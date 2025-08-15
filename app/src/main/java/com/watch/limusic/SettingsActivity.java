package com.watch.limusic;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.content.SharedPreferences;

import com.watch.limusic.cache.CacheManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {
    private TextView txtCacheSummary;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView txtTransSummary;
    private TextView txtDownloadTransSummary;

    private static final String PREFS = "player_prefs";
    private static final String KEY_FORCE_TRANSCODE = "force_transcode_non_mp3";
    private static final String KEY_FORCE_DOWNLOAD_TRANSCODE = "force_transcode_download_non_mp3";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // 跳转至 Navidrome 详细设置页
        findViewById(R.id.card_navidrome).setOnClickListener(v ->
                startActivity(new Intent(this, NavidromeSettingsActivity.class)));

        // 缓存相关卡片配置
        txtCacheSummary = findViewById(R.id.txt_cache_summary);

        // 一键清理缓存
        findViewById(R.id.card_clear_cache).setOnClickListener(v -> {
            executor.execute(() -> {
                CacheManager.clearCache(this);
                runOnUiThread(() -> {
                    Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show();
                    updateCacheSummary();
                });
            });
        });

        // 缓存详情
        findViewById(R.id.card_cache_settings).setOnClickListener(v ->
                startActivity(new Intent(this, CacheSettingsActivity.class)));

        // 强制转码（非MP3 → 320kbps）开关
        txtTransSummary = findViewById(R.id.txt_transcoding_summary);
        updateTranscodingSummary();
        findViewById(R.id.card_transcoding).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_FORCE_TRANSCODE, false);
            sp.edit().putBoolean(KEY_FORCE_TRANSCODE, !enabled).apply();
            updateTranscodingSummary();
        });

        // 下载强制转码（非MP3 → 320kbps）开关
        txtDownloadTransSummary = findViewById(R.id.txt_download_transcoding_summary);
        updateDownloadTranscodingSummary();
        findViewById(R.id.card_download_transcoding).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, false);
            sp.edit().putBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, !enabled).apply();
            updateDownloadTranscodingSummary();
        });

        updateCacheSummary();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCacheSummary();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
} 