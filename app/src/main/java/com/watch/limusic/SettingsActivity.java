package com.watch.limusic;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import com.watch.limusic.cache.CacheManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {
    private TextView txtCacheSummary;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
} 