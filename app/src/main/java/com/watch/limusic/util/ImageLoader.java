package com.watch.limusic.util;

import android.content.Context;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.watch.limusic.R;
import com.watch.limusic.api.NavidromeApi;

/**
 * 图片加载工具类
 * 封装Glide图片加载逻辑，统一管理缓存策略和加载参数
 */
public class ImageLoader {

    // 是否暂停加载 - 用于RecyclerView滚动时暂停图片加载
    private static boolean isPaused = false;
    private static RequestManager requestManager = null;
    
    /**
     * 为RecyclerView添加滚动监听器，滚动时暂停图片加载
     * @param recyclerView 目标RecyclerView
     * @param context 上下文
     */
    public static void setupRecyclerViewScrollListener(RecyclerView recyclerView, Context context) {
        if (requestManager == null && context != null) {
            requestManager = Glide.with(context);
        }
        
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // 停止滚动时恢复加载
                    resumeLoading();
                } else {
                    // 滚动时暂停加载
                    pauseLoading();
                }
            }
        });
    }
    
    /**
     * 暂停图片加载
     */
    private static void pauseLoading() {
        isPaused = true;
        if (requestManager != null) {
            requestManager.pauseRequests();
        }
    }
    
    /**
     * 恢复图片加载
     */
    private static void resumeLoading() {
        isPaused = false;
        if (requestManager != null) {
            requestManager.resumeRequests();
        }
    }

    /**
     * 加载专辑封面 - 列表项小图
     * @param context 上下文
     * @param coverArtId 封面ID
     * @param imageView 目标ImageView
     */
    public static void loadAlbumListCover(@NonNull Context context, String coverArtId, @NonNull ImageView imageView) {
        if (coverArtId == null || coverArtId.isEmpty()) {
            imageView.setImageResource(R.drawable.default_album_art);
            return;
        }

        // 列表项小尺寸(约120dp)
        int size = 120;
        loadCoverArt(context, coverArtId, imageView, size, size);
    }

    /**
     * 加载专辑封面 - 播放控制栏中等图
     * @param context 上下文
     * @param coverArtId 封面ID
     * @param imageView 目标ImageView
     */
    public static void loadPlayerCover(@NonNull Context context, String coverArtId, @NonNull ImageView imageView) {
        if (coverArtId == null || coverArtId.isEmpty()) {
            imageView.setImageResource(R.drawable.default_album_art);
            return;
        }

        // 播放器控制栏中等尺寸(约150dp)
        int size = 150;
        loadCoverArt(context, coverArtId, imageView, size, size);
    }

    /**
     * 加载封面图片基础方法
     * @param context 上下文
     * @param coverArtId 封面ID
     * @param imageView 目标ImageView
     * @param width 宽度
     * @param height 高度
     */
    private static void loadCoverArt(@NonNull Context context, String coverArtId, 
            @NonNull ImageView imageView, int width, int height) {
        
        // 构建请求选项
        RequestOptions options = new RequestOptions()
                .placeholder(R.drawable.default_album_art)
                .error(R.drawable.default_album_art)
                .override(width, height)
                .diskCacheStrategy(DiskCacheStrategy.ALL);
        
        // 获取URL并加载
        String url = NavidromeApi.getInstance(context).getCoverArtUrl(coverArtId);
        
        // 使用Glide而不是GlideApp
        Glide.with(context)
                .load(url)
                .apply(options)
                .into(imageView);
    }
} 