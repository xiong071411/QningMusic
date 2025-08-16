package com.watch.limusic.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

/**
 * 音乐数据库类，管理所有数据库相关操作
 */
@Database(entities = {AlbumEntity.class, SongEntity.class, DownloadEntity.class}, version = 4, exportSchema = false)
@TypeConverters({DatabaseConverters.class})
public abstract class MusicDatabase extends RoomDatabase {
    
    private static final String DATABASE_NAME = "limusic_database";
    private static volatile MusicDatabase INSTANCE;
    
    // 数据访问对象
    public abstract AlbumDao albumDao();
    public abstract SongDao songDao();
    public abstract DownloadDao downloadDao();
    
    /**
     * 获取数据库单例实例
     */
    public static MusicDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MusicDatabase.class) {
                if (INSTANCE == null) {
                    // 创建数据库实例，使用内存缓存策略提高性能
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            MusicDatabase.class,
                            DATABASE_NAME)
                            .fallbackToDestructiveMigration() // 版本变化时重建数据库
                            .allowMainThreadQueries() // 这个仅用于快速开发，生产环境应该在后台线程操作
                            .build();
                }
            }
        }
        return INSTANCE;
    }
} 