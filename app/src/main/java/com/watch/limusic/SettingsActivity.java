package com.watch.limusic;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class SettingsActivity extends AppCompatActivity {

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

        // 仅绑定现有的三个入口卡片
        View serverEntry = findViewById(R.id.card_navidrome);
        if (serverEntry != null) {
            serverEntry.setOnClickListener(v -> startActivity(new Intent(this, NavidromeSettingsActivity.class)));
        }

        View generalEntry = findViewById(R.id.card_general_settings);
        if (generalEntry != null) {
            generalEntry.setOnClickListener(v -> startActivity(new Intent(this, GeneralSettingsActivity.class)));
        }

        View playerEntry = findViewById(R.id.card_player_settings);
        if (playerEntry != null) {
            playerEntry.setOnClickListener(v -> startActivity(new Intent(this, PlayerSettingsActivity.class)));
        }
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