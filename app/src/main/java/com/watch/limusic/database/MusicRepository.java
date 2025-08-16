package com.watch.limusic.database;

import android.content.Context;
import android.util.Log;
import android.content.Intent;

import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.api.SubsonicResponse;
import com.watch.limusic.model.Album;
import com.watch.limusic.model.Song;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音乐数据存储库，协调网络API和本地数据库之间的数据交互
 */
public class MusicRepository {
    private static final String TAG = "MusicRepository";
    
    // 单例实例
    private static volatile MusicRepository INSTANCE;
    
    // 依赖组件
    private final MusicDatabase database;
    private final NavidromeApi api;
    private final CacheDetector cacheDetector;
    private final Context context;
    
    // 线程池，用于异步操作
    private final ExecutorService executorService;
    
    // 网络状态
    private boolean isNetworkAvailable = true;
    
    /**
     * 获取单例实例
     */
    public static MusicRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MusicRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MusicRepository(context);
                }
            }
        }
        return INSTANCE;
    }
    
    /**
     * 私有构造函数
     */
    private MusicRepository(Context context) {
        this.context = context.getApplicationContext();
        this.database = MusicDatabase.getInstance(context);
        this.api = NavidromeApi.getInstance(context);
        this.cacheDetector = new CacheDetector(context);
        this.executorService = Executors.newFixedThreadPool(3); // 创建3线程的线程池
    }
    
    /**
     * 设置网络可用状态
     */
    public void setNetworkAvailable(boolean available) {
        isNetworkAvailable = available;
        Log.d(TAG, "网络状态更改: " + (available ? "可用" : "不可用"));
    }
    
    /**
     * 获取专辑列表，优先从网络获取，失败时从数据库获取
     */
    public List<Album> getAlbums(String type, int size, int offset) {
        if (isNetworkAvailable) {
            try {
                // 从网络获取专辑
                SubsonicResponse<List<Album>> response = api.getAlbumList(type, size, offset);
                if (response != null && response.isSuccess() && response.getData() != null) {
                    List<Album> albums = response.getData();
                    
                    // 异步保存到数据库
                    saveAlbumsToDatabase(albums);
                    
                    return albums;
                }
            } catch (IOException e) {
                Log.e(TAG, "从网络获取专辑失败", e);
                // 如果网络获取失败，尝试从数据库获取
            }
        }
        
        // 从数据库获取专辑
        return getAlbumsFromDatabase(offset, size);
    }
    
    /**
     * 从数据库获取专辑列表
     */
    public List<Album> getAlbumsFromDatabase(int offset, int limit) {
        try {
            List<AlbumEntity> entities = database.albumDao().getAllAlbums();
            if (entities.isEmpty()) {
                return new ArrayList<>();
            }
            
            // 应用分页
            int fromIndex = Math.min(offset, entities.size());
            int toIndex = Math.min(offset + limit, entities.size());
            if (fromIndex >= toIndex) {
                return new ArrayList<>();
            }
            
            List<AlbumEntity> paginatedEntities = entities.subList(fromIndex, toIndex);
            return EntityConverter.toAlbums(paginatedEntities);
        } catch (Exception e) {
            Log.e(TAG, "从数据库获取专辑失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 保存专辑列表到数据库
     */
    private void saveAlbumsToDatabase(List<Album> albums) {
        if (albums == null || albums.isEmpty()) return;
        
        executorService.execute(() -> {
            try {
                List<AlbumEntity> entities = EntityConverter.toAlbumEntities(albums);
                database.albumDao().insertAllAlbums(entities);
                Log.d(TAG, "成功保存 " + albums.size() + " 张专辑到数据库");
            } catch (Exception e) {
                Log.e(TAG, "保存专辑到数据库失败", e);
            }
        });
    }
    
    /**
     * 获取专辑歌曲，优先从网络获取，失败时从数据库获取
     */
    public List<Song> getAlbumSongs(String albumId) {
        if (isNetworkAvailable) {
            try {
                // 从网络获取歌曲
                List<Song> songs = api.getAlbumSongs(albumId);
                if (songs != null && !songs.isEmpty()) {
                    // 检查每首歌曲的缓存状态
                    checkAndUpdateSongsCacheStatus(songs);
                    
                    // 异步保存到数据库
                    saveSongsToDatabase(songs);
                    
                    Log.d(TAG, "从网络获取专辑歌曲成功: " + songs.size() + " 首, 专辑ID: " + albumId);
                    return songs;
                } else {
                    Log.w(TAG, "从网络获取专辑歌曲失败: 没有歌曲, 专辑ID: " + albumId);
                }
            } catch (IOException e) {
                Log.e(TAG, "从网络获取专辑歌曲失败: " + e.getMessage() + ", 专辑ID: " + albumId);
                // 如果网络获取失败，尝试从数据库获取
            }
        } else {
            Log.d(TAG, "网络不可用，尝试从数据库获取专辑歌曲, 专辑ID: " + albumId);
        }
        
        // 从数据库获取歌曲
        List<Song> dbSongs = getSongsFromDatabase(albumId);
        Log.d(TAG, "从数据库获取专辑歌曲: " + dbSongs.size() + " 首, 专辑ID: " + albumId);
        return dbSongs;
    }
    
    /**
     * 从数据库获取专辑歌曲
     */
    public List<Song> getSongsFromDatabase(String albumId) {
        try {
            List<SongEntity> entities = database.songDao().getSongsByAlbumId(albumId);
            Log.d(TAG, "从数据库查询专辑歌曲: 找到 " + entities.size() + " 首, 专辑ID: " + albumId);
            
            // 如果没有找到歌曲，尝试获取所有歌曲并过滤
            if (entities.isEmpty()) {
                Log.w(TAG, "数据库中没有找到专辑歌曲，尝试从所有歌曲中过滤, 专辑ID: " + albumId);
                List<SongEntity> allSongs = database.songDao().getAllSongs();
                Log.d(TAG, "数据库中共有 " + allSongs.size() + " 首歌曲");
                
                // 手动过滤出属于该专辑的歌曲
                List<SongEntity> filteredSongs = new ArrayList<>();
                for (SongEntity song : allSongs) {
                    if (albumId.equals(song.getAlbumId())) {
                        filteredSongs.add(song);
                    }
                }
                
                if (!filteredSongs.isEmpty()) {
                    Log.d(TAG, "通过过滤找到 " + filteredSongs.size() + " 首专辑歌曲");
                    return EntityConverter.toSongs(filteredSongs);
                }
            }
            
            return EntityConverter.toSongs(entities);
        } catch (Exception e) {
            Log.e(TAG, "从数据库获取歌曲失败: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 保存歌曲到数据库
     */
    public void saveSongsToDatabase(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return;
        
        executorService.execute(() -> {
            try {
                List<SongEntity> entities = EntityConverter.toSongEntities(songs);

                // 先补齐缺失的专辑占位，避免外键约束失败
                try {
                    List<AlbumEntity> placeholders = new ArrayList<>();
                    for (Song s : songs) {
                        String albumId = s.getAlbumId();
                        if (albumId == null || albumId.isEmpty()) continue;
                        // 若专辑不存在则构造一个轻量占位，不覆盖已有专辑
                        AlbumEntity exist = database.albumDao().getAlbumById(albumId);
                        if (exist == null) {
                            String name = s.getAlbum() != null ? s.getAlbum() : "";
                            String artist = s.getArtist() != null ? s.getArtist() : "";
                            String cover = s.getCoverArtUrl();
                            placeholders.add(new AlbumEntity(albumId, name, artist, "", cover, 0, 0, 0));
                        }
                    }
                    if (!placeholders.isEmpty()) {
                        database.albumDao().insertAlbumsIfAbsent(placeholders);
                    }
                } catch (Exception eIgnore) {
                    Log.w(TAG, "插入专辑占位失败(忽略继续): " + eIgnore.getMessage());
                }
                
                // 更新缓存状态与 initial
                for (SongEntity entity : entities) {
                    boolean isCached = cacheDetector.isSongCached(entity.getId());
                    entity.setCached(isCached);
                    if (entity.getInitial() == null || entity.getInitial().isEmpty()) {
                        try {
                            String first = com.watch.limusic.util.PinyinUtil.getFirstLetter(entity.getTitle());
                            entity.setInitial(first != null ? first : "#");
                        } catch (Exception ignore) {
                            entity.setInitial("#");
                        }
                    }
                }
                
                database.songDao().insertAllSongs(entities);
                Log.d(TAG, "成功保存 " + songs.size() + " 首歌曲到数据库");

                // 通知UI数据已更新（总数与字母偏移可据此刷新）
                try {
                    int total = database.songDao().getSongCount();
                    Intent intent = new Intent("com.watch.limusic.DB_SONGS_UPDATED");
                    intent.putExtra("totalCount", total);
                    context.sendBroadcast(intent);
                } catch (Exception e) {
                    Log.w(TAG, "发送DB_SONGS_UPDATED广播失败: " + e.getMessage());
                }
            } catch (Exception e) {
                Log.e(TAG, "保存歌曲到数据库失败", e);
            }
        });
    }
    
    /**
     * 检查和更新歌曲的缓存状态
     */
    public void checkAndUpdateSongsCacheStatus(List<Song> songs) {
        for (Song song : songs) {
            boolean isCached = cacheDetector.isSongCached(song.getId());
            
            // 更新数据库中的缓存状态
            updateSongCacheStatus(song.getId(), isCached);
        }
    }
    
    /**
     * 更新歌曲缓存状态
     */
    public void updateSongCacheStatus(String songId, boolean isCached) {
        executorService.execute(() -> {
            try {
                database.songDao().updateCacheStatus(songId, isCached);
                Log.d(TAG, "更新歌曲缓存状态: " + songId + ", 缓存=" + isCached);
            } catch (Exception e) {
                Log.e(TAG, "更新歌曲缓存状态失败", e);
            }
        });
    }
    
    /**
     * 同步所有歌曲的缓存状态
     */
    public void syncAllSongsCacheStatus() {
        executorService.execute(() -> {
            try {
                cacheDetector.syncCacheStatus();
                Log.d(TAG, "所有歌曲缓存状态同步完成");
            } catch (Exception e) {
                Log.e(TAG, "同步歌曲缓存状态失败", e);
            }
        });
    }
    
    /**
     * 获取已缓存的歌曲
     */
    public List<Song> getCachedSongs() {
        try {
            List<SongEntity> entities = database.songDao().getCachedSongs();
            return EntityConverter.toSongs(entities);
        } catch (Exception e) {
            Log.e(TAG, "获取已缓存歌曲失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 搜索专辑
     */
    public List<Album> searchAlbums(String query) {
        if (isNetworkAvailable) {
            // 此处应该调用API搜索方法，但目前NavidromeApi没有实现这个方法
            // 所以只能从本地数据库搜索
        }
        
        // 从数据库搜索
        try {
            List<AlbumEntity> entities = database.albumDao().searchAlbums(query);
            return EntityConverter.toAlbums(entities);
        } catch (Exception e) {
            Log.e(TAG, "搜索专辑失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 搜索歌曲
     */
    public List<Song> searchSongs(String query) {
        if (isNetworkAvailable) {
            // 此处应该调用API搜索方法，但目前NavidromeApi没有实现这个方法
            // 所以只能从本地数据库搜索
        }
        
        // 从数据库搜索
        try {
            List<SongEntity> entities = database.songDao().searchSongs(query);
            return EntityConverter.toSongs(entities);
        } catch (Exception e) {
            Log.e(TAG, "搜索歌曲失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取数据库中的歌曲数量
     */
    public int getSongCount() {
        try {
            return database.songDao().getSongCount();
        } catch (Exception e) {
            Log.e(TAG, "获取歌曲数量失败", e);
            return 0;
        }
    }
    
    /**
     * 从数据库获取所有歌曲
     */
    public List<Song> getAllSongsFromDatabase() {
        try {
            List<SongEntity> entities = database.songDao().getAllSongs();
            Log.d(TAG, "从数据库获取所有歌曲: " + entities.size() + " 首");
            
            if (entities.isEmpty()) {
                Log.w(TAG, "数据库中没有歌曲，尝试从缓存中获取");
                // 如果数据库中没有歌曲，尝试获取已缓存的歌曲
                entities = database.songDao().getCachedSongs();
                Log.d(TAG, "从数据库获取已缓存歌曲: " + entities.size() + " 首");
            }
            
            return EntityConverter.toSongs(entities);
        } catch (Exception e) {
            Log.e(TAG, "从数据库获取所有歌曲失败", e);
            return new ArrayList<>();
        }
    }

    // 轻量方案新增：范围加载（返回 UI 需要的模型）
    public List<Song> getSongsRange(int limit, int offset) {
        try {
            List<SongEntity> entities = database.songDao().getSongsRange(limit, offset);
            return EntityConverter.toSongs(entities);
        } catch (Exception e) {
            Log.e(TAG, "范围加载歌曲失败", e);
            return new ArrayList<>();
        }
    }

    // 轻量方案新增：构建字母锚点到全局偏移的映射
    public Map<String, Integer> getLetterOffsetMap() {
        Map<String, Integer> offsetMap = new HashMap<>();
        try {
            List<InitialCount> counts = database.songDao().getCountsByInitial();
            int totalHashAndDigits = 0;
            int totalSoFar = 0;

            // 先统计 '#' 与 0-9 的合并偏移
            int hashCount = 0;
            int digitCount = 0;
            for (InitialCount ic : counts) {
                if (ic == null || ic.initial == null) continue;
                String k = ic.initial;
                if ("#".equals(k)) hashCount += ic.cnt;
                else if (k.length() == 1 && Character.isDigit(k.charAt(0))) digitCount += ic.cnt;
            }
            totalHashAndDigits = hashCount + digitCount;

            // '#' 锚点指向最开头（仅当确有 '#' 或数字组时）
            if (totalHashAndDigits > 0) {
                offsetMap.put("#", 0);
            }

            // A-Z 顺序累积（A 的偏移 = '#'与数字的总和 + 'A'之前字母组的计数和）
            totalSoFar = totalHashAndDigits;
            for (char c = 'A'; c <= 'Z'; c++) {
                String key = String.valueOf(c);
                int cnt = 0;
                for (InitialCount ic : counts) {
                    if (ic != null && key.equals(ic.initial)) {
                        cnt = ic.cnt;
                        break;
                    }
                }
                if (cnt > 0) {
                    offsetMap.put(key, totalSoFar);
                    totalSoFar += cnt;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "计算字母偏移映射失败", e);
        }
        return offsetMap;
    }
    
    /**
     * 关闭存储库，清理资源
     */
    public void shutdown() {
        executorService.shutdown();
    }
} 