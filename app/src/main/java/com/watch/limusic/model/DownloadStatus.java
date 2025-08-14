package com.watch.limusic.model;

/**
 * 下载状态枚举
 */
public enum DownloadStatus {
    NOT_DOWNLOADED("未下载"),
    DOWNLOADING("下载中"),
    DOWNLOADED("已下载"),
    DOWNLOAD_FAILED("下载失败"),
    DOWNLOAD_PAUSED("下载暂停");

    private final String description;

    DownloadStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
