package com.watch.limusic.download;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.database.DownloadRepository;
import com.watch.limusic.model.DownloadInfo;
import com.watch.limusic.model.DownloadStatus;
import com.watch.limusic.model.Song;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.content.SharedPreferences;
import java.util.Locale;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

/**
 * 下载管理器 - 处理歌曲的手动下载
 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static final int MAX_CONCURRENT_DOWNLOADS = 3;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int BUFFER_SIZE = 8192;
    
    // 广播动作
    public static final String ACTION_DOWNLOAD_PROGRESS = "com.watch.limusic.DOWNLOAD_PROGRESS";
    public static final String ACTION_DOWNLOAD_COMPLETE = "com.watch.limusic.DOWNLOAD_COMPLETE";
    public static final String ACTION_DOWNLOAD_FAILED = "com.watch.limusic.DOWNLOAD_FAILED";
    public static final String ACTION_DOWNLOAD_CANCELED = "com.watch.limusic.DOWNLOAD_CANCELED";
    
    // 广播额外数据键
    public static final String EXTRA_SONG_ID = "songId";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_ERROR_MESSAGE = "errorMessage";

    private static DownloadManager INSTANCE;
    private final Context context;
    private final ExecutorService downloadExecutor;
    private final Handler mainHandler;
    private final ConcurrentHashMap<String, DownloadInfo> activeDownloads;
    private final ConcurrentHashMap<String, Future<?>> downloadTasks;
    private final ConcurrentHashMap<String, Boolean> pauseFlags;
    private final File downloadDir;
    private final File songsDir;
    private final File coversDir;
    private final DownloadRepository downloadRepository;
    private BroadcastReceiver configUpdatedReceiver;

    private DownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.activeDownloads = new ConcurrentHashMap<>();
        this.downloadTasks = new ConcurrentHashMap<>();
        this.pauseFlags = new ConcurrentHashMap<>();
        this.downloadRepository = DownloadRepository.getInstance(context);
        
        // 创建下载目录结构
        this.downloadDir = new File(context.getExternalFilesDir(null), "downloads");
        this.songsDir = new File(downloadDir, "songs");
        this.coversDir = new File(downloadDir, "covers");
        
        createDirectories();

        // 监听配置更新，立即中断所有下载任务
        try {
            configUpdatedReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (!com.watch.limusic.api.NavidromeApi.ACTION_NAVIDROME_CONFIG_UPDATED.equals(intent.getAction())) return;
                    cancelAll();
                }
            };
            context.registerReceiver(configUpdatedReceiver, new IntentFilter(com.watch.limusic.api.NavidromeApi.ACTION_NAVIDROME_CONFIG_UPDATED));
        } catch (Exception ignore) {}
    }

    public static synchronized DownloadManager getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new DownloadManager(context);
        }
        return INSTANCE;
    }

    private void createDirectories() {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        if (!songsDir.exists()) {
            songsDir.mkdirs();
        }
        if (!coversDir.exists()) {
            coversDir.mkdirs();
        }
    }

    /**
     * 开始下载歌曲
     */
    public void downloadSong(Song song) {
        String songId = song.getId();
        
        // 检查是否已经在下载队列中
        if (activeDownloads.containsKey(songId)) {
            DownloadInfo exist = activeDownloads.get(songId);
            if (exist != null && exist.getStatus() == DownloadStatus.DOWNLOAD_PAUSED) {
                Log.i(TAG, "检测到暂停任务，自动恢复: " + song.getTitle());
                resumeDownload(song);
                return;
            } else {
                Log.w(TAG, "歌曲已在下载队列中: " + song.getTitle());
                return;
            }
        }
        
        // 检查是否已经下载
        if (isDownloaded(songId)) {
            Log.i(TAG, "歌曲已下载: " + song.getTitle());
            return;
        }
        
        // 添加到数据库
        downloadRepository.addDownload(song);
        
        DownloadInfo downloadInfo = new DownloadInfo(song);
        downloadInfo.setStatus(DownloadStatus.DOWNLOADING);
        activeDownloads.put(songId, downloadInfo);
        
        // 更新数据库状态
        downloadRepository.updateDownloadStatus(songId, DownloadStatus.DOWNLOADING);
        
        // 提交下载任务
        Future<?> task = downloadExecutor.submit(() -> performDownload(downloadInfo));
        downloadTasks.put(songId, task);
        
        Log.i(TAG, "开始下载歌曲: " + song.getTitle());
    }

    /**
     * 取消所有进行中的下载（用于服务器配置切换时的立即中断）
     */
    public void cancelAll() {
        try {
            java.util.List<String> ids = new java.util.ArrayList<>(activeDownloads.keySet());
            for (String id : ids) {
                try { cancelDownload(id); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "取消全部下载时发生异常: " + e.getMessage());
        }
    }

    /**
     * 执行实际的下载操作
     */
    private void performDownload(DownloadInfo downloadInfo) {
        String songId = downloadInfo.getSongId();
        
        try {
            // 获取下载URL，根据“下载强制转码”设置决定是否转码
            SharedPreferences sp = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE);
            boolean forceDownloadTranscode = sp.getBoolean("force_transcode_download_non_mp3", false);
            String streamUrl;
            if (forceDownloadTranscode) {
                streamUrl = NavidromeApi.getInstance(context).getTranscodedStreamUrl(songId, "mp3", 320);
            } else {
                streamUrl = NavidromeApi.getInstance(context).getStreamUrl(songId);
            }
            if (streamUrl == null || streamUrl.isEmpty()) {
                throw new IOException("无法获取歌曲流URL");
            }
            
            // 选择保存扩展名：若强制转码=mp3，否则保留原始容器（默认mp3兜底）
            String targetExt = forceDownloadTranscode ? "mp3" : "mp3";
            // TODO: 后续可通过HEAD获取Content-Type来动态选择更准确扩展名
            // 使用.part临时文件进行下载，完成后按扩展名重命名
            File partialFile = new File(songsDir, songId + "." + targetExt + ".part");
            downloadInfo.setFilePath(partialFile.getAbsolutePath());
            
            // 开始下载
            downloadFile(streamUrl, partialFile, downloadInfo);
            
            // 若被暂停，退出而不做后续处理
            if (downloadInfo.getStatus() == DownloadStatus.DOWNLOAD_PAUSED) {
                Log.i(TAG, "下载已暂停: " + downloadInfo.getTitle());
                return;
            }
            
            // 下载完成
            downloadInfo.setStatus(DownloadStatus.DOWNLOADED);
            downloadInfo.setDownloadTimestamp(System.currentTimeMillis());
            
            // 重命名为最终文件
            File finalFile = new File(songsDir, songId + "." + targetExt);
            if (finalFile.exists()) {
                // 防御性删除旧文件
                finalFile.delete();
            }
            boolean renamed = new File(downloadInfo.getFilePath()).renameTo(finalFile);
            if (!renamed) {
                // 如果重命名失败，直接写入到最终路径
                throw new IOException("重命名下载文件失败");
            }
            downloadInfo.setFilePath(finalFile.getAbsolutePath());
            
            // 更新数据库
            downloadRepository.markDownloadCompleted(songId, finalFile.getAbsolutePath(), finalFile.length());
            
            // 尝试下载专辑封面，便于离线显示
            try {
                String albumId = downloadInfo.getAlbumId();
                if (albumId != null && !albumId.isEmpty()) {
                    downloadCoverArtIfNeeded(albumId);
                }
            } catch (Exception coverEx) {
                Log.w(TAG, "下载专辑封面失败(忽略): " + coverEx.getMessage());
            }
            
            // 发送完成广播
            sendDownloadCompleteBroadcast(songId);
            
            Log.i(TAG, "下载完成: " + downloadInfo.getTitle());
            
        } catch (Exception e) {
            Log.e(TAG, "下载失败: " + downloadInfo.getTitle(), e);
            
            downloadInfo.setStatus(DownloadStatus.DOWNLOAD_FAILED);
            downloadInfo.setErrorMessage(e.getMessage());
            downloadInfo.incrementRetryCount();
            
            // 更新数据库
            downloadRepository.markDownloadFailed(songId, e.getMessage());
            
            // 如果重试次数未超限，可以考虑重试
            if (downloadInfo.getRetryCount() < MAX_RETRY_COUNT) {
                Log.i(TAG, "准备重试下载: " + downloadInfo.getTitle() + 
                          " (重试次数: " + downloadInfo.getRetryCount() + ")");
                // 这里可以添加延迟重试逻辑
            }
            
            // 发送失败广播
            sendDownloadFailedBroadcast(songId, e.getMessage());
            
        } finally {
            // 清理：暂停状态保留activeDownloads，移除任务引用；其他状态清空
            if (activeDownloads.containsKey(songId)) {
                if (activeDownloads.get(songId).getStatus() == DownloadStatus.DOWNLOAD_PAUSED) {
                    // 仅移除任务Future，保留下载信息便于续传
                    downloadTasks.remove(songId);
                } else {
                    activeDownloads.remove(songId);
                    downloadTasks.remove(songId);
                    pauseFlags.remove(songId);
                }
            } else {
                downloadTasks.remove(songId);
                pauseFlags.remove(songId);
            }
        }
    }

    /**
     * 下载文件的核心方法
     */
    private void downloadFile(String urlString, File targetFile, DownloadInfo downloadInfo) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);

            // 断点续传：如果已有部分文件，使用Range
            long existingBytes = targetFile.exists() ? targetFile.length() : 0;
            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=" + existingBytes + "-");
            }

            connection.connect();

            int code = connection.getResponseCode();
            if (!(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL)) {
                throw new IOException("HTTP错误: " + code);
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength < 0) contentLength = 0;

            long totalBytes;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                // 尝试从Content-Range解析总大小
                String cr = connection.getHeaderField("Content-Range");
                long totalFromHeader = -1;
                if (cr != null && cr.contains("/")) {
                    try {
                        totalFromHeader = Long.parseLong(cr.substring(cr.indexOf('/') + 1));
                    } catch (Exception ignore) {}
                }
                totalBytes = totalFromHeader > 0 ? totalFromHeader : existingBytes + contentLength;
            } else {
                totalBytes = contentLength;
            }
            downloadInfo.setTotalBytes(totalBytes);

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(targetFile, existingBytes > 0)) {

                byte[] buffer = new byte[BUFFER_SIZE];
                long downloadedBytes = existingBytes;
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    // 检查暂停标记
                    if (Boolean.TRUE.equals(pauseFlags.get(downloadInfo.getSongId()))) {
                        downloadInfo.setStatus(DownloadStatus.DOWNLOAD_PAUSED);
                        downloadRepository.updateDownloadStatus(downloadInfo.getSongId(), DownloadStatus.DOWNLOAD_PAUSED);
                        break;
                    }

                    output.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;
                    downloadInfo.setDownloadedBytes(downloadedBytes);

                    // 发送进度更新
                    final int progress = downloadInfo.getProgressPercentage();
                    sendDownloadProgressBroadcast(downloadInfo.getSongId(), progress);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 检查歌曲是否已下载
     */
    public boolean isDownloaded(String songId) {
        // 支持多扩展名检测
        String[] exts = new String[]{"mp3","flac","ogg","opus","aac","m4a","wav"};
        for (String ext : exts) {
            File f = new File(songsDir, songId + "." + ext);
            if (f.exists() && f.length() > 0) return true;
        }
        return false;
    }

    /**
     * 取消下载
     */
    public void cancelDownload(String songId) {
        Future<?> task = downloadTasks.get(songId);
        if (task != null) {
            task.cancel(true);
            downloadTasks.remove(songId);
        }
        
        DownloadInfo downloadInfo = activeDownloads.remove(songId);
        if (downloadInfo != null) {
            downloadInfo.setStatus(DownloadStatus.NOT_DOWNLOADED);
            
            // 删除部分下载的文件
            File part = new File(songsDir, songId + ".mp3.part");
            if (part.exists()) part.delete();
            
            // 更新数据库
            downloadRepository.updateDownloadStatus(songId, DownloadStatus.NOT_DOWNLOADED);
        }
        
        // 发送取消广播，便于UI立即刷新
        sendDownloadCanceledBroadcast(songId);
        
        Log.i(TAG, "取消下载: " + songId);
    }

    /**
     * 暂停下载
     */
    public void pauseDownload(String songId) {
        DownloadInfo info = activeDownloads.get(songId);
        if (info == null) return;
        if (info.getStatus() == DownloadStatus.DOWNLOAD_PAUSED) return;
        pauseFlags.put(songId, true);
        info.setStatus(DownloadStatus.DOWNLOAD_PAUSED);
        downloadRepository.updateDownloadStatus(songId, DownloadStatus.DOWNLOAD_PAUSED);
        Log.i(TAG, "已请求暂停下载: " + songId);
    }

    /**
     * 继续下载（断点续传）
     */
    public void resumeDownload(Song song) {
        String songId = song.getId();
        pauseFlags.remove(songId);
        DownloadInfo info = activeDownloads.get(songId);
        if (info == null) {
            info = new DownloadInfo(song);
            activeDownloads.put(songId, info);
        }
        final DownloadInfo infoFinal = info;
        infoFinal.setStatus(DownloadStatus.DOWNLOADING);
        downloadRepository.updateDownloadStatus(songId, DownloadStatus.DOWNLOADING);
        Future<?> task = downloadExecutor.submit(() -> performDownload(infoFinal));
        downloadTasks.put(songId, task);
        Log.i(TAG, "继续下载: " + song.getTitle());
    }

    /**
     * 删除已下载的歌曲
     */
    public boolean deleteDownload(String songId) {
        boolean deleted = false;
        String[] exts = new String[]{"mp3","flac","ogg","opus","aac","m4a","wav"};
        for (String ext : exts) {
            File f = new File(songsDir, songId + "." + ext);
            if (f.exists()) {
                deleted = f.delete() || deleted;
            }
        }
        
        if (deleted) {
            // 从数据库中删除记录
            downloadRepository.deleteDownload(songId);
            Log.i(TAG, "删除下载文件: " + songId);
        }
        
        return deleted;
    }

    /**
     * 获取下载信息
     */
    public DownloadInfo getDownloadInfo(String songId) {
        return activeDownloads.get(songId);
    }

    /**
     * 获取下载文件路径
     */
    public String getDownloadFilePath(String songId) {
        String[] exts = new String[]{"mp3","flac","ogg","opus","aac","m4a","wav"};
        for (String ext : exts) {
            File f = new File(songsDir, songId + "." + ext);
            if (f.exists() && f.length() > 0) return f.getAbsolutePath();
        }
        return null;
    }

    // 广播发送方法
    private void sendDownloadProgressBroadcast(String songId, int progress) {
        Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS);
        intent.putExtra(EXTRA_SONG_ID, songId);
        intent.putExtra(EXTRA_PROGRESS, progress);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void sendDownloadCompleteBroadcast(String songId) {
        Intent intent = new Intent(ACTION_DOWNLOAD_COMPLETE);
        intent.putExtra(EXTRA_SONG_ID, songId);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void sendDownloadFailedBroadcast(String songId, String errorMessage) {
        Intent intent = new Intent(ACTION_DOWNLOAD_FAILED);
        intent.putExtra(EXTRA_SONG_ID, songId);
        intent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void sendDownloadCanceledBroadcast(String songId) {
        Intent intent = new Intent(ACTION_DOWNLOAD_CANCELED);
        intent.putExtra(EXTRA_SONG_ID, songId);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    /**
     * 释放资源
     */
    public void shutdown() {
        downloadExecutor.shutdown();
        try { if (configUpdatedReceiver != null) context.unregisterReceiver(configUpdatedReceiver); } catch (Exception ignore) {}
    }

    /**
     * 如果未保存过该专辑封面，则下载保存到本地
     */
    private void downloadCoverArtIfNeeded(String albumId) throws IOException {
        if (albumId == null || albumId.isEmpty()) return;
        File coverFile = new File(coversDir, albumId + ".jpg");
        if (coverFile.exists() && coverFile.length() > 0) return;

        String coverUrl = NavidromeApi.getInstance(context).getCoverArtUrl(albumId);
        if (coverUrl == null || coverUrl.isEmpty()) return;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(coverUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP错误: " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(coverFile)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            Log.d(TAG, "已保存专辑封面: " + coverFile.getAbsolutePath());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
