package com.watch.limusic;

import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;
import com.watch.limusic.api.NavidromeApi;

/**
 * 独立的 Navidrome 服务器详细设置页面。
 * 提供地址、端口、用户名、密码编辑，以及连接测试与保存功能。
 */
public class NavidromeSettingsActivity extends AppCompatActivity {

    private TextInputEditText serverUrlInput;
    private TextInputEditText serverPortInput;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private Button testButton;
    private Button saveButton;

    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navidrome_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        preferences = getSharedPreferences("navidrome_settings", MODE_PRIVATE);
        loadSettings();

        testButton.setOnClickListener(v -> testConnection());
        saveButton.setOnClickListener(v -> saveSettings());
    }

    private void initViews() {
        serverUrlInput = findViewById(R.id.server_url);
        serverPortInput = findViewById(R.id.server_port);
        usernameInput = findViewById(R.id.username);
        passwordInput = findViewById(R.id.password);
        testButton = findViewById(R.id.test_button);
        saveButton = findViewById(R.id.save_button);
    }

    private void loadSettings() {
        serverUrlInput.setText(preferences.getString("server_url", ""));
        serverPortInput.setText(preferences.getString("server_port", "4533"));
        usernameInput.setText(preferences.getString("username", ""));
        passwordInput.setText(preferences.getString("password", ""));
    }

    private void testConnection() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        String serverPort = serverPortInput.getText().toString().trim();
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请填写所有必填字段", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "http://" + serverUrl;
        }
        if (serverPort.isEmpty()) {
            serverPort = "4533";
        }

        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage("正在测试连接...");
        dialog.setCancelable(false);
        dialog.show();

        final String finalServerUrl = serverUrl;
        final int finalPort;
        try { finalPort = Integer.parseInt(serverPort); } catch (Exception e) { dialog.dismiss(); Toast.makeText(this, "端口无效", Toast.LENGTH_SHORT).show(); return; }

        new Thread(() -> {
            try {
                boolean ok = com.watch.limusic.api.NavidromeApi.ping(finalServerUrl, finalPort, username, password);
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, ok ? "连接成功！" : "连接失败：服务器返回错误", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "连接失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void saveSettings() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        String serverPort = serverPortInput.getText().toString().trim();
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请填写所有必填字段", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "http://" + serverUrl;
        }
        if (serverPort.isEmpty()) {
            serverPort = "4533";
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("server_url", serverUrl);
        editor.putString("server_port", serverPort);
        editor.putString("username", username);
        editor.putString("password", password);
        editor.apply();

        // 发送配置更新广播
        try {
            android.content.Intent intent = new android.content.Intent(com.watch.limusic.api.NavidromeApi.ACTION_NAVIDROME_CONFIG_UPDATED);
            sendBroadcast(intent);
        } catch (Exception ignore) {}

        Toast.makeText(this, "已切换至新服务器", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
} 