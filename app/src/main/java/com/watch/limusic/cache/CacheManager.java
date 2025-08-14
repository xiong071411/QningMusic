package com.watch.limusic.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer2.database.DatabaseProvider;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.upstream.cache.Cache;
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
    private static volatile CacheManager INSTANCE;
    private final Context context;

    // 私有构造函数，确保单例模式
    private CacheManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // 获取单例实例
    public static synchronized CacheManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (CacheManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new CacheManager(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    // 获取Context
    public Context getContext() {
        return context;
    }
    
    // 获取缓存实例
    public SimpleCache getCache() {
        if (cache == null) {
            long maxBytes = getMaxCacheBytes();
            LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(maxBytes);
            databaseProvider = new StandaloneDatabaseProvider(context);
            File dir = new File(context.getCacheDir(), "media");
            cache = new SimpleCache(dir, evictor, databaseProvider);
        }
        return cache;
    }

    // 构建缓存数据源工厂
    public CacheDataSource.Factory buildCacheDataSourceFactory() {
        // 创建默认的网络数据源作为上游；如果缓存中没有命中，就从网络拉取并写入缓存
        DefaultDataSource.Factory upstreamFactory = new DefaultDataSource.Factory(
                context,
                new DefaultHttpDataSource.Factory()
                        .setUserAgent("LiMusic")
        );

        return new CacheDataSource.Factory()
                .setCache(getCache())
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(new CacheDataSink.Factory().setCache(getCache()))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    // 检查URL是否已缓存
    public boolean isCached(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        try {
            Cache cache = getCache();
            String key = buildCacheKey(url);
            
            // 检查缓存中是否有此key
            boolean hasKey = cache.getKeys().contains(key);
            
            if (hasKey) {
                // 检查是否有缓存数据
                boolean hasCachedSpans = cache.getCachedSpans(key).size() > 0;
                return hasCachedSpans;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "检查缓存状态失败: " + url, e);
            return false;
        }
    }
    
    // 构建缓存键
    private String buildCacheKey(String url) {
        return url;
    }

    // 获取最大缓存大小
    public long getMaxCacheBytes() {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long mb = sp.getLong(KEY_MAX_MB, 70);
        return mb * 1024 * 1024;
    }

    // 设置最大缓存大小
    public void setMaxCacheMb(long mb) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_MAX_MB, mb).apply();
        // Need to recreate cache with new size next time.
        release();
    }

    // 清理缓存
    public void clearCache() {
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

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f); else f.delete();
            }
        }
        dir.delete();
    }

    // 释放缓存资源
    public void release() {
        if (cache != null) {
            try {
                cache.release();
            } catch (Exception e) {
                Log.e(TAG, "释放缓存出错", e);
            }
            cache = null;
        }
    }

    // 获取缓存使用量
    public long getCacheUsageBytes() {
        try {
            return getCache().getCacheSpace();
        } catch (Exception e) {
            return 0;
        }
    }
    
    // 兼容静态方法调用
    public static SimpleCache getCache(Context context) {
        return getInstance(context).getCache();
    }
    
    public static CacheDataSource.Factory buildCacheDataSourceFactory(Context context) {
        return getInstance(context).buildCacheDataSourceFactory();
    }
    
    public static long getMaxCacheBytes(Context context) {
        return getInstance(context).getMaxCacheBytes();
    }
    
    public static void setMaxCacheMb(Context context, long mb) {
        getInstance(context).setMaxCacheMb(mb);
    }
    
    public static void clearCache(Context context) {
        getInstance(context).clearCache();
    }
    
    public static long getCacheUsageBytes(Context context) {
        return getInstance(context).getCacheUsageBytes();
    }
} 