package com.buildapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 单例：管理 Gradle 环境安装与应用构建进程
 */
public class BuildManager {

    private static final String TAG = "BuildManager";

    public static final String GRADLE_VERSION = "8.14.6";
    public static final String PROXY = "https://gh-proxy.com/";

    // Gradle 下载源（按优先级尝试）
    private static final String[] GRADLE_URLS = {
            PROXY + "https://github.com/gradle/gradle/releases/download/v" + GRADLE_VERSION + "/gradle-" + GRADLE_VERSION + "-bin.zip",
            "https://mirrors.cloud.tencent.com/gradle/gradle-" + GRADLE_VERSION + "-bin.zip",
            "https://services.gradle.org/distributions/gradle-" + GRADLE_VERSION + "-bin.zip"
    };

    // Android JDK (aarch64) 下载源 - Termux openjdk-17
    private static final String[] JDK_URLS = {
            "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/openjdk-17_17.0.13-1_aarch64.deb",
            "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/openjdk-17_17.0.12-1_aarch64.deb",
            "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/openjdk-17_17.0.11-1_aarch64.deb"
    };

    public enum State { IDLE, RUNNING_INSTALL, RUNNING_BUILD }
    public enum Session { NONE, INSTALL, BUILD }

    public interface Listener {
        void onOutput(String text);
        void onStateChanged(State state);
        void onFinished(Session session, int exitCode);
    }

    private static BuildManager instance;

    private Context context;
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

    public String getJavaBin() {
        return new File(jdkHome, "bin/java").getAbsolutePath();
    }

    private void setState(State s) {
        state = s;
        mainHandler.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onStateChanged(s);
        });
    }

    private void appendOutput(String text) {
        outputBuffer.append(text);
        // keep buffer from growing unbounded
        if (outputBuffer.length() > 500_000) {
            outputBuffer.delete(0, outputBuffer.length() - 400_000);
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

    /** 停止当前进程 */
    public void stop() {
        if (currentProcess != null) {
            try {
                currentProcess.destroyForcibly();
            } catch (Exception e) {
                Log.e(TAG, "stop error", e);
            }
            appendOutput("\n[进程已被强制停止]\n");
        }
    }

    /** 部署 Gradle 环境 */
    public void startGradleInstall() {
        if (state != State.IDLE) return;
        outputBuffer.setLength(0);
        currentSession = Session.INSTALL;
        setState(State.RUNNING_INSTALL);

        new Thread(() -> {
            try {
                // 1. 确保 JDK 可用
                ensureJdk();
                // 2. 下载并安装 Gradle
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

    // ---------- JDK 安装 ----------
    private void ensureJdk() throws Exception {
        if (isJdkInstalled()) {
            appendOutput("[JDK] 已安装: " + jdkHome.getAbsolutePath() + "\n");
            return;
        }
        // 先尝试系统 java
        String systemJava = findSystemJava();
        if (systemJava != null) {
            appendOutput("[JDK] 使用系统 Java: " + systemJava + "\n");
            // 创建符号链接到 jdkHome
            File javaParent = new File(systemJava).getParentFile().getParentFile();
            jdkHome = javaParent;
            return;
        }
        appendOutput("[JDK] 正在下载 Android JDK (openjdk-17 aarch64)...\n");
        File debFile = new File(context.getCacheDir(), "openjdk-17.deb");
        downloadWithFallbacks(JDK_URLS, debFile);
        appendOutput("[JDK] 下载完成，解压中...\n");
        extractDeb(debFile, jdkHome.getParentFile());
        // Termux 的 JDK 解压在 data/data/com.termux/files/usr/lib/jvm/java-17-openjdk 下
        // 但我们解压到了 filesDir，需要找到 java 二进制
        File javaBin = findFileRecursive(jdkHome.getParentFile(), "java");
        if (javaBin != null) {
            jdkHome = javaBin.getParentFile().getParentFile();
            appendOutput("[JDK] 安装完成: " + jdkHome.getAbsolutePath() + "\n");
        } else {
            throw new IOException("未找到 java 二进制文件");
        }
        // 确保可执行权限
        chmodRecursive(jdkHome);
    }

    private String findSystemJava() {
        String[] paths = {"/system/bin/java", "/system/xbin/java", "/data/data/com.termux/files/usr/bin/java", "/usr/bin/java"};
        for (String p : paths) {
            File f = new File(p);
            if (f.exists() && f.canExecute()) return p;
        }
        // 尝试 PATH
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "which java"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            if (line != null && !line.isEmpty()) {
                File f = new File(line.trim());
                if (f.exists()) return line.trim();
            }
        } catch (IOException ignored) {}
        return null;
    }

    // ---------- Gradle 安装 ----------
    private void installGradle() throws Exception {
        if (isGradleInstalled()) {
            appendOutput("[Gradle] 已安装: " + gradleHome.getAbsolutePath() + "\n");
            verifyGradle();
            return;
        }
        appendOutput("[Gradle] 正在下载 Gradle " + GRADLE_VERSION + " (走代理)...\n");
        File zipFile = new File(context.getCacheDir(), "gradle-" + GRADLE_VERSION + "-bin.zip");
        downloadWithFallbacks(GRADLE_URLS, zipFile);
        appendOutput("[Gradle] 下载完成 (" + zipFile.length() / 1024 / 1024 + "MB)，解压中...\n");
        File gradleBase = new File(context.getFilesDir(), "gradle");
        gradleBase.mkdirs();
        unzip(zipFile, gradleBase);
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
        String out = readStream(p.getInputStream());
        appendOutput(out);
        int code = p.waitFor();
        if (code != 0) throw new IOException("Gradle 验证失败，退出码: " + code);
    }

    // ---------- 构建执行 ----------
    private void runBuild(String customCommand) throws Exception {
        // 确保 JDK 和 Gradle 就绪
        if (!isJdkInstalled()) ensureJdk();
        if (!isGradleInstalled()) {
            appendOutput("[构建] Gradle 未安装，正在自动部署...\n");
            installGradle();
        }

        File workDir = new File(workingDir);
        appendOutput("[构建] 工作目录: " + workingDir + "\n");

        // 确定构建命令
        String cmd;
        if (customCommand != null && !customCommand.trim().isEmpty()) {
            cmd = customCommand.trim();
            appendOutput("[构建] 使用自定义命令: " + cmd + "\n");
        } else {
            File gradlew = new File(workDir, "gradlew");
            if (gradlew.exists()) {
                cmd = "./gradlew build --console=plain --no-daemon";
                appendOutput("[构建] 使用项目 gradlew\n");
            } else {
                cmd = "\"" + getGradleBin() + "\" build --console=plain --no-daemon";
                appendOutput("[构建] 使用系统 Gradle\n");
            }
        }
        appendOutput("[构建] 执行: " + cmd + "\n");
        appendOutput("========================================\n");

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "cd \"" + workingDir + "\" && " + cmd);
        pb.directory(workDir);
        pb.environment().put("JAVA_HOME", jdkHome.getAbsolutePath());
        pb.environment().put("GRADLE_HOME", gradleHome.getAbsolutePath());
        pb.environment().put("PATH",
                new File(jdkHome, "bin").getAbsolutePath() + ":" +
                new File(gradleHome, "bin").getAbsolutePath() + ":" +
                pb.environment().get("PATH"));
        pb.redirectErrorStream(true);

        currentProcess = pb.start();
        // 读取输出
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
    private void downloadWithFallbacks(String[] urls, File dest) throws IOException {
        IOException last = null;
        for (String url : urls) {
            try {
                appendOutput("  尝试: " + url + "\n");
                downloadFile(url, dest);
                if (dest.length() > 1000) {
                    appendOutput("  下载成功 (" + dest.length() / 1024 + "KB)\n");
                    return;
                }
            } catch (IOException e) {
                last = e;
                appendOutput("  失败: " + e.getMessage() + "\n");
            }
        }
        throw last != null ? last : new IOException("所有下载源均失败");
    }

    private void downloadFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "BuildApp/1.0");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total % (5 * 1024 * 1024) < 8192) {
                    appendOutput("  已下载 " + (total / 1024 / 1024) + "MB...\r");
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        destDir.mkdirs();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File outFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** 解压 .deb 包（ar 归档）到 destDir */
    private void extractDeb(File debFile, File destDir) throws IOException {
        destDir.mkdirs();
        // .deb 是 ar 归档: debian-binary, control.tar.*, data.tar.*
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "cd \"" + destDir.getAbsolutePath() + "\" && " +
                "ar x \"" + debFile.getAbsolutePath() + "\" && " +
                "tar -xf data.tar.* -C . 2>/dev/null || tar -xJf data.tar.xz -C . 2>/dev/null || tar -xzf data.tar.gz -C . 2>/dev/null; " +
                "rm -f debian-binary control.tar.* data.tar.*");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        readStream(p.getInputStream());
        try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    private String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                // also append to terminal output
                appendOutput(new String(buf, 0, n));
            }
        }
        return sb.toString();
    }
}
