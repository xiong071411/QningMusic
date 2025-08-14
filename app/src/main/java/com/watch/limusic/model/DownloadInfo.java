package com.watch.limusic.model;

/**
 * 下载信息模型
 */
public class DownloadInfo {
    private String songId;
    private String title;
    private String artist;
    private String album;
    private String albumId;
    private DownloadStatus status;
    private long downloadedBytes;
    private long totalBytes;
    private long downloadTimestamp;
    private String filePath;
    private String errorMessage;
    private int retryCount;

    public DownloadInfo() {
        this.status = DownloadStatus.NOT_DOWNLOADED;
        this.downloadTimestamp = System.currentTimeMillis();
        this.retryCount = 0;
    }

    public DownloadInfo(Song song) {
        this();
        this.songId = song.getId();
        this.title = song.getTitle();
        this.artist = song.getArtist();
        this.album = song.getAlbum();
        this.albumId = song.getAlbumId();
    }

    // Getters and Setters
    public String getSongId() {
        return songId;
    }

    public void setSongId(String songId) {
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

    public DownloadStatus getStatus() {
        return status;
    }

    public void setStatus(DownloadStatus status) {
        this.status = status;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public void setDownloadedBytes(long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long getDownloadTimestamp() {
        return downloadTimestamp;
    }

    public void setDownloadTimestamp(long downloadTimestamp) {
        this.downloadTimestamp = downloadTimestamp;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
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

    /**
     * 获取下载进度百分比
     */
    public int getProgressPercentage() {
        if (totalBytes <= 0) return 0;
        return (int) ((downloadedBytes * 100) / totalBytes);
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
     * 检查是否已暂停
     */
    public boolean isPaused() {
        return status == DownloadStatus.DOWNLOAD_PAUSED;
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
}
