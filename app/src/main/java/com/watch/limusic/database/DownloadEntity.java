package com.watch.limusic.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.watch.limusic.model.DownloadStatus;

/**
 * 下载记录实体类
 */
@Entity(tableName = "downloads")
public class DownloadEntity {
    @PrimaryKey
    @NonNull
    private String songId;
    
    private String title;
    private String artist;
    private String album;
    private String albumId;
    private String filePath;
    private long fileSize;
    private DownloadStatus status;
    private long downloadTimestamp;
    private long completedTimestamp;
    private String errorMessage;
    private int retryCount;
    private String streamUrl;
    private String coverArtUrl;

    public DownloadEntity() {
        this.status = DownloadStatus.NOT_DOWNLOADED;
        this.downloadTimestamp = System.currentTimeMillis();
        this.retryCount = 0;
    }

    @NonNull
    public String getSongId() {
        return songId;
    }

    public void setSongId(@NonNull String songId) {
        this.songId = songId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getAlbumId() {
        return albumId;
    }

    public void setAlbumId(String albumId) {
        this.albumId = albumId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public DownloadStatus getStatus() {
        return status;
    }

    public void setStatus(DownloadStatus status) {
        this.status = status;
    }

    public long getDownloadTimestamp() {
        return downloadTimestamp;
    }

    public void setDownloadTimestamp(long downloadTimestamp) {
        this.downloadTimestamp = downloadTimestamp;
    }

    public long getCompletedTimestamp() {
        return completedTimestamp;
    }

    public void setCompletedTimestamp(long completedTimestamp) {
        this.completedTimestamp = completedTimestamp;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl;
    }

    public String getCoverArtUrl() {
        return coverArtUrl;
    }

    public void setCoverArtUrl(String coverArtUrl) {
        this.coverArtUrl = coverArtUrl;
    }

    /**
     * 检查是否下载完成
     */
    public boolean isDownloaded() {
        return status == DownloadStatus.DOWNLOADED;
    }

    /**
     * 检查是否正在下载
     */
    public boolean isDownloading() {
        return status == DownloadStatus.DOWNLOADING;
    }

    /**
     * 检查是否下载失败
     */
    public boolean isFailed() {
        return status == DownloadStatus.DOWNLOAD_FAILED;
    }

    /**
     * 增加重试次数
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }

    /**
     * 重置重试次数
     */
    public void resetRetryCount() {
        this.retryCount = 0;
    }

    /**
     * 标记下载完成
     */
    public void markCompleted(String filePath, long fileSize) {
        this.status = DownloadStatus.DOWNLOADED;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.completedTimestamp = System.currentTimeMillis();
        this.errorMessage = null;
        this.resetRetryCount();
    }

    /**
     * 标记下载失败
     */
    public void markFailed(String errorMessage) {
        this.status = DownloadStatus.DOWNLOAD_FAILED;
        this.errorMessage = errorMessage;
        this.incrementRetryCount();
    }

    /**
     * 开始下载
     */
    public void startDownload() {
        this.status = DownloadStatus.DOWNLOADING;
        this.downloadTimestamp = System.currentTimeMillis();
        this.errorMessage = null;
    }
}
