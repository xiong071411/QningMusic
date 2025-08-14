package com.watch.limusic.database;

import androidx.room.TypeConverter;

import com.watch.limusic.model.DownloadStatus;

/**
 * 数据库类型转换器
 */
public class DatabaseConverters {

    /**
     * 将DownloadStatus枚举转换为字符串存储
     */
    @TypeConverter
    public static String fromDownloadStatus(DownloadStatus status) {
        return status == null ? null : status.name();
    }

    /**
     * 将字符串转换为DownloadStatus枚举
     */
    @TypeConverter
    public static DownloadStatus toDownloadStatus(String status) {
        if (status == null) {
            return DownloadStatus.NOT_DOWNLOADED;
        }
        try {
            return DownloadStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return DownloadStatus.NOT_DOWNLOADED;
        }
    }
}
