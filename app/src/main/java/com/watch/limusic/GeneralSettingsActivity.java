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
import java.util.concurrent.atomic.AtomicBoolean;

public class GeneralSettingsActivity extends AppCompatActivity {
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
    private final AtomicBoolean clearing = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_general_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        txtCacheSummary = findViewById(R.id.txt_cache_summary);
        txtTransSummary = findViewById(R.id.txt_transcoding_summary);
        txtDownloadTransSummary = findViewById(R.id.txt_download_transcoding_summary);
        txtLowPowerSummary = findViewById(R.id.txt_low_power_summary);
        txtProgressSummary = findViewById(R.id.txt_progress_summary);

        // 缓存清理（防抖 + 串行）
        findViewById(R.id.card_clear_cache).setOnClickListener(v -> triggerClearAllCache());
        // 缓存详情
        findViewById(R.id.card_cache_settings).setOnClickListener(v -> startActivity(new Intent(this, CacheSettingsActivity.class)));

        // 解码兼容模式
        findViewById(R.id.card_transcoding).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_FORCE_TRANSCODE, false);
            sp.edit().putBoolean(KEY_FORCE_TRANSCODE, !enabled).apply();
            updateTranscodingSummary();
        });
        // 下载强制转码
        findViewById(R.id.card_download_transcoding).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, false);
            sp.edit().putBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, !enabled).apply();
            updateDownloadTranscodingSummary();
        });
        // 省电模式
        findViewById(R.id.card_low_power).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_LOW_POWER_MODE, false);
            sp.edit().putBoolean(KEY_LOW_POWER_MODE, !enabled).apply();
            updateLowPowerSummary();
            Toast.makeText(this, !enabled ? "已开启低耗模式" : "已关闭低耗模式", Toast.LENGTH_SHORT).show();
            // 通知主界面刷新
            try { Intent i = new Intent("com.watch.limusic.UI_SETTINGS_CHANGED"); i.putExtra("what","low_power"); sendBroadcast(i);} catch (Exception ignore) {}
            try { sendBroadcast(new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED")); } catch (Exception ignore) {}
        });
        // 进度刷新频率
        findViewById(R.id.card_progress_broadcast).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            int mode = sp.getInt(KEY_PROGRESS_MODE, 0);
            int next = (mode + 1) % 3;
            sp.edit().putInt(KEY_PROGRESS_MODE, next).apply();
            updateProgressSummary();
        });

        // 新增：清理歌词缓存
        View cardClearLyrics = findViewByIdName("card_clear_lyrics_cache");
        if (cardClearLyrics != null) cardClearLyrics.setOnClickListener(v -> triggerClearLyricsCache());

        updateCacheSummary();
        updateTranscodingSummary();
        updateDownloadTranscodingSummary();
        updateLowPowerSummary();
        updateProgressSummary();
    }

    private View findViewByIdName(String idName) {
        int id = getResources().getIdentifier(idName, "id", getPackageName());
        return id == 0 ? null : findViewById(id);
    }

    private void triggerClearAllCache() {
        if (!clearing.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                CacheManager.clearCache(this);
                runOnUiThread(() -> {
                    Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show();
                    updateCacheSummary();
                });
            } finally {
                clearing.set(false);
            }
        });
    }

    private void triggerClearLyricsCache() {
        if (!clearing.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                // 歌词缓存目录：externalFiles/downloads/lyrics
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "downloads/lyrics");
                deleteRecursively(dir);
                runOnUiThread(() -> Toast.makeText(this, "歌词缓存已清理", Toast.LENGTH_SHORT).show());
            } finally {
                clearing.set(false);
            }
        });
    }

    private void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] list = f.listFiles();
            if (list != null) for (java.io.File c : list) deleteRecursively(c);
        }
        try { f.delete(); } catch (Exception ignore) {}
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