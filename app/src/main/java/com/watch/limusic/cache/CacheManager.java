package com.watch.limusic.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.exoplayer2.database.DatabaseProvider;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.io.File;

public class CacheManager {
    private static final String TAG = "CacheManager";
    private static final String PREFS = "cache_prefs";
    private static final String KEY_MAX_MB = "max_cache_mb";
    private static final long DEFAULT_MAX_BYTES = 70 * 1024 * 1024; // 70MB

    private static SimpleCache cache;
    private static DatabaseProvider databaseProvider;

    public static synchronized SimpleCache getCache(Context context) {
        if (cache == null) {
            long maxBytes = getMaxCacheBytes(context);
            LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(maxBytes);
            databaseProvider = new StandaloneDatabaseProvider(context);
            File dir = new File(context.getCacheDir(), "media");
            cache = new SimpleCache(dir, evictor, databaseProvider);
        }
        return cache;
    }

    public static synchronized CacheDataSource.Factory buildCacheDataSourceFactory(Context context) {
        // 创建默认的网络数据源作为上游；如果缓存中没有命中，就从网络拉取并写入缓存
        DefaultDataSource.Factory upstreamFactory = new DefaultDataSource.Factory(
                context,
                new DefaultHttpDataSource.Factory()
                        .setUserAgent("LiMusic")
        );

        return new CacheDataSource.Factory()
                .setCache(getCache(context))
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(new CacheDataSink.Factory().setCache(getCache(context)))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static long getMaxCacheBytes(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long mb = sp.getLong(KEY_MAX_MB, 70);
        return mb * 1024 * 1024;
    }

    public static void setMaxCacheMb(Context context, long mb) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_MAX_MB, mb).apply();
        // Need to recreate cache with new size next time.
        release();
    }

    public static void clearCache(Context context) {
        if (cache != null) {
            try {
                for (String key : cache.getKeys()) {
                    try {
                        cache.removeResource(key);
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "清理缓存失败", e);
            }
        } else {
            // 如果缓存尚未初始化，仅删除目录
            File dir = new File(context.getCacheDir(), "media");
            deleteDir(dir);
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f); else f.delete();
            }
        }
        dir.delete();
    }

    public static void release() {
        if (cache != null) {
            try {
                cache.release();
            } catch (Exception e) {
                Log.e(TAG, "释放缓存出错", e);
            }
            cache = null;
        }
    }

    public static long getCacheUsageBytes(Context context) {
        try {
            return getCache(context).getCacheSpace();
        } catch (Exception e) {
            return 0;
        }
    }
} 