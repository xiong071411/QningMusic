package com.watch.limusic;

import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * 听歌记录服务器设置页
 * - 开启/关闭
 * - 服务器、用户名、密码
 * - 连接测试（/api/listens GET 不一定存在，使用 HEAD / 或 GET / 健康检查；若站点有 /api/listens?page=1，可尝试 GET）
 */
public class ListenReportSettingsActivity extends AppCompatActivity {

    private static final String PREFS = "listen_report_settings";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ALLOW_INSECURE = "allow_insecure"; // 忽略主机名校验（仅测试用）

    private Switch switchEnable;
    private TextInputEditText inputBaseUrl;
    private TextInputEditText inputUsername;
    private TextInputEditText inputPassword;
    private Button btnTest;
    private Button btnSave;
    private Switch switchInsecure;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_listen_report_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        switchEnable = findViewById(R.id.switch_enable);
        inputBaseUrl = findViewById(R.id.base_url);
        inputUsername = findViewById(R.id.username);
        inputPassword = findViewById(R.id.password);
        btnTest = findViewById(R.id.test_button);
        btnSave = findViewById(R.id.save_button);
        switchInsecure = findViewById(R.id.switch_insecure);

        loadPrefs();

        btnTest.setOnClickListener(v -> testConnection());
        btnSave.setOnClickListener(v -> savePrefs());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = sp.getBoolean(KEY_ENABLED, false);
        String baseUrl = sp.getString(KEY_BASE_URL, "");
        String username = sp.getString(KEY_USERNAME, "");
        String password = sp.getString(KEY_PASSWORD, "");
        switchEnable.setChecked(enabled);
        inputBaseUrl.setText(baseUrl);
        inputUsername.setText(username);
        inputPassword.setText(password);
        Switch s = switchInsecure; if (s != null) s.setChecked(sp.getBoolean(KEY_ALLOW_INSECURE, false));
    }

    private void savePrefs() {
        String baseUrl = text(inputBaseUrl);
        String username = text(inputUsername);
        String password = text(inputPassword);
        boolean enabled = switchEnable.isChecked();

        if (enabled) {
            if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
                Toast.makeText(this, "请填写完整信息，或关闭开关", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .putBoolean(KEY_ALLOW_INSECURE, switchInsecure != null && switchInsecure.isChecked())
                .apply();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void testConnection() {
        String baseUrl = text(inputBaseUrl);
        String username = text(inputUsername);
        String password = text(inputPassword);
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, "请先填写服务器、用户名和密码", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage("正在测试连接…");
        dialog.setCancelable(false);
        dialog.show();

        new Thread(() -> {
            boolean ok = false;
            String msg = null;
            try {
                String url = trimSlash(baseUrl) + "/api/listens?page=1&limit=1";
                boolean allowInsecure = switchInsecure != null && switchInsecure.isChecked();
                OkHttpClient.Builder b = new OkHttpClient.Builder()
                        .connectTimeout(1500, TimeUnit.MILLISECONDS)
                        .readTimeout(1500, TimeUnit.MILLISECONDS)
                        .callTimeout(2000, TimeUnit.MILLISECONDS)
                        ;
                if (allowInsecure) {
                    String host = null; try { java.net.URI u = new java.net.URI(baseUrl); host = u.getHost(); } catch (Exception ignore) {}
                    final String finalHost = host;
                    b.hostnameVerifier((hostname, session) -> finalHost != null && hostname != null && hostname.equalsIgnoreCase(finalHost));
                }
                OkHttpClient client = b.build();
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", Credentials.basic(username, password))
                        .get()
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    ok = resp.isSuccessful();
                    msg = ok ? "连接成功" : ("HTTP " + resp.code());
                }
            } catch (Exception e) {
                msg = e.getMessage();
            }
            boolean okFinal = ok; String msgFinal = msg;
            runOnUiThread(() -> {
                try { dialog.dismiss(); } catch (Throwable ignore) {}
                Toast.makeText(this, okFinal ? msgFinal : ("连接失败：" + msgFinal), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == '/') n--;
        return s.substring(0, n);
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}


