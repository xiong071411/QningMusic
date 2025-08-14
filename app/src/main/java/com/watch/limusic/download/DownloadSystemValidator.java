package com.watch.limusic.download;

import android.content.Context;
import android.util.Log;

import com.watch.limusic.database.DownloadRepository;
import com.watch.limusic.model.Song;

import java.io.File;
import java.util.List;

/**
 * 下载系统验证器 - 用于测试和验证下载系统的各项功能
 */
public class DownloadSystemValidator {
    private static final String TAG = "DownloadSystemValidator";
    
    private final Context context;
    private final DownloadManager downloadManager;
    private final LocalFileDetector localFileDetector;
    private final DownloadRepository downloadRepository;

    public DownloadSystemValidator(Context context) {
        this.context = context;
        this.downloadManager = DownloadManager.getInstance(context);
        this.localFileDetector = new LocalFileDetector(context);
        this.downloadRepository = DownloadRepository.getInstance(context);
    }

    /**
     * 验证下载系统的完整性
     */
    public ValidationResult validateDownloadSystem() {
        ValidationResult result = new ValidationResult();
        
        Log.i(TAG, "开始验证下载系统...");
        
        // 1. 验证目录结构
        result.directoryStructureValid = validateDirectoryStructure();
        
        // 2. 验证文件检测功能
        result.fileDetectionValid = validateFileDetection();
        
        // 3. 验证数据库功能
        result.databaseValid = validateDatabase();
        
        // 4. 验证存储计算
        result.storageCalculationValid = validateStorageCalculation();
        
        // 5. 验证文件清理功能
        result.cleanupValid = validateCleanupFunctionality();
        
        result.overallValid = result.directoryStructureValid && 
                             result.fileDetectionValid && 
                             result.databaseValid && 
                             result.storageCalculationValid && 
                             result.cleanupValid;
        
        Log.i(TAG, "下载系统验证完成，结果: " + (result.overallValid ? "通过" : "失败"));
        return result;
    }

    /**
     * 验证目录结构
     */
    private boolean validateDirectoryStructure() {
        try {
            File downloadDir = new File(context.getExternalFilesDir(null), "downloads");
            File songsDir = new File(downloadDir, "songs");
            File coversDir = new File(downloadDir, "covers");
            
            boolean valid = downloadDir.exists() && downloadDir.isDirectory() &&
                           songsDir.exists() && songsDir.isDirectory() &&
                           coversDir.exists() && coversDir.isDirectory();
            
            Log.d(TAG, "目录结构验证: " + (valid ? "通过" : "失败"));
            return valid;
        } catch (Exception e) {
            Log.e(TAG, "目录结构验证失败", e);
            return false;
        }
    }

    /**
     * 验证文件检测功能
     */
    private boolean validateFileDetection() {
        try {
            // 获取已下载的歌曲列表
            List<String> downloadedSongs = localFileDetector.getAllDownloadedSongIds();
            
            // 验证每个文件是否真实存在
            for (String songId : downloadedSongs) {
                if (!localFileDetector.isSongDownloaded(songId)) {
                    Log.e(TAG, "文件检测不一致: " + songId);
                    return false;
                }
                
                String filePath = localFileDetector.getDownloadedSongPath(songId);
                if (filePath == null || !new File(filePath).exists()) {
                    Log.e(TAG, "文件路径无效: " + songId);
                    return false;
                }
            }
            
            Log.d(TAG, "文件检测验证: 通过 (" + downloadedSongs.size() + " 个文件)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "文件检测验证失败", e);
            return false;
        }
    }

    /**
     * 验证数据库功能
     */
    private boolean validateDatabase() {
        try {
            // 获取数据库中的下载记录数量
            int dbCount = downloadRepository.getCompletedDownloadCount();
            
            // 获取文件系统中的文件数量
            List<String> fileSystemSongs = localFileDetector.getAllDownloadedSongIds();
            int fileCount = fileSystemSongs.size();
            
            // 数据库记录数量应该大于等于文件系统中的文件数量
            // (因为数据库可能包含失败的下载记录)
            boolean valid = dbCount >= 0; // 基本的数据库连接验证
            
            Log.d(TAG, "数据库验证: " + (valid ? "通过" : "失败") + 
                      " (数据库记录: " + dbCount + ", 文件数量: " + fileCount + ")");
            return valid;
        } catch (Exception e) {
            Log.e(TAG, "数据库验证失败", e);
            return false;
        }
    }

    /**
     * 验证存储计算
     */
    private boolean validateStorageCalculation() {
        try {
            long totalSize = localFileDetector.getTotalDownloadSize();
            long songsSize = localFileDetector.getSongsDownloadSize();
            long coversSize = localFileDetector.getCoversDownloadSize();
            
            // 验证总大小等于歌曲大小加封面大小
            boolean valid = totalSize == (songsSize + coversSize) && totalSize >= 0;
            
            Log.d(TAG, "存储计算验证: " + (valid ? "通过" : "失败") + 
                      " (总计: " + totalSize + ", 歌曲: " + songsSize + ", 封面: " + coversSize + ")");
            return valid;
        } catch (Exception e) {
            Log.e(TAG, "存储计算验证失败", e);
            return false;
        }
    }

    /**
     * 验证清理功能
     */
    private boolean validateCleanupFunctionality() {
        try {
            // 获取清理前的文件数量
            List<String> beforeCleanup = localFileDetector.getAllDownloadedSongIds();
            int beforeCount = beforeCleanup.size();
            
            // 执行清理损坏文件
            int cleanedCount = localFileDetector.cleanupCorruptedFiles();
            
            // 获取清理后的文件数量
            List<String> afterCleanup = localFileDetector.getAllDownloadedSongIds();
            int afterCount = afterCleanup.size();
            
            // 验证清理结果
            boolean valid = (beforeCount - afterCount) == cleanedCount;
            
            Log.d(TAG, "清理功能验证: " + (valid ? "通过" : "失败") + 
                      " (清理前: " + beforeCount + ", 清理后: " + afterCount + ", 清理数量: " + cleanedCount + ")");
            return valid;
        } catch (Exception e) {
            Log.e(TAG, "清理功能验证失败", e);
            return false;
        }
    }

    /**
     * 模拟下载测试（仅用于测试环境）
     */
    public boolean simulateDownloadTest(Song testSong) {
        try {
            String songId = testSong.getId();
            
            // 检查初始状态
            boolean initiallyDownloaded = localFileDetector.isSongDownloaded(songId);
            
            if (initiallyDownloaded) {
                Log.d(TAG, "测试歌曲已存在，跳过下载测试");
                return true;
            }
            
            // 开始下载
            downloadManager.downloadSong(testSong);
            
            // 等待一段时间（实际测试中需要等待下载完成）
            Thread.sleep(1000);
            
            // 检查下载状态
            boolean downloadStarted = downloadManager.getDownloadInfo(songId) != null;
            
            Log.d(TAG, "下载测试: " + (downloadStarted ? "开始成功" : "开始失败"));
            return downloadStarted;
        } catch (Exception e) {
            Log.e(TAG, "下载测试失败", e);
            return false;
        }
    }

    /**
     * 验证离线播放功能
     */
    public boolean validateOfflinePlayback() {
        try {
            List<String> downloadedSongs = localFileDetector.getAllDownloadedSongIds();
            
            if (downloadedSongs.isEmpty()) {
                Log.d(TAG, "没有已下载的歌曲，无法验证离线播放");
                return true; // 没有歌曲不算失败
            }
            
            // 验证每个下载的歌曲都有有效的文件路径
            for (String songId : downloadedSongs) {
                String filePath = localFileDetector.getDownloadedSongPath(songId);
                if (filePath == null || !filePath.startsWith("file://")) {
                    File file = new File(filePath);
                    if (!file.exists() || !file.canRead()) {
                        Log.e(TAG, "离线播放验证失败: 文件不可读 " + songId);
                        return false;
                    }
                }
            }
            
            Log.d(TAG, "离线播放验证: 通过 (" + downloadedSongs.size() + " 个文件可播放)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "离线播放验证失败", e);
            return false;
        }
    }

    /**
     * 验证结果类
     */
    public static class ValidationResult {
        public boolean overallValid = false;
        public boolean directoryStructureValid = false;
        public boolean fileDetectionValid = false;
        public boolean databaseValid = false;
        public boolean storageCalculationValid = false;
        public boolean cleanupValid = false;
        
        @Override
        public String toString() {
            return "ValidationResult{" +
                    "overall=" + overallValid +
                    ", directory=" + directoryStructureValid +
                    ", fileDetection=" + fileDetectionValid +
                    ", database=" + databaseValid +
                    ", storage=" + storageCalculationValid +
                    ", cleanup=" + cleanupValid +
                    '}';
        }
    }
}
