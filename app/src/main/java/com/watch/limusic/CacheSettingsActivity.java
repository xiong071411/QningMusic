package com.watch.limusic;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.watch.limusic.cache.CacheManager;

public class CacheSettingsActivity extends AppCompatActivity {
    private EditText editSize;
    private TextView txtCurrent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("缓存设置");
        }

        editSize = findViewById(R.id.edit_cache_size);
        txtCurrent = findViewById(R.id.txt_current_size);
        long currentMb = CacheManager.getMaxCacheBytes(this) / (1024 * 1024);
        editSize.setText(String.valueOf(currentMb));
        txtCurrent.setText("当前最大缓存: " + currentMb + " MB");

        Button btnSave = findViewById(R.id.btn_save_cache_size);
        btnSave.setOnClickListener(v -> {
            try {
                long mb = Long.parseLong(editSize.getText().toString().trim());
                if (mb < 20) {
                    Toast.makeText(this, "最小20MB", Toast.LENGTH_SHORT).show();
                    return;
                }
                CacheManager.setMaxCacheMb(this, mb);
                Toast.makeText(this, "已保存，重启应用后生效", Toast.LENGTH_SHORT).show();
                txtCurrent.setText("当前最大缓存: " + mb + " MB");
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
            }
        });

        Button btnClear = findViewById(R.id.btn_clear_cache);
        btnClear.setOnClickListener(v -> {
            CacheManager.clearCache(this);
            Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show();
        });
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