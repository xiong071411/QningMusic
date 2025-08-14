package com.watch.limusic.database;

import android.content.Context;
import android.util.Log;

import com.google.android.exoplayer2.upstream.cache.Cache;
import com.watch.limusic.cache.CacheManager;
import com.watch.limusic.model.Song;
import com.watch.limusic.api.NavidromeApi;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓存检测工具类，负责检测歌曲是否已缓存并同步数据库状态
 */
public class CacheDetector {
    private static final String TAG = "CacheDetector";
    
    private final MusicDatabase database;
    private final CacheManager cacheManager;

    public CacheDetector(Context context) {
        this.database = MusicDatabase.getInstance(context);
        this.cacheManager = CacheManager.getInstance(context);
    }

    /**
     * 检测歌曲是否已缓存
     * @param songId 歌曲ID
     * @return 是否已缓存
     */
    public boolean isSongCached(String songId) {
        try {
            SongEntity song = database.songDao().getSongById(songId);
            if (song != null) {
                // 获取流URL
                String streamUrl = song.getStreamUrl();
                if (streamUrl == null || streamUrl.isEmpty()) {
                    // 如果数据库中没有流URL，尝试从API构建
                    streamUrl = NavidromeApi.getInstance(cacheManager.getContext()).getStreamUrl(songId);
                    // 更新数据库中的URL
                    if (streamUrl != null && !streamUrl.isEmpty()) {
                        song.setStreamUrl(streamUrl);
                        // 如果数据库有更新方法则调用，否则忽略
                        try {
                            database.songDao().updateSong(song);
                        } catch (Exception e) {
                            Log.w(TAG, "无法更新歌曲流URL: " + e.getMessage());
                        }
                    }
                }
                
                // 直接检查ExoPlayer缓存中是否存在
                boolean isActuallyCached = streamUrl != null && !streamUrl.isEmpty() && 
                                         cacheManager.isCached(streamUrl);
                
                // 如果数据库标记与实际缓存状态不一致，更新数据库
                if (song.isCached() != isActuallyCached) {
                    song.setCached(isActuallyCached);
                    database.songDao().updateCacheStatus(songId, isActuallyCached);
                    Log.d(TAG, "更新歌曲缓存状态: " + song.getTitle() + ", 缓存=" + isActuallyCached);
                }
                
                return isActuallyCached;
            } else {
                // 如果数据库中没有这首歌，直接检查缓存
                String streamUrl = NavidromeApi.getInstance(cacheManager.getContext()).getStreamUrl(songId);
                return streamUrl != null && !streamUrl.isEmpty() && cacheManager.isCached(streamUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "检查缓存状态时出错", e);
        }
        return false;
    }

    /**
     * 获取缓存中的所有歌曲
     * @return 已缓存的歌曲列表
     */
    public List<Song> getAllCachedSongs() {
        List<Song> cachedSongs = new ArrayList<>();
        try {
            List<SongEntity> cachedEntities = database.songDao().getCachedSongs();
            for (SongEntity entity : cachedEntities) {
                // 再次确认是否真的已缓存
                if (cacheManager.isCached(entity.getStreamUrl())) {
                    cachedSongs.add(EntityConverter.toSong(entity));
                } else {
                    // 更新数据库状态
                    entity.setCached(false);
                    database.songDao().updateCacheStatus(entity.getId(), false);
                    Log.d(TAG, "歌曲缓存状态不一致，已更新: " + entity.getTitle());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取缓存歌曲时出错", e);
        }
        return cachedSongs;
    }

    /**
     * 同步所有歌曲的缓存状态
     * 这是一个潜在的耗时操作，应在后台线程执行
     */
    public void syncCacheStatus() {
        try {
            List<SongEntity> allSongs = database.songDao().getAllSongs();
            Cache cache = cacheManager.getCache();
            
            for (SongEntity song : allSongs) {
                boolean isCached = cacheManager.isCached(song.getStreamUrl());
                
                if (song.isCached() != isCached) {
                    song.setCached(isCached);
                    database.songDao().updateCacheStatus(song.getId(), isCached);
                    Log.d(TAG, "已更新歌曲缓存状态: " + song.getTitle() + ", 缓存=" + isCached);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "同步缓存状态时出错", e);
        }
    }
    
    /**
     * 更新单首歌曲的缓存状态
     * @param song 歌曲
     * @param isCached 是否已缓存
     */
    public void updateSongCacheStatus(Song song, boolean isCached) {
        try {
            SongEntity entity = database.songDao().getSongById(song.getId());
            if (entity != null) {
                entity.setCached(isCached);
                database.songDao().updateCacheStatus(song.getId(), isCached);
                Log.d(TAG, "已手动更新歌曲缓存状态: " + song.getTitle() + ", 缓存=" + isCached);
            } else {
                // 歌曲不在数据库中，添加它
                SongEntity newEntity = EntityConverter.toSongEntity(song);
                newEntity.setCached(isCached);
                database.songDao().insertSong(newEntity);
                Log.d(TAG, "歌曲不在数据库中，已添加并设置缓存状态: " + song.getTitle());
            }
        } catch (Exception e) {
            Log.e(TAG, "更新歌曲缓存状态时出错", e);
        }
    }
} 