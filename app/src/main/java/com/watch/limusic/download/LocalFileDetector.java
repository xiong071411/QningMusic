package com.watch.limusic.download;

import android.content.Context;
import android.util.Log;

import com.watch.limusic.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地文件检测器 - 可靠地检测已下载的歌曲
 * 替代不可靠的ExoPlayer缓存检测
 */
public class LocalFileDetector {
    private static final String TAG = "LocalFileDetector";
    
    private final Context context;
    private final File songsDir;
    private final File coversDir;
    
    private static final Set<String> SUPPORTED_EXTS = new HashSet<>(Arrays.asList(
        "mp3", "flac", "ogg", "opus", "aac", "m4a", "wav"
    ));

    public LocalFileDetector(Context context) {
        this.context = context.getApplicationContext();
        File downloadDir = new File(context.getExternalFilesDir(null), "downloads");
        this.songsDir = new File(downloadDir, "songs");
        this.coversDir = new File(downloadDir, "covers");
    }

    /**
     * 检查歌曲是否已下载
     * 这是一个简单可靠的文件系统检查，比ExoPlayer缓存检测更可靠
     */
    public boolean isSongDownloaded(String songId) {
        if (songId == null || songId.isEmpty()) {
            return false;
        }
        return findExistingSongFile(songId) != null;
    }
    
    private File findExistingSongFile(String songId) {
        for (String ext : SUPPORTED_EXTS) {
            File f = new File(songsDir, songId + "." + ext);
            if (f.exists() && f.length() > 0) return f;
        }
        return null;
    }

    /**
     * 检查歌曲是否已下载（使用Song对象）
     */
    public boolean isSongDownloaded(Song song) {
        return song != null && isSongDownloaded(song.getId());
    }

    /**
     * 获取已下载歌曲的文件路径
     */
    public String getDownloadedSongPath(String songId) {
        File f = findExistingSongFile(songId);
        return f != null ? f.getAbsolutePath() : null;
    }

    /**
     * 获取已下载歌曲的文件大小
     */
    public long getDownloadedSongSize(String songId) {
        File f = findExistingSongFile(songId);
        return f != null ? f.length() : 0;
    }

    /**
     * 获取所有已下载的歌曲ID列表
     */
    public List<String> getAllDownloadedSongIds() {
        List<String> downloadedIds = new ArrayList<>();
        
        if (!songsDir.exists()) {
            return downloadedIds;
        }
        
        File[] files = songsDir.listFiles((dir, name) -> {
            int dot = name.lastIndexOf('.');
            if (dot <= 0) return false;
            String ext = name.substring(dot + 1).toLowerCase();
            return SUPPORTED_EXTS.contains(ext);
        });
        if (files != null) {
            for (File file : files) {
                if (file.length() > 0) {
                    String fileName = file.getName();
                    int dot = fileName.lastIndexOf('.');
                    if (dot > 0) {
                        String songId = fileName.substring(0, dot);
                    downloadedIds.add(songId);
                    }
                }
            }
        }
        
        Log.d(TAG, "找到 " + downloadedIds.size() + " 首已下载的歌曲");
        return downloadedIds;
    }

    /**
     * 过滤歌曲列表，只返回已下载的歌曲
     */
    public List<Song> filterDownloadedSongs(List<Song> songs) {
        List<Song> downloadedSongs = new ArrayList<>();
        
        if (songs == null || songs.isEmpty()) {
            return downloadedSongs;
        }
        
        for (Song song : songs) {
            if (isSongDownloaded(song)) {
                downloadedSongs.add(song);
            }
        }
        
        Log.d(TAG, "从 " + songs.size() + " 首歌曲中过滤出 " + downloadedSongs.size() + " 首已下载的歌曲");
        return downloadedSongs;
    }

    /**
     * 检查专辑封面是否已下载
     */
    public boolean isAlbumCoverDownloaded(String albumId) {
        if (albumId == null || albumId.isEmpty()) {
            return false;
        }
        
        File coverFile = new File(coversDir, albumId + ".jpg");
        return coverFile.exists() && coverFile.length() > 0;
    }

    /**
     * 获取已下载专辑封面的文件路径
     */
    public String getDownloadedAlbumCoverPath(String albumId) {
        if (!isAlbumCoverDownloaded(albumId)) {
            return null;
        }
        
        File coverFile = new File(coversDir, albumId + ".jpg");
        return coverFile.getAbsolutePath();
    }

    /**
     * 获取下载目录的总大小
     */
    public long getTotalDownloadSize() {
        return getDirectorySize(songsDir) + getDirectorySize(coversDir);
    }

    /**
     * 获取歌曲下载目录的大小
     */
    public long getSongsDownloadSize() {
        return getDirectorySize(songsDir);
    }

    /**
     * 获取封面下载目录的大小
     */
    public long getCoversDownloadSize() {
        return getDirectorySize(coversDir);
    }

    /**
     * 计算目录大小的辅助方法
     */
    private long getDirectorySize(File directory) {
        long size = 0;
        
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return size;
        }
        
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirectorySize(file);
                }
            }
        }
        
        return size;
    }

    /**
     * 验证下载文件的完整性
     */
    public boolean validateDownloadedFile(String songId) {
        File songFile = new File(songsDir, songId + ".mp3");
        
        if (!songFile.exists()) {
            return false;
        }
        
        // 基本的文件完整性检查
        long fileSize = songFile.length();
        if (fileSize < 1024) { // 文件太小，可能损坏
            Log.w(TAG, "下载文件可能损坏，文件太小: " + songId + " (大小: " + fileSize + " bytes)");
            return false;
        }
        
        // 可以添加更多的完整性检查，比如文件头验证等
        return true;
    }

    /**
     * 清理损坏的下载文件
     */
    public int cleanupCorruptedFiles() {
        int cleanedCount = 0;
        
        if (!songsDir.exists()) {
            return cleanedCount;
        }
        
        File[] files = songsDir.listFiles((dir, name) -> {
            int dot = name.lastIndexOf('.');
            if (dot <= 0) return false;
            String ext = name.substring(dot + 1).toLowerCase();
            return SUPPORTED_EXTS.contains(ext);
        });
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                int dot = fileName.lastIndexOf('.');
                if (dot > 0) {
                    String songId = fileName.substring(0, dot);
                if (!validateDownloadedFile(songId)) {
                    if (file.delete()) {
                        cleanedCount++;
                        Log.i(TAG, "清理损坏的下载文件: " + songId);
                        }
                    }
                }
            }
        }
        
        Log.i(TAG, "清理了 " + cleanedCount + " 个损坏的下载文件");
        return cleanedCount;
    }

    /**
     * 获取下载目录路径（用于调试）
     */
    public String getDownloadDirectoryPath() {
        return songsDir.getParent();
    }
}
