package com.buildapp;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 单例：管理 Gradle 环境安装与应用构建进程
 * 环境包（JDK + Gradle）已打包在 APK assets 中，首次运行时解压到 app 私有目录
 */
public class BuildManager {

    private static final String TAG = "BuildManager";

    public static final String GRADLE_VERSION = "8.14.6";

    public enum State { IDLE, RUNNING_INSTALL, RUNNING_BUILD }
    public enum Session { NONE, INSTALL, BUILD }

    public interface Listener {
        void onOutput(String text);
        void onStateChanged(State state);
        void onFinished(Session session, int exitCode);
    }

    private static BuildManager instance;

    private final Context context;
    private State state = State.IDLE;
    private Session currentSession = Session.NONE;
    private Process currentProcess;
    private final StringBuilder outputBuffer = new StringBuilder();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String workingDir;
    private File gradleHome;
    private File jdkHome;

    private BuildManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.gradleHome = new File(context.getFilesDir(), "gradle/gradle-" + GRADLE_VERSION);
        this.jdkHome = new File(context.getFilesDir(), "jdk");
    }

    public static synchronized BuildManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new BuildManager(ctx);
        }
        return instance;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public State getState() { return state; }
    public Session getCurrentSession() { return currentSession; }
    public String getOutput() { return outputBuffer.toString(); }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String dir) { this.workingDir = dir; }

    public boolean isGradleInstalled() {
        return new File(gradleHome, "bin/gradle").exists();
    }

    public boolean isJdkInstalled() {
        return new File(jdkHome, "bin/java").exists();
    }

    public String getGradleBin() {
        return new File(gradleHome, "bin/gradle").getAbsolutePath();
    }

    private void setState(State s) {
        state = s;
        mainHandler.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onStateChanged(s);
        });
    }

    private void appendOutput(String text) {
        outputBuffer.append(text);
        if (outputBuffer.length() > 800_000) {
            outputBuffer.delete(0, outputBuffer.length() - 600_000);
        }
        mainHandler.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onOutput(text);
        });
    }

    private void finish(Session session, int exitCode) {
        currentProcess = null;
        currentSession = Session.NONE;
        setState(State.IDLE);
        mainHandler.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onFinished(session, exitCode);
        });
    }

    public void stop() {
        if (currentProcess != null) {
            try { currentProcess.destroyForcibly(); } catch (Exception e) { Log.e(TAG, "stop error", e); }
            appendOutput("\n[进程已被强制停止]\n");
        }
    }

    /** 部署 Gradle 环境（从 APK assets 解压） */
    public void startGradleInstall() {
        if (state != State.IDLE) return;
        outputBuffer.setLength(0);
        currentSession = Session.INSTALL;
        setState(State.RUNNING_INSTALL);

        new Thread(() -> {
            try {
                ensureJdk();
                installGradle();
                finish(Session.INSTALL, 0);
            } catch (Exception e) {
                appendOutput("\n[错误] " + e.getMessage() + "\n");
                Log.e(TAG, "install failed", e);
                finish(Session.INSTALL, 1);
            }
        }, "gradle-install").start();
    }

    /** 构建应用 */
    public void startBuild(String customCommand) {
        if (state != State.IDLE) return;
        if (workingDir == null || workingDir.isEmpty()) {
            appendOutput("[错误] 请先选择工作目录\n");
            return;
        }
        outputBuffer.setLength(0);
        currentSession = Session.BUILD;
        setState(State.RUNNING_BUILD);

        new Thread(() -> {
            try {
                runBuild(customCommand);
            } catch (Exception e) {
                appendOutput("\n[错误] " + e.getMessage() + "\n");
                Log.e(TAG, "build failed", e);
                finish(Session.BUILD, 1);
            }
        }, "gradle-build").start();
    }

    // ---------- JDK 安装（从 assets 解压） ----------
    private void ensureJdk() throws Exception {
        if (isJdkInstalled()) {
            appendOutput("[JDK] 已安装: " + jdkHome.getAbsolutePath() + "\n");
            return;
        }
        appendOutput("[JDK] 正在从应用包解压 JDK 17 (aarch64)...\n");
        File debFile = new File(context.getCacheDir(), "jdk-aarch64.deb");
        copyAsset("env/jdk-aarch64.deb", debFile);
        appendOutput("[JDK] 解压中...\n");
        File extractDir = new File(context.getFilesDir(), "jdk_extract");
        extractDir.mkdirs();
        // ar x 解压 deb，再解压 data.tar.xz
        execShell("cd \"" + extractDir.getAbsolutePath() + "\" && ar x \"" + debFile.getAbsolutePath() + "\" && tar -xJf data.tar.xz");
        // 查找 java 二进制
        File javaBin = findFileRecursive(extractDir, "java");
        if (javaBin == null) {
            throw new IOException("未找到 java 二进制文件");
        }
        jdkHome = javaBin.getParentFile().getParentFile();
        appendOutput("[JDK] 安装完成: " + jdkHome.getAbsolutePath() + "\n");
        chmodRecursive(jdkHome);
        // 清理临时文件
        debFile.delete();
        deleteRecursive(new File(extractDir, "data.tar.xz"));
        deleteRecursive(new File(extractDir, "control.tar.xz"));
        deleteRecursive(new File(extractDir, "debian-binary"));
    }

    // ---------- Gradle 安装（从 assets 解压） ----------
    private void installGradle() throws Exception {
        if (isGradleInstalled()) {
            appendOutput("[Gradle] 已安装: " + gradleHome.getAbsolutePath() + "\n");
            verifyGradle();
            return;
        }
        appendOutput("[Gradle] 正在从应用包解压 Gradle " + GRADLE_VERSION + "...\n");
        File zipFile = new File(context.getCacheDir(), "gradle-bin.zip");
        copyAsset("env/gradle-bin.zip", zipFile);
        appendOutput("[Gradle] 解压中...\n");
        File gradleBase = new File(context.getFilesDir(), "gradle");
        gradleBase.mkdirs();
        execShell("unzip -o -q \"" + zipFile.getAbsolutePath() + "\" -d \"" + gradleBase.getAbsolutePath() + "\"");
        zipFile.delete();
        chmodRecursive(gradleHome);
        appendOutput("[Gradle] 安装完成: " + gradleHome.getAbsolutePath() + "\n");
        verifyGradle();
    }

    private void verifyGradle() throws Exception {
        appendOutput("[Gradle] 验证安装...\n");
        ProcessBuilder pb = new ProcessBuilder(getGradleBin(), "--version");
        pb.environment().put("JAVA_HOME", jdkHome.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        readStream(p.getInputStream());
        int code = p.waitFor();
        if (code != 0) throw new IOException("Gradle 验证失败，退出码: " + code);
    }

    // ---------- 构建执行 ----------
    private void runBuild(String customCommand) throws Exception {
        if (!isJdkInstalled()) ensureJdk();
        if (!isGradleInstalled()) {
            appendOutput("[构建] Gradle 未安装，正在自动部署...\n");
            installGradle();
        }

        appendOutput("[构建] 工作目录: " + workingDir + "\n");

        String cmd;
        if (customCommand != null && !customCommand.trim().isEmpty()) {
            cmd = customCommand.trim();
            appendOutput("[构建] 使用自定义命令: " + cmd + "\n");
        } else {
            File gradlew = new File(workingDir, "gradlew");
            if (gradlew.exists()) {
                cmd = "./gradlew build --console=plain --no-daemon";
                appendOutput("[构建] 使用项目 gradlew\n");
            } else {
                cmd = "\"" + getGradleBin() + "\" build --console=plain --no-daemon";
                appendOutput("[构建] 使用内置 Gradle\n");
            }
        }
        appendOutput("[构建] 执行: " + cmd + "\n");
        appendOutput("========================================\n");

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "cd \"" + workingDir + "\" && " + cmd);
        pb.directory(new File(workingDir));
        pb.environment().put("JAVA_HOME", jdkHome.getAbsolutePath());
        pb.environment().put("GRADLE_HOME", gradleHome.getAbsolutePath());
        pb.environment().put("PATH",
                new File(jdkHome, "bin").getAbsolutePath() + ":" +
                new File(gradleHome, "bin").getAbsolutePath() + ":" +
                pb.environment().get("PATH"));
        pb.redirectErrorStream(true);

        currentProcess = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                appendOutput(new String(buf, 0, n));
            }
        }
        int exitCode = currentProcess.waitFor();
        appendOutput("\n========================================\n");
        appendOutput("[构建] 进程退出码: " + exitCode + "\n");
        if (exitCode == 0) {
            appendOutput("[构建] 构建成功！产物位于 " + workingDir + "/app/build/outputs/\n");
        } else {
            appendOutput("[构建] 构建失败，请检查上方日志\n");
        }
        finish(Session.BUILD, exitCode);
    }

    // ---------- 工具方法 ----------
    private void copyAsset(String assetPath, File dest) throws IOException {
        AssetManager am = context.getAssets();
        try (InputStream in = am.open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total % (10 * 1024 * 1024) < 65536) {
                    appendOutput("  已复制 " + (total / 1024 / 1024) + "MB...\n");
                }
            }
        }
    }

    private void execShell(String cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        readStream(p.getInputStream());
        try {
            int code = p.waitFor();
            if (code != 0) throw new IOException("命令执行失败: " + cmd + " (exit " + code + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
    }

    private File findFileRecursive(File dir, String name) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFileRecursive(f, name);
                if (found != null) return found;
            } else if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    private void chmodRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) chmodRecursive(f);
        } else {
            if (dir.getParent().endsWith("bin") || dir.getName().endsWith(".sh")) {
                dir.setExecutable(true, false);
            }
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        f.delete();
    }

    private String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                appendOutput(new String(buf, 0, n));
            }
        }
        return sb.toString();
    }
}
