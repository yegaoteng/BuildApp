package com.buildapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE = 100;
    private static final int REQUEST_MANAGE_STORAGE = 101;

    private TextView tvDirStatus;
    private TextView tvGradleStatus;
    private EditText etCustomCmd;
    private BuildManager buildManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buildManager = BuildManager.getInstance(this);

        tvDirStatus = findViewById(R.id.tvDirStatus);
        tvGradleStatus = findViewById(R.id.tvGradleStatus);
        etCustomCmd = findViewById(R.id.etCustomCmd);

        Button btnSelectDir = findViewById(R.id.btnSelectDir);
        Button btnDeploy = findViewById(R.id.btnDeploy);
        Button btnBuild = findViewById(R.id.btnBuild);

        btnSelectDir.setOnClickListener(v -> onSelectDirectory());
        btnDeploy.setOnClickListener(v -> onDeployEnvironment());
        btnBuild.setOnClickListener(v -> onBuildApp());

        updateStatus();
        requestStoragePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        String dir = buildManager.getWorkingDir();
        if (dir != null && !dir.isEmpty()) {
            tvDirStatus.setText(dir);
            tvDirStatus.setTextColor(getColor(R.color.accent));
        } else {
            tvDirStatus.setText(R.string.no_directory_selected);
            tvDirStatus.setTextColor(getColor(R.color.text_muted));
        }

        if (buildManager.isGradleInstalled()) {
            tvGradleStatus.setText("已就绪");
            tvGradleStatus.setTextColor(getColor(R.color.accent));
        } else {
            tvGradleStatus.setText("未部署");
            tvGradleStatus.setTextColor(getColor(R.color.danger));
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要存储权限才能访问项目文件", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "所有文件访问权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "未授予所有文件访问权限，可能无法访问项目目录", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void onSelectDirectory() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要文件访问权限")
                    .setMessage("请授予所有文件访问权限，以便选择和构建项目目录。")
                    .setPositiveButton("去授权", (d, w) -> requestStoragePermission())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        showDirectoryPicker();
    }

    private void showDirectoryPicker() {
        File startDir = Environment.getExternalStorageDirectory();
        new DirectoryPickerDialog(this, startDir, dir -> {
            buildManager.setWorkingDir(dir.getAbsolutePath());
            updateStatus();
            Toast.makeText(this, "已选择: " + dir.getName(), Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void onDeployEnvironment() {
        Intent intent = new Intent(this, TerminalActivity.class);
        intent.putExtra(TerminalActivity.EXTRA_SESSION, TerminalActivity.SESSION_INSTALL);
        startActivity(intent);
    }

    private void onBuildApp() {
        if (buildManager.getWorkingDir() == null || buildManager.getWorkingDir().isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("请先选择工作目录")
                    .setPositiveButton(R.string.confirm, null)
                    .show();
            return;
        }
        Intent intent = new Intent(this, TerminalActivity.class);
        intent.putExtra(TerminalActivity.EXTRA_SESSION, TerminalActivity.SESSION_BUILD);
        intent.putExtra(TerminalActivity.EXTRA_CUSTOM_CMD, etCustomCmd.getText().toString().trim());
        startActivity(intent);
    }
}
