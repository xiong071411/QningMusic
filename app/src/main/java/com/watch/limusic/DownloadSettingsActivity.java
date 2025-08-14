package com.watch.limusic;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.watch.limusic.download.DownloadManager;
import com.watch.limusic.download.LocalFileDetector;

import java.text.DecimalFormat;
import java.util.List;

/**
 * 下载管理设置界面
 */
public class DownloadSettingsActivity extends AppCompatActivity {
    private static final String TAG = "DownloadSettingsActivity";
    
    private TextView txtStorageUsage;
    private TextView txtDownloadLocation;
    private TextView txtDownloadedCount;
    private Button btnClearDownloads;
    private Button btnCleanupCorrupted;
    
    private DownloadManager downloadManager;
    private LocalFileDetector localFileDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LiMusic);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("下载管理");
        }

        // 初始化组件
        initViews();
        initManagers();
        updateStorageInfo();
        setupClickListeners();
    }

    private void initViews() {
        txtStorageUsage = findViewById(R.id.txt_storage_usage);
        txtDownloadLocation = findViewById(R.id.txt_download_location);
        txtDownloadedCount = findViewById(R.id.txt_downloaded_count);
        btnClearDownloads = findViewById(R.id.btn_clear_downloads);
        btnCleanupCorrupted = findViewById(R.id.btn_cleanup_corrupted);
    }

    private void initManagers() {
        downloadManager = DownloadManager.getInstance(this);
        localFileDetector = new LocalFileDetector(this);
    }

    private void updateStorageInfo() {
        // 获取存储使用情况
        long totalSize = localFileDetector.getTotalDownloadSize();
        long songsSize = localFileDetector.getSongsDownloadSize();
        long coversSize = localFileDetector.getCoversDownloadSize();
        
        // 获取已下载歌曲数量
        List<String> downloadedSongs = localFileDetector.getAllDownloadedSongIds();
        int downloadedCount = downloadedSongs.size();
        
        // 格式化大小显示
        String totalSizeStr = formatFileSize(totalSize);
        String songsSizeStr = formatFileSize(songsSize);
        String coversSizeStr = formatFileSize(coversSize);
        
        // 更新UI
        txtStorageUsage.setText(String.format("总计: %s\n歌曲: %s\n封面: %s", 
                totalSizeStr, songsSizeStr, coversSizeStr));
        txtDownloadedCount.setText(String.format("%d 首歌曲", downloadedCount));
        txtDownloadLocation.setText(localFileDetector.getDownloadDirectoryPath());
    }

    private void setupClickListeners() {
        btnClearDownloads.setOnClickListener(v -> showClearDownloadsDialog());
        btnCleanupCorrupted.setOnClickListener(v -> cleanupCorruptedFiles());
    }

    private void showClearDownloadsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("清理下载")
                .setMessage("确定要删除所有已下载的歌曲吗？此操作不可撤销。")
                .setPositiveButton("确定", (dialog, which) -> clearAllDownloads())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearAllDownloads() {
        new Thread(() -> {
            try {
                List<String> downloadedSongs = localFileDetector.getAllDownloadedSongIds();
                int deletedCount = 0;
                
                for (String songId : downloadedSongs) {
                    if (downloadManager.deleteDownload(songId)) {
                        deletedCount++;
                    }
                }
                
                final int finalDeletedCount = deletedCount;
                runOnUiThread(() -> {
                    Toast.makeText(this, "已删除 " + finalDeletedCount + " 首歌曲", 
                            Toast.LENGTH_SHORT).show();
                    updateStorageInfo();
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "清理失败: " + e.getMessage(), 
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void cleanupCorruptedFiles() {
        new Thread(() -> {
            try {
                int cleanedCount = localFileDetector.cleanupCorruptedFiles();
                
                runOnUiThread(() -> {
                    if (cleanedCount > 0) {
                        Toast.makeText(this, "清理了 " + cleanedCount + " 个损坏文件", 
                                Toast.LENGTH_SHORT).show();
                        updateStorageInfo();
                    } else {
                        Toast.makeText(this, "没有发现损坏的文件", 
                                Toast.LENGTH_SHORT).show();
                    }
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "清理失败: " + e.getMessage(), 
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        
        DecimalFormat df = new DecimalFormat("#,##0.#");
        return df.format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStorageInfo();
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
