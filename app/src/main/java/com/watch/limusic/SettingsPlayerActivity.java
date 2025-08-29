package com.watch.limusic;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class SettingsPlayerActivity extends AppCompatActivity {
    private static final String PREFS = "player_prefs";
    private static final String KEY_BG_BLUR_ENABLED = "bg_blur_enabled";
    private static final String KEY_BG_BLUR_INTENSITY = "bg_blur_intensity";

    private TextView txtBgBlurSummary;
    private TextView txtBgBlurIntensityValue;
    private SeekBar seekBgBlurIntensity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_player);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        txtBgBlurSummary = findViewById(R.id.txt_bg_blur_summary);
        txtBgBlurIntensityValue = findViewById(R.id.txt_bg_blur_intensity_value);
        seekBgBlurIntensity = findViewById(R.id.seek_bg_blur_intensity);

        findViewById(R.id.card_bg_blur).setOnClickListener(v -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_BG_BLUR_ENABLED, true);
            sp.edit().putBoolean(KEY_BG_BLUR_ENABLED, !enabled).apply();
            updateBgBlurSummary();
            // 通知主界面
            sendBroadcast(new Intent("com.watch.limusic.UI_SETTINGS_CHANGED").putExtra("what", "bg_blur_enabled"));
            sendBroadcast(new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED"));
        });

        if (seekBgBlurIntensity != null) {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            seekBgBlurIntensity.setMax(100);
            seekBgBlurIntensity.setProgress(sp.getInt(KEY_BG_BLUR_INTENSITY, 50));
            seekBgBlurIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    txtBgBlurIntensityValue.setText(progress + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    sp.edit().putInt(KEY_BG_BLUR_INTENSITY, seekBar.getProgress()).apply();
                    sendBroadcast(new Intent("com.watch.limusic.UI_SETTINGS_CHANGED").putExtra("what", "bg_blur_intensity"));
                    sendBroadcast(new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED"));
                }
            });
        }

        updateBgBlurSummary();
        updateBgBlurIntensitySummary();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void updateBgBlurSummary() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = sp.getBoolean(KEY_BG_BLUR_ENABLED, true);
        txtBgBlurSummary.setText(enabled ? "已开启" : "关闭");
    }

    private void updateBgBlurIntensitySummary() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int v = sp.getInt(KEY_BG_BLUR_INTENSITY, 50);
        txtBgBlurIntensityValue.setText(v + "%");
    }
} 