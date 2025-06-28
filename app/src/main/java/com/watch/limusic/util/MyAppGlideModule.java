package com.watch.limusic.util;

import android.content.Context;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;

/**
 * 自定义Glide配置模块
 * 通过控制内存缓存、磁盘缓存和位图池的大小，优化内存使用
 */
@GlideModule
public class MyAppGlideModule extends AppGlideModule {
    
    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        // 设置内存缓存大小为20MB (较小的值以减少内存占用)
        int memoryCacheSizeBytes = 1024 * 1024 * 20; // 20MB
        builder.setMemoryCache(new LruResourceCache(memoryCacheSizeBytes));
        
        // 设置磁盘缓存大小为100MB
        int diskCacheSizeBytes = 1024 * 1024 * 100; // 100MB
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, diskCacheSizeBytes));
        
        // 设置位图池大小为内存缓存的1/4，减少位图分配和GC
        builder.setBitmapPool(new LruBitmapPool(memoryCacheSizeBytes / 4));
    }
    
    // 禁用解析清单文件，提高启动速度
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
} 