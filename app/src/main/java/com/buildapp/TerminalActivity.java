package com.buildapp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class TerminalActivity extends AppCompatActivity implements BuildManager.Listener {

    public static final String EXTRA_SESSION = "session";
    public static final String EXTRA_CUSTOM_CMD = "custom_cmd";
    public static final int SESSION_INSTALL = 1;
    public static final int SESSION_BUILD = 2;

    private TextView tvTerminal;
    private ScrollView scrollTerminal;
    private Button btnReturnHome;
    private ImageButton btnBack;
    private TextView tvTitle;

    private BuildManager buildManager;
    private int sessionType;
    private boolean autoStarted = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        buildManager = BuildManager.getInstance(this);

        tvTerminal = findViewById(R.id.tvTerminal);
        scrollTerminal = findViewById(R.id.scrollTerminal);
        btnReturnHome = findViewById(R.id.btnReturnHome);
        btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTerminalTitle);

        tvTerminal.setMovementMethod(new ScrollingMovementMethod());

        sessionType = getIntent().getIntExtra(EXTRA_SESSION, 0);

        btnBack.setOnClickListener(v -> onBackPressed());
        btnReturnHome.setOnClickListener(v -> finish());

        // 显示已有输出（如果有）
        tvTerminal.setText(buildManager.getOutput());
        scrollToBottom();
    }

    @Override
    protected void onStart() {
        super.onStart();
        buildManager.addListener(this);

        BuildManager.State state = buildManager.getState();
        if (state == BuildManager.State.IDLE && !autoStarted) {
            autoStarted = true;
            autoStart();
        } else {
            updateTitle();
        }
        updateReturnButton();
    }

    @Override
    protected void onStop() {
        super.onStop();
        buildManager.removeListener(this);
    }

    private void autoStart() {
        if (sessionType == SESSION_INSTALL) {
            tvTitle.setText("部署 Gradle 环境");
            buildManager.startGradleInstall();
        } else if (sessionType == SESSION_BUILD) {
            tvTitle.setText("构建应用");
            String customCmd = getIntent().getStringExtra(EXTRA_CUSTOM_CMD);
            buildManager.startBuild(customCmd);
        }
    }

    private void updateTitle() {
        if (sessionType == SESSION_INSTALL) {
            tvTitle.setText("部署 Gradle 环境");
        } else if (sessionType == SESSION_BUILD) {
            tvTitle.setText("构建应用");
        }
    }

    private void updateReturnButton() {
        // 仅在安装会话且进程空闲时显示"返回主界面"
        BuildManager.State state = buildManager.getState();
        if (sessionType == SESSION_INSTALL && state == BuildManager.State.IDLE) {
            btnReturnHome.setVisibility(View.VISIBLE);
        } else {
            btnReturnHome.setVisibility(View.GONE);
        }
    }

    // ---- BuildManager.Listener ----

    @Override
    public void onOutput(String text) {
        runOnUiThread(() -> {
            tvTerminal.append(text);
            scrollToBottom();
        });
    }

    @Override
    public void onStateChanged(BuildManager.State state) {
        runOnUiThread(this::updateReturnButton);
    }

    @Override
    public void onFinished(BuildManager.Session session, int exitCode) {
        runOnUiThread(() -> {
            updateReturnButton();
            if (session == BuildManager.Session.INSTALL) {
                if (exitCode == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.install_success_title)
                            .setMessage(R.string.install_success_message)
                            .setCancelable(false)
                            .setPositiveButton(R.string.return_home, (d, w) -> finish())
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.install_failed_title)
                            .setMessage("环境部署失败，请查看终端日志。")
                            .setPositiveButton(R.string.confirm, null)
                            .show();
                }
            }
        });
    }

    private void scrollToBottom() {
        handler.postDelayed(() ->
                scrollTerminal.fullScroll(View.FOCUS_DOWN), 50);
    }

    // ---- 返回处理 ----

    @Override
    public void onBackPressed() {
        BuildManager.State state = buildManager.getState();
        if (state == BuildManager.State.RUNNING_INSTALL || state == BuildManager.State.RUNNING_BUILD) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.stop_build_title)
                    .setMessage(R.string.stop_build_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.confirm, (d, w) -> {
                        buildManager.stop();
                        finish();
                    })
                    .show();
        } else {
            super.onBackPressed();
        }
    }
}
