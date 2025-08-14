package com.watch.limusic.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.watch.limusic.model.DownloadStatus;

import java.util.List;

/**
 * 下载数据访问对象
 */
@Dao
public interface DownloadDao {

    /**
     * 插入下载记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertDownload(DownloadEntity download);

    /**
     * 批量插入下载记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAllDownloads(List<DownloadEntity> downloads);

    /**
     * 更新下载记录
     */
    @Update
    void updateDownload(DownloadEntity download);

    /**
     * 删除下载记录
     */
    @Delete
    void deleteDownload(DownloadEntity download);

    /**
     * 根据歌曲ID删除下载记录
     */
    @Query("DELETE FROM downloads WHERE songId = :songId")
    void deleteDownloadById(String songId);

    /**
     * 根据歌曲ID获取下载记录
     */
    @Query("SELECT * FROM downloads WHERE songId = :songId")
    DownloadEntity getDownloadById(String songId);

    /**
     * 获取所有下载记录
     */
    @Query("SELECT * FROM downloads ORDER BY downloadTimestamp DESC")
    List<DownloadEntity> getAllDownloads();

    /**
     * 获取已完成的下载
     */
    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY completedTimestamp DESC")
    List<DownloadEntity> getDownloadsByStatus(DownloadStatus status);

    /**
     * 获取已下载的歌曲
     */
    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADED' ORDER BY completedTimestamp DESC")
    List<DownloadEntity> getCompletedDownloads();

    /**
     * 获取正在下载的歌曲
     */
    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING' ORDER BY downloadTimestamp DESC")
    List<DownloadEntity> getActiveDownloads();

    /**
     * 获取下载失败的歌曲
     */
    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOAD_FAILED' ORDER BY downloadTimestamp DESC")
    List<DownloadEntity> getFailedDownloads();

    /**
     * 更新下载状态
     */
    @Query("UPDATE downloads SET status = :status WHERE songId = :songId")
    void updateDownloadStatus(String songId, DownloadStatus status);

    /**
     * 更新下载进度信息
     */
    @Query("UPDATE downloads SET status = :status, filePath = :filePath, fileSize = :fileSize, completedTimestamp = :timestamp WHERE songId = :songId")
    void updateDownloadProgress(String songId, DownloadStatus status, String filePath, long fileSize, long timestamp);

    /**
     * 更新下载错误信息
     */
    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage, retryCount = retryCount + 1 WHERE songId = :songId")
    void updateDownloadError(String songId, DownloadStatus status, String errorMessage);

    /**
     * 获取下载统计信息
     */
    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'DOWNLOADED'")
    int getCompletedDownloadCount();

    /**
     * 获取总下载文件大小
     */
    @Query("SELECT SUM(fileSize) FROM downloads WHERE status = 'DOWNLOADED'")
    long getTotalDownloadSize();

    /**
     * 根据专辑ID获取下载记录
     */
    @Query("SELECT * FROM downloads WHERE albumId = :albumId ORDER BY downloadTimestamp DESC")
    List<DownloadEntity> getDownloadsByAlbum(String albumId);

    /**
     * 根据艺术家获取下载记录
     */
    @Query("SELECT * FROM downloads WHERE artist = :artist ORDER BY downloadTimestamp DESC")
    List<DownloadEntity> getDownloadsByArtist(String artist);

    /**
     * 清理所有下载记录
     */
    @Query("DELETE FROM downloads")
    void clearAllDownloads();

    /**
     * 清理失败的下载记录
     */
    @Query("DELETE FROM downloads WHERE status = 'DOWNLOAD_FAILED'")
    void clearFailedDownloads();

    /**
     * 获取需要重试的下载（失败且重试次数未超限）
     */
    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOAD_FAILED' AND retryCount < 3 ORDER BY downloadTimestamp ASC")
    List<DownloadEntity> getRetryableDownloads();

    /**
     * 检查歌曲是否已下载
     */
    @Query("SELECT COUNT(*) > 0 FROM downloads WHERE songId = :songId AND status = 'DOWNLOADED'")
    boolean isDownloaded(String songId);

    /**
     * 搜索下载记录
     */
    @Query("SELECT * FROM downloads WHERE (title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%') ORDER BY downloadTimestamp DESC")
    List<DownloadEntity> searchDownloads(String query);
}
