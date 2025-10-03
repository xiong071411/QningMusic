package com.watch.limusic;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
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

    private SwitchCompat swTranscoding;
    private SwitchCompat swDownloadTranscoding;
    private SwitchCompat swLowPower;

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

        // 绑定 Switch
        try { swTranscoding = findViewById(R.id.switch_transcoding); } catch (Exception ignore) {}
        try { swDownloadTranscoding = findViewById(R.id.switch_download_transcoding); } catch (Exception ignore) {}
        try { swLowPower = findViewById(R.id.switch_low_power); } catch (Exception ignore) {}

        // 初始化 Switch 状态
        SharedPreferences spInit = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (swTranscoding != null) swTranscoding.setChecked(spInit.getBoolean(KEY_FORCE_TRANSCODE, false));
        if (swDownloadTranscoding != null) swDownloadTranscoding.setChecked(spInit.getBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, false));
        if (swLowPower != null) swLowPower.setChecked(spInit.getBoolean(KEY_LOW_POWER_MODE, false));

        // Switch 监听 → 写入偏好并更新摘要
        if (swTranscoding != null) swTranscoding.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_FORCE_TRANSCODE, isChecked).apply();
            updateTranscodingSummary();
        });
        if (swDownloadTranscoding != null) swDownloadTranscoding.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_FORCE_DOWNLOAD_TRANSCODE, isChecked).apply();
            updateDownloadTranscodingSummary();
        });
        if (swLowPower != null) swLowPower.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_LOW_POWER_MODE, isChecked).apply();
            updateLowPowerSummary();
            Toast.makeText(this, isChecked ? "已开启低耗模式" : "已关闭低耗模式", Toast.LENGTH_SHORT).show();
            // 通知主界面刷新
            try { Intent i = new Intent("com.watch.limusic.UI_SETTINGS_CHANGED"); i.putExtra("what","low_power"); sendBroadcast(i);} catch (Exception ignore) {}
            try { sendBroadcast(new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED")); } catch (Exception ignore) {}
        });

        // 卡片点击 → 触发 Switch 点击（扩大可点击区域）
        findViewById(R.id.card_transcoding).setOnClickListener(v -> { if (swTranscoding != null) swTranscoding.performClick(); });
        findViewById(R.id.card_download_transcoding).setOnClickListener(v -> { if (swDownloadTranscoding != null) swDownloadTranscoding.performClick(); });
        findViewById(R.id.card_low_power).setOnClickListener(v -> { if (swLowPower != null) swLowPower.performClick(); });
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

        // 听歌记录服务器设置入口
        View listenReport = findViewById(R.id.card_listen_report_settings);
        if (listenReport != null) listenReport.setOnClickListener(v -> startActivity(new Intent(this, ListenReportSettingsActivity.class)));

        // 测试日志记录（仅测试版有效；release为占位实现）
        View cardLog = findViewByIdName("card_test_log_recorder");
        if (cardLog != null) {
            if (!isDebuggable()) { cardLog.setVisibility(View.GONE); }
            TextView status = findViewByIdNameAsText("txt_logrecorder_status");
            View btnStart = findViewByIdName("btn_log_start");
            View btnStop = findViewByIdName("btn_log_stop");
            if (status != null) status.setText(com.watch.limusic.devtools.LogRecorder.isRecording() ? "正在记录…" : "未在记录");
            if (btnStart != null) btnStart.setOnClickListener(v -> {
                boolean ok = com.watch.limusic.devtools.LogRecorder.start(this);
                Toast.makeText(this, ok ? "开始记录日志" : "无法开始（可能已在记录或不支持）", Toast.LENGTH_SHORT).show();
                if (status != null) status.setText(com.watch.limusic.devtools.LogRecorder.isRecording() ? "正在记录…" : "未在记录");
            });
            if (btnStop != null) btnStop.setOnClickListener(v -> {
                java.io.File f = com.watch.limusic.devtools.LogRecorder.stop(this);
                if (f != null) {
                    Toast.makeText(this, "日志已保存：" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "未在记录或保存失败", Toast.LENGTH_SHORT).show();
                }
                if (status != null) status.setText(com.watch.limusic.devtools.LogRecorder.isRecording() ? "正在记录…" : "未在记录");
            });
        }
    }

    private View findViewByIdName(String idName) {
        int id = getResources().getIdentifier(idName, "id", getPackageName());
        return id == 0 ? null : findViewById(id);
    }

    private TextView findViewByIdNameAsText(String idName) {
        View v = findViewByIdName(idName);
        return (v instanceof TextView) ? (TextView) v : null;
    }

    private boolean isDebuggable() {
        try {
            int flags = getApplicationInfo().flags;
            return (flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable ignore) { return false; }
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