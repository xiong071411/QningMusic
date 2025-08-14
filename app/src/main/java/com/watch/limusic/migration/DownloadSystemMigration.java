package com.watch.limusic.migration;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.watch.limusic.cache.CacheManager;
import com.watch.limusic.database.CacheDetector;
import com.watch.limusic.database.DownloadRepository;
import com.watch.limusic.database.MusicRepository;
import com.watch.limusic.database.SongEntity;
import com.watch.limusic.download.LocalFileDetector;
import com.watch.limusic.model.Song;

import java.util.List;

/**
 * 下载系统迁移工具
 * 确保新下载系统与现有缓存系统的兼容性
 */
public class DownloadSystemMigration {
    private static final String TAG = "DownloadSystemMigration";
    private static final String PREFS_NAME = "download_migration";
    private static final String KEY_MIGRATION_COMPLETED = "migration_completed";
    private static final String KEY_MIGRATION_VERSION = "migration_version";
    private static final int CURRENT_MIGRATION_VERSION = 1;
    
    private final Context context;
    private final SharedPreferences prefs;
    private final MusicRepository musicRepository;
    private final DownloadRepository downloadRepository;
    private final CacheDetector cacheDetector;
    private final LocalFileDetector localFileDetector;

    public DownloadSystemMigration(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.musicRepository = MusicRepository.getInstance(context);
        this.downloadRepository = DownloadRepository.getInstance(context);
        this.cacheDetector = new CacheDetector(context);
        this.localFileDetector = new LocalFileDetector(context);
    }

    /**
     * 检查是否需要执行迁移
     */
    public boolean needsMigration() {
        int currentVersion = prefs.getInt(KEY_MIGRATION_VERSION, 0);
        boolean migrationCompleted = prefs.getBoolean(KEY_MIGRATION_COMPLETED, false);
        
        return !migrationCompleted || currentVersion < CURRENT_MIGRATION_VERSION;
    }

    /**
     * 执行迁移过程
     */
    public MigrationResult performMigration() {
        Log.i(TAG, "开始执行下载系统迁移...");
        
        MigrationResult result = new MigrationResult();
        
        try {
            // 1. 迁移缓存状态到下载系统
            result.cacheStatusMigrated = migrateCacheStatus();
            
            // 2. 同步数据库状态
            result.databaseSynced = syncDatabaseStatus();
            
            // 3. 验证迁移结果
            result.validationPassed = validateMigration();
            
            // 4. 清理旧数据（可选）
            result.cleanupCompleted = performCleanup();
            
            result.overallSuccess = result.cacheStatusMigrated && 
                                   result.databaseSynced && 
                                   result.validationPassed;
            
            if (result.overallSuccess) {
                // 标记迁移完成
                prefs.edit()
                     .putBoolean(KEY_MIGRATION_COMPLETED, true)
                     .putInt(KEY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION)
                     .apply();
                
                Log.i(TAG, "下载系统迁移完成");
            } else {
                Log.e(TAG, "下载系统迁移失败: " + result);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "迁移过程中发生错误", e);
            result.error = e.getMessage();
        }
        
        return result;
    }

    /**
     * 迁移缓存状态到下载系统
     */
    private boolean migrateCacheStatus() {
        try {
            Log.d(TAG, "开始迁移缓存状态...");
            
            // 获取所有歌曲
            List<Song> allSongs = musicRepository.getAllSongsFromDatabase();
            int migratedCount = 0;
            
            for (Song song : allSongs) {
                try {
                    String songId = song.getId();
                    
                    // 检查是否已在新下载系统中
                    boolean isDownloaded = localFileDetector.isSongDownloaded(songId);
                    
                    if (!isDownloaded) {
                        // 检查是否在旧缓存系统中
                        boolean isCached = cacheDetector.isSongCached(songId);
                        
                        if (isCached) {
                            // 这里可以选择将缓存的歌曲标记为"已缓存但未下载"
                            // 或者提示用户重新下载
                            Log.d(TAG, "发现已缓存但未下载的歌曲: " + song.getTitle());
                            migratedCount++;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "迁移单首歌曲失败: " + song.getTitle(), e);
                }
            }
            
            Log.d(TAG, "缓存状态迁移完成，处理了 " + migratedCount + " 首歌曲");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "缓存状态迁移失败", e);
            return false;
        }
    }

    /**
     * 同步数据库状态
     */
    private boolean syncDatabaseStatus() {
        try {
            Log.d(TAG, "开始同步数据库状态...");
            
            // 获取所有已下载的歌曲
            List<String> downloadedSongs = localFileDetector.getAllDownloadedSongIds();
            
            for (String songId : downloadedSongs) {
                try {
                    // 确保数据库中有对应的下载记录
                    if (downloadRepository.getDownload(songId) == null) {
                        // 如果数据库中没有记录，但文件存在，创建记录
                        // 由于没有getSongById方法，我们从所有歌曲中查找
                        List<Song> allSongs = musicRepository.getAllSongsFromDatabase();
                        Song foundSong = null;
                        for (Song song : allSongs) {
                            if (song.getId().equals(songId)) {
                                foundSong = song;
                                break;
                            }
                        }

                        if (foundSong != null) {
                            downloadRepository.addDownload(foundSong);

                            String filePath = localFileDetector.getDownloadedSongPath(songId);
                            long fileSize = localFileDetector.getDownloadedSongSize(songId);
                            downloadRepository.markDownloadCompleted(songId, filePath, fileSize);

                            Log.d(TAG, "为现有下载文件创建数据库记录: " + foundSong.getTitle());
                        } else {
                            Log.w(TAG, "无法找到歌曲信息: " + songId);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "同步单首歌曲状态失败: " + songId, e);
                }
            }
            
            Log.d(TAG, "数据库状态同步完成");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "数据库状态同步失败", e);
            return false;
        }
    }

    /**
     * 验证迁移结果
     */
    private boolean validateMigration() {
        try {
            Log.d(TAG, "开始验证迁移结果...");
            
            // 检查文件系统和数据库的一致性
            List<String> downloadedSongs = localFileDetector.getAllDownloadedSongIds();
            int dbCount = downloadRepository.getCompletedDownloadCount();
            
            // 基本的一致性检查
            boolean consistent = dbCount >= downloadedSongs.size();
            
            Log.d(TAG, "迁移验证: " + (consistent ? "通过" : "失败") + 
                      " (文件: " + downloadedSongs.size() + ", 数据库: " + dbCount + ")");
            
            return consistent;
            
        } catch (Exception e) {
            Log.e(TAG, "迁移验证失败", e);
            return false;
        }
    }

    /**
     * 执行清理操作（可选）
     */
    private boolean performCleanup() {
        try {
            Log.d(TAG, "开始执行清理操作...");
            
            // 清理损坏的下载文件
            int cleanedFiles = localFileDetector.cleanupCorruptedFiles();
            
            // 清理失败的下载记录
            downloadRepository.clearFailedDownloads();
            
            Log.d(TAG, "清理操作完成，清理了 " + cleanedFiles + " 个损坏文件");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "清理操作失败", e);
            return false;
        }
    }

    /**
     * 获取兼容性状态
     */
    public CompatibilityStatus getCompatibilityStatus() {
        CompatibilityStatus status = new CompatibilityStatus();
        
        try {
            // 检查缓存系统状态
            status.cacheSystemAvailable = CacheManager.getInstance(context) != null;
            
            // 检查下载系统状态
            status.downloadSystemAvailable = localFileDetector != null;
            
            // 检查数据库状态
            status.databaseAvailable = downloadRepository != null;
            
            // 检查迁移状态
            status.migrationCompleted = !needsMigration();
            
            status.overallCompatible = status.cacheSystemAvailable && 
                                     status.downloadSystemAvailable && 
                                     status.databaseAvailable;
            
        } catch (Exception e) {
            Log.e(TAG, "获取兼容性状态失败", e);
            status.error = e.getMessage();
        }
        
        return status;
    }

    /**
     * 迁移结果类
     */
    public static class MigrationResult {
        public boolean overallSuccess = false;
        public boolean cacheStatusMigrated = false;
        public boolean databaseSynced = false;
        public boolean validationPassed = false;
        public boolean cleanupCompleted = false;
        public String error = null;
        
        @Override
        public String toString() {
            return "MigrationResult{" +
                    "success=" + overallSuccess +
                    ", cache=" + cacheStatusMigrated +
                    ", database=" + databaseSynced +
                    ", validation=" + validationPassed +
                    ", cleanup=" + cleanupCompleted +
                    ", error='" + error + '\'' +
                    '}';
        }
    }

    /**
     * 兼容性状态类
     */
    public static class CompatibilityStatus {
        public boolean overallCompatible = false;
        public boolean cacheSystemAvailable = false;
        public boolean downloadSystemAvailable = false;
        public boolean databaseAvailable = false;
        public boolean migrationCompleted = false;
        public String error = null;
        
        @Override
        public String toString() {
            return "CompatibilityStatus{" +
                    "compatible=" + overallCompatible +
                    ", cache=" + cacheSystemAvailable +
                    ", download=" + downloadSystemAvailable +
                    ", database=" + databaseAvailable +
                    ", migrated=" + migrationCompleted +
                    ", error='" + error + '\'' +
                    '}';
        }
    }
}
