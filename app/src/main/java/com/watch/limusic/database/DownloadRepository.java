package com.watch.limusic.database;

import android.content.Context;
import android.util.Log;

import com.watch.limusic.model.DownloadInfo;
import com.watch.limusic.model.DownloadStatus;
import com.watch.limusic.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 下载数据存储库
 */
public class DownloadRepository {
    private static final String TAG = "DownloadRepository";
    private static DownloadRepository INSTANCE;
    
    private final MusicDatabase database;
    private final DownloadDao downloadDao;
    private final ExecutorService executorService;

    private DownloadRepository(Context context) {
        this.database = MusicDatabase.getInstance(context);
        this.downloadDao = database.downloadDao();
        this.executorService = Executors.newFixedThreadPool(2);
    }

    public static synchronized DownloadRepository getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new DownloadRepository(context.getApplicationContext());
        }
        return INSTANCE;
    }

    /**
     * 添加下载记录
     */
    public void addDownload(Song song) {
        executorService.execute(() -> {
            try {
                DownloadEntity entity = new DownloadEntity();
                entity.setSongId(song.getId());
                entity.setTitle(song.getTitle());
                entity.setArtist(song.getArtist());
                entity.setAlbum(song.getAlbum());
                entity.setAlbumId(song.getAlbumId());
                entity.setCoverArtUrl(song.getCoverArtUrl());
                entity.setStatus(DownloadStatus.NOT_DOWNLOADED);
                
                downloadDao.insertDownload(entity);
                Log.d(TAG, "添加下载记录: " + song.getTitle());
            } catch (Exception e) {
                Log.e(TAG, "添加下载记录失败", e);
            }
        });
    }

    /**
     * 更新下载状态
     */
    public void updateDownloadStatus(String songId, DownloadStatus status) {
        executorService.execute(() -> {
            try {
                downloadDao.updateDownloadStatus(songId, status);
                Log.d(TAG, "更新下载状态: " + songId + " -> " + status);
            } catch (Exception e) {
                Log.e(TAG, "更新下载状态失败", e);
            }
        });
    }

    /**
     * 标记下载完成
     */
    public void markDownloadCompleted(String songId, String filePath, long fileSize) {
        executorService.execute(() -> {
            try {
                long timestamp = System.currentTimeMillis();
                downloadDao.updateDownloadProgress(songId, DownloadStatus.DOWNLOADED, 
                        filePath, fileSize, timestamp);
                Log.d(TAG, "标记下载完成: " + songId);
            } catch (Exception e) {
                Log.e(TAG, "标记下载完成失败", e);
            }
        });
    }

    /**
     * 标记下载失败
     */
    public void markDownloadFailed(String songId, String errorMessage) {
        executorService.execute(() -> {
            try {
                downloadDao.updateDownloadError(songId, DownloadStatus.DOWNLOAD_FAILED, errorMessage);
                Log.d(TAG, "标记下载失败: " + songId + " - " + errorMessage);
            } catch (Exception e) {
                Log.e(TAG, "标记下载失败失败", e);
            }
        });
    }

    /**
     * 删除下载记录
     */
    public void deleteDownload(String songId) {
        executorService.execute(() -> {
            try {
                downloadDao.deleteDownloadById(songId);
                Log.d(TAG, "删除下载记录: " + songId);
            } catch (Exception e) {
                Log.e(TAG, "删除下载记录失败", e);
            }
        });
    }

    /**
     * 获取下载记录
     */
    public DownloadEntity getDownload(String songId) {
        try {
            return downloadDao.getDownloadById(songId);
        } catch (Exception e) {
            Log.e(TAG, "获取下载记录失败", e);
            return null;
        }
    }

    /**
     * 获取所有已完成的下载
     */
    public List<DownloadEntity> getCompletedDownloads() {
        try {
            return downloadDao.getCompletedDownloads();
        } catch (Exception e) {
            Log.e(TAG, "获取已完成下载失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取正在下载的项目
     */
    public List<DownloadEntity> getActiveDownloads() {
        try {
            return downloadDao.getActiveDownloads();
        } catch (Exception e) {
            Log.e(TAG, "获取正在下载项目失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取下载失败的项目
     */
    public List<DownloadEntity> getFailedDownloads() {
        try {
            return downloadDao.getFailedDownloads();
        } catch (Exception e) {
            Log.e(TAG, "获取下载失败项目失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 检查歌曲是否已下载
     */
    public boolean isDownloaded(String songId) {
        try {
            return downloadDao.isDownloaded(songId);
        } catch (Exception e) {
            Log.e(TAG, "检查下载状态失败", e);
            return false;
        }
    }

    /**
     * 获取下载统计信息
     */
    public int getCompletedDownloadCount() {
        try {
            return downloadDao.getCompletedDownloadCount();
        } catch (Exception e) {
            Log.e(TAG, "获取下载统计失败", e);
            return 0;
        }
    }

    /**
     * 获取总下载大小
     */
    public long getTotalDownloadSize() {
        try {
            return downloadDao.getTotalDownloadSize();
        } catch (Exception e) {
            Log.e(TAG, "获取总下载大小失败", e);
            return 0;
        }
    }

    /**
     * 清理所有下载记录
     */
    public void clearAllDownloads() {
        executorService.execute(() -> {
            try {
                downloadDao.clearAllDownloads();
                Log.d(TAG, "清理所有下载记录");
            } catch (Exception e) {
                Log.e(TAG, "清理下载记录失败", e);
            }
        });
    }

    /**
     * 清理失败的下载记录
     */
    public void clearFailedDownloads() {
        executorService.execute(() -> {
            try {
                downloadDao.clearFailedDownloads();
                Log.d(TAG, "清理失败的下载记录");
            } catch (Exception e) {
                Log.e(TAG, "清理失败下载记录失败", e);
            }
        });
    }

    /**
     * 搜索下载记录
     */
    public List<DownloadEntity> searchDownloads(String query) {
        try {
            return downloadDao.searchDownloads(query);
        } catch (Exception e) {
            Log.e(TAG, "搜索下载记录失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 将DownloadEntity转换为DownloadInfo
     */
    public static DownloadInfo toDownloadInfo(DownloadEntity entity) {
        if (entity == null) return null;
        
        DownloadInfo info = new DownloadInfo();
        info.setSongId(entity.getSongId());
        info.setTitle(entity.getTitle());
        info.setArtist(entity.getArtist());
        info.setAlbum(entity.getAlbum());
        info.setAlbumId(entity.getAlbumId());
        info.setStatus(entity.getStatus());
        info.setFilePath(entity.getFilePath());
        info.setDownloadTimestamp(entity.getDownloadTimestamp());
        info.setErrorMessage(entity.getErrorMessage());
        info.setRetryCount(entity.getRetryCount());
        
        return info;
    }
}
