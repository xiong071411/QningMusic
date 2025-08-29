package com.watch.limusic;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.watch.limusic.cache.CacheManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsGeneralActivity extends AppCompatActivity {
    private static final String PREFS = "player_prefs";
    private static final String KEY_FORCE_TRANSCODE = "force_transcode_non_mp3";
    private static final String KEY_FORCE_DOWNLOAD_TRANSCODE = "force_transcode_download_non_mp3";
    private static final String KEY_LOW_POWER_MODE = "low_power_mode_enabled";
    private static final String KEY_PROGRESS_MODE = "progress_broadcast_mode";

    private TextView txtCacheSummary;
    private TextView txtTransSummary;
    private TextView txtDownloadTransSummary;
    private TextView txtLowPowerSummary;
    private TextView txtProgressSummary;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_general);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        txtCacheSummary = findViewById(R.id.txt_cache_summary);
        txtTransSummary = findViewById(R.id.txt_transcoding_summary);
        txtDownloadTransSummary = findViewById(R.id.txt_download_transcoding_summary);
        txtLowPowerSummary = findViewById(R.id.txt_low_power_summary);
        txtProgressSummary = findViewById(R.id.txt_progress_summary);

        findViewById(R.id.card_clear_cache).setOnClickListener(v -> executor.execute(() -> {
            CacheManager.clearCache(this);
            runOnUiThread(() -> {
                Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show();
                updateCacheSummary();
            });
        }));
        findViewById(R.id.card_cache_settings).setOnClickListener(v ->
                startActivity(new Intent(this, CacheSettingsActivity.class)));
        findViewById(R.id.card_transcoding).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_FORCE_TRANSCODE, false);
            sp.edit().putBoolean(KEY_FORCE_TRANSCODE, !enabled).apply();
            updateTranscodingSummary();
        });
        findViewById(R.id.card_download_transcoding).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, false);
            sp.edit().putBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, !enabled).apply();
            updateDownloadTranscodingSummary();
        });
        findViewById(R.id.card_low_power).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_LOW_POWER_MODE, false);
            sp.edit().putBoolean(KEY_LOW_POWER_MODE, !enabled).apply();
            updateLowPowerSummary();
            Toast.makeText(this, !enabled ? "已开启低耗模式" : "已关闭低耗模式", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.card_progress_broadcast).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            int mode = sp.getInt(KEY_PROGRESS_MODE, 0);
            int next = (mode + 1) % 3;
            sp.edit().putInt(KEY_PROGRESS_MODE, next).apply();
            updateProgressSummary();
        });

        updateCacheSummary();
        updateTranscodingSummary();
        updateDownloadTranscodingSummary();
        updateLowPowerSummary();
        updateProgressSummary();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void updateCacheSummary() {
        executor.execute(() -> {
            long bytes = CacheManager.getCacheUsageBytes(this);
            long mb = bytes / (1024 * 1024);
            runOnUiThread(() -> txtCacheSummary.setText("清理缓存 (已用 " + mb + " MB)"));
        });
    }

    private void updateTranscodingSummary() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = sp.getBoolean(KEY_FORCE_TRANSCODE, false);
        txtTransSummary.setText(enabled ? "已开启（非MP3→320kbps）" : "关闭");
    }

    private void updateDownloadTranscodingSummary() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = sp.getBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, false);
        txtDownloadTransSummary.setText(enabled ? "已开启（非MP3→320kbps）" : "关闭");
    }

    private void updateLowPowerSummary() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = sp.getBoolean(KEY_LOW_POWER_MODE, false);
        txtLowPowerSummary.setText(enabled ? "已开启（限制部分功能）" : "关闭");
    }

    private void updateProgressSummary() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int mode = sp.getInt(KEY_PROGRESS_MODE, 0);
        String txt = (mode == 0) ? "正常（0.5 秒）" : (mode == 1) ? "慢速（1.5 秒）" : "最慢（2.5 秒）";
        txtProgressSummary.setText(txt);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
} 