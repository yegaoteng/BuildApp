package com.buildapp;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 简单的目录选择对话框
 */
public class DirectoryPickerDialog {

    public interface OnDirectorySelectedListener {
        void onSelected(File directory);
    }

    private final AlertDialog dialog;
    private final ListView listView;
    private final TextView tvPath;
    private File currentDir;
    private final OnDirectorySelectedListener listener;
    private final List<File> dirs = new ArrayList<>();

    public DirectoryPickerDialog(Context context, File startDir, OnDirectorySelectedListener listener) {
        this.listener = listener;
        this.currentDir = startDir.exists() ? startDir : new File("/");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 8);

        tvPath = new TextView(context);
        tvPath.setTextSize(13);
        tvPath.setPadding(0, 0, 0, 12);
        layout.addView(tvPath);

        listView = new ListView(context);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        layout.addView(listView);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            File f = dirs.get(position);
            if (f.isDirectory()) {
                currentDir = f;
                refresh();
            }
        });

        dialog = new AlertDialog.Builder(context)
                .setTitle("选择工作目录")
                .setView(layout)
                .setPositiveButton("选择此目录", (d, w) -> {
                    if (listener != null) listener.onSelected(currentDir);
                })
                .setNegativeButton("取消", null)
                .create();

        refresh();
    }

    private void refresh() {
        tvPath.setText("当前: " + currentDir.getAbsolutePath());
        dirs.clear();

        // 父目录
        File parent = currentDir.getParentFile();
        if (parent != null) {
            dirs.add(parent);
        }

        File[] children = currentDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() && !f.isHidden();
            }
        });
        if (children != null) {
            List<File> childList = new ArrayList<>(Arrays.asList(children));
            Collections.sort(childList, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            dirs.addAll(childList);
        }

        ArrayAdapter<File> adapter = new ArrayAdapter<File>(
                dialog.getContext(),
                android.R.layout.simple_list_item_1,
                dirs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                File f = dirs.get(position);
                String name;
                if (position == 0 && currentDir.getParentFile() != null && f.equals(currentDir.getParentFile())) {
                    name = ".. (上级目录)";
                } else {
                    name = f.getName();
                }
                tv.setText(name);
                tv.setTextSize(14);
                return v;
            }
        };
        listView.setAdapter(adapter);
    }

    public void show() {
        dialog.show();
    }
}
