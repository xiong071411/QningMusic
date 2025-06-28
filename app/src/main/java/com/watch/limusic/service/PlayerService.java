package com.watch.limusic.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import androidx.media.session.MediaButtonReceiver;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.watch.limusic.MainActivity;
import com.watch.limusic.R;
import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.model.Song;
import com.watch.limusic.cache.CacheManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.android.exoplayer2.DefaultLoadControl;

public class PlayerService extends Service {
    private static final String TAG = "PlayerService";
    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final int PLAYBACK_MODE_REPEAT_ALL = 0;
    public static final int PLAYBACK_MODE_REPEAT_ONE = 1;
    public static final int PLAYBACK_MODE_SHUFFLE = 2;

    private final IBinder binder = new LocalBinder();
    private ExoPlayer player;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private Song currentSong;
    private int playbackMode = PLAYBACK_MODE_REPEAT_ALL;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isUiVisible = false;
    
    // 添加播放列表相关变量
    private List<Song> playlist = new ArrayList<>();
    private List<MediaItem> mediaItems = new ArrayList<>();
    private int currentIndex = -1;
    private NavidromeApi navidromeApi;

    public class LocalBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 初始化Navidrome API
        navidromeApi = NavidromeApi.getInstance(this);
        
        // 初始化播放器
        initializePlayer();
        
        // 初始化媒体会话
        initializeMediaSession();
        
        // 初始化音频管理器
        initializeAudioManager();
        
        // 获取唤醒锁
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiMusic:WakeLock");
    }

    private void initializePlayer() {
        Context context = getApplicationContext();
        
        // 创建数据源工厂
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(
                CacheManager.buildCacheDataSourceFactory(context)
        );
        
        // 创建轨道选择器
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        // 设置轨道选择器参数，减少内存使用
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setForceHighestSupportedBitrate(false)  // 不强制使用最高比特率
                .setExceedRendererCapabilitiesIfNecessary(false) // 不超过渲染器能力
        );
        
        // 设置自定义加载控制，优化内存和缓冲
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(5000, false)  // 5秒后备缓冲，不保留
            .build();
        
        // 构建播放器
        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    new com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                        .setContentType(C.CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    false  // 不自动处理音频焦点，我们手动处理
                )
                .setHandleAudioBecomingNoisy(true)  // 处理耳机拔出等情况
                .build();

        // 设置音量
        player.setVolume(1.0f);
        
        // 初始时设置默认循环模式为列表循环
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setShuffleModeEnabled(false);
        Log.d(TAG, "初始化播放器 - 设置默认模式为列表循环");
        
        // 添加播放器监听器
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_READY:
                        Log.d(TAG, "播放器就绪");
                        updatePlaybackState();
                        break;
                    case Player.STATE_BUFFERING:
                        Log.d(TAG, "正在缓冲");
                        break;
                    case Player.STATE_ENDED:
                        Log.d(TAG, "播放结束");
                        updatePlaybackState();
                        // 依赖 ExoPlayer 自身的 RepeatMode 处理循环，避免手动 seek 导致索引错乱
                        break;
                    case Player.STATE_IDLE:
                        Log.d(TAG, "播放器空闲");
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlaybackState();
                if (!isPlaying) {
                    releaseAudioFocus();
                }
            }

            @Override
            public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage());
                error.printStackTrace();
            }

            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                if (mediaItem != null) {
                    int newIndex = player.getCurrentMediaItemIndex();
                    if (newIndex >= 0 && newIndex < playlist.size()) {
                        currentIndex = newIndex;
                        currentSong = playlist.get(currentIndex);
                        Log.d(TAG, "切换到歌曲: " + currentSong.getTitle() + ", 索引: " + currentIndex);
                        switch (reason) {
                            case Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT:
                                Log.d(TAG, "原因: 重复播放");
                                break;
                            case Player.MEDIA_ITEM_TRANSITION_REASON_AUTO:
                                Log.d(TAG, "原因: 自动切换到下一首");
                                break;
                            case Player.MEDIA_ITEM_TRANSITION_REASON_SEEK:
                                Log.d(TAG, "原因: 用户跳转");
                                break;
                            case Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED:
                                Log.d(TAG, "原因: 播放列表改变");
                                break;
                        }
                    }
                }
                updateNotification();
                // 立即广播，确保UI更新标题/作者
                sendSongChangedBroadcast();
            }
        });
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSessionCompat(this, "LiMusic");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                player.seekTo(pos);
            }
        });
        mediaSession.setActive(true);
    }

    private void initializeAudioManager() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusChange -> {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            Log.d(TAG, "获得音频焦点");
                            if (player != null) {
                                player.setVolume(1.0f);
                                if (!player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) {
                                    player.play();
                                }
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            Log.d(TAG, "永久失去音频焦点");
                            releaseAudioFocus();
                            pause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            Log.d(TAG, "暂时失去音频焦点");
                            if (player != null && player.isPlaying()) {
                                pause();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            Log.d(TAG, "暂时降低音量");
                            if (player != null) {
                                player.setVolume(0.3f);
                            }
                            break;
                    }
                })
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .build();
    }

    private boolean requestAudioFocus() {
        if (hasAudioFocus) {
            return true;
        }

        int result = audioManager.requestAudioFocus(audioFocusRequest);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus = true;
            Log.d(TAG, "获得音频焦点");
            return true;
        } else {
            Log.e(TAG, "无法获得音频焦点");
            return false;
        }
    }

    private void releaseAudioFocus() {
        if (hasAudioFocus) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            hasAudioFocus = false;
            Log.d(TAG, "释放音频焦点");
        }
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        
        long position = player != null ? player.getCurrentPosition() : 0;
        
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY |
                            PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(player != null && player.isPlaying() ? PlaybackStateCompat.STATE_PLAYING :
                          PlaybackStateCompat.STATE_PAUSED, position, 1.0f);
        
        mediaSession.setPlaybackState(stateBuilder.build());
        updateNotification();
        
        // UI更新逻辑移至单独方法
    }
    
    // 发送广播，只包含必要的信息
    private void sendPlaybackStateBroadcast() {
        if (player == null) return;
        
        Intent intent = new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED");
        intent.putExtra("isPlaying", player.isPlaying());
        intent.putExtra("position", player.getCurrentPosition());
        intent.putExtra("duration", player.getDuration());
        intent.putExtra("playbackMode", playbackMode);
        
        // 只在歌曲切换或特定事件时才发送标题和艺术家信息
        if (currentSong != null) {
            // 使用额外的标志来标记是否需要更新标题
            boolean isSongChanged = intent.getBooleanExtra("songChanged", false);
            
            if (isSongChanged) {
                intent.putExtra("title", currentSong.getTitle());
                intent.putExtra("artist", currentSong.getArtist());
                intent.putExtra("albumId", currentSong.getAlbumId());
            }
        }
        
        sendBroadcast(intent);
    }
    
    // 在歌曲变化时调用此方法
    private void sendSongChangedBroadcast() {
        if (player == null || currentSong == null) return;
        
        Intent intent = new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED");
        intent.putExtra("isPlaying", player.isPlaying());
        intent.putExtra("position", player.getCurrentPosition());
        intent.putExtra("duration", player.getDuration());
        intent.putExtra("playbackMode", playbackMode);
        intent.putExtra("songChanged", true);
        intent.putExtra("title", currentSong.getTitle());
        intent.putExtra("artist", currentSong.getArtist());
        intent.putExtra("albumId", currentSong.getAlbumId());
        
        sendBroadcast(intent);
    }
    
    // 用于UI更新的Runnable
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            // 只要UI可见就发送广播，不管是否播放
            if (isUiVisible && player != null) {
                sendPlaybackStateBroadcast();
                // 从1秒改为2秒更新一次，减少系统开销
                handler.postDelayed(this, 2000);
            }
        }
    };

    // 启动UI更新循环
    private void startUiUpdate() {
        // 移除旧的回调，以防重复
        handler.removeCallbacks(uiUpdateRunnable);
        // 立即开始
        handler.post(uiUpdateRunnable);
    }

    // 停止UI更新循环
    private void stopUiUpdate() {
        handler.removeCallbacks(uiUpdateRunnable);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "音乐播放",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("用于音乐播放控制");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void updateNotification() {
        if (currentSong == null) return;

        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentSong.getTitle())
                .setContentText(currentSong.getArtist())
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentIntent(pendingIntent)
                .setOngoing(player.isPlaying())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // 添加播放/暂停操作
        if (player.isPlaying()) {
            builder.addAction(R.drawable.ic_pause, "暂停",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE));
        } else {
            builder.addAction(R.drawable.ic_play, "播放",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY));
        }

        startForeground(NOTIFICATION_ID, builder.build());
    }

    public void play() {
        if (requestAudioFocus()) {
            player.play();
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
            // 确保服务是"已启动"状态，以便可以在后台运行
            startService(new Intent(this, PlayerService.class));
            updateNotification();
            // 立即发送一次更新，确保UI马上响应
            sendPlaybackStateBroadcast();
            startUiUpdate();
        }
    }

    public void pause() {
        player.pause();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(false); // 暂停时，允许通知被划掉
        // 立即发送一次更新，确保UI马上响应
        sendPlaybackStateBroadcast();
        stopUiUpdate();
    }

    public void next() {
        if (playlist.isEmpty()) {
            return;
        }
        
        if (currentIndex < playlist.size() - 1) {
            currentIndex++;
        } else if (playbackMode == PLAYBACK_MODE_REPEAT_ALL) {
            // 在列表循环模式下，回到第一首
            currentIndex = 0;
        } else {
            // 已经是最后一首，不处理
            return;
        }
        
        player.seekTo(currentIndex, 0);
        Log.d(TAG, "播放下一首: 索引 " + currentIndex);
        sendSongChangedBroadcast();
    }

    public void previous() {
        if (playlist.isEmpty()) {
            return;
        }
        
        // 如果当前播放进度超过3秒，则重新播放当前歌曲
        if (player.getCurrentPosition() > 3000) {
            player.seekTo(0);
            return;
        }
        
        if (currentIndex > 0) {
            currentIndex--;
        } else if (playbackMode == PLAYBACK_MODE_REPEAT_ALL && !playlist.isEmpty()) {
            // 列表循环模式下，跳到最后一首
            currentIndex = playlist.size() - 1;
        } else {
            // 已经是第一首，不处理
            return;
        }
        
        player.seekTo(currentIndex, 0);
        Log.d(TAG, "播放上一首: 索引 " + currentIndex);
        sendSongChangedBroadcast();
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public void playSong(String streamUrl, Song song) {
        // 单曲播放模式
        currentSong = song;
        
        // 更新播放列表，只包含当前一首歌
        playlist.clear();
        playlist.add(song);
        currentIndex = 0;
        
        // 创建媒体项
        MediaItem mediaItem = MediaItem.fromUri(streamUrl);
        player.clearMediaItems();
        player.setMediaItem(mediaItem);
        player.prepare();
        
        // 应用当前播放模式
        applyPlaybackMode();
        
        // 开始播放
        play();
        
        // 发送歌曲变化广播
        sendSongChangedBroadcast();
        
        Log.d(TAG, "播放单曲: " + song.getTitle() + ", URL: " + streamUrl);
    }
    
    // 设置并播放整个列表，startIndex为开始播放的索引
    public void setPlaylist(List<Song> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        
        Log.d(TAG, "准备设置播放列表: " + songs.size() + " 首歌曲, 请求索引: " + startIndex + ", 当前播放模式: " + 
              (playbackMode == PLAYBACK_MODE_REPEAT_ALL ? "列表循环" : "单曲循环"));
        
        // 限制加载的歌曲数量，避免内存溢出
        List<Song> limitedSongs = songs;
        if (songs.size() > 200) {
            Log.w(TAG, "歌曲列表过大，限制为200首");
            limitedSongs = songs.subList(0, 200);
        }
        
        // 保存播放列表
        playlist.clear();
        playlist.addAll(limitedSongs);
        currentIndex = Math.min(Math.max(startIndex, 0), limitedSongs.size() - 1);
        currentSong = playlist.get(currentIndex);
        
        Log.d(TAG, "设置当前索引为: " + currentIndex + ", 歌曲: " + currentSong.getTitle());
        
        // 构建媒体项列表
        List<MediaItem> items = new ArrayList<>();
        for (Song song : playlist) {
            String url = NavidromeApi.getInstance(this).getStreamUrl(song.getId());
            items.add(MediaItem.fromUri(url));
        }
        
        // 清除之前的播放列表，添加整个新列表
        player.stop();
        player.clearMediaItems();
        player.setMediaItems(items, currentIndex, /*startPositionMs*/ 0);
        
        // 确保随机模式关闭
        player.setShuffleModeEnabled(false);
        
        // 准备播放器
        player.prepare();
        
        // 应用播放模式（只有列表循环或单曲循环）
        if (playbackMode == PLAYBACK_MODE_REPEAT_ONE) {
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
        } else {
            // 默认为列表循环
            playbackMode = PLAYBACK_MODE_REPEAT_ALL;
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
        }
        
        // 开始播放
        play();
        
        // 发送歌曲变化广播
        sendSongChangedBroadcast();
        
        Log.d(TAG, "设置播放列表: " + playlist.size() + " 首歌曲, 从索引 " + currentIndex + " 开始播放");
    }
    
    // 从播放列表中播放指定索引的歌曲
    private void playSongFromPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) {
            return;
        }
        
        currentIndex = index;
        currentSong = playlist.get(index);
        
        // 不清除MediaItems，只跳转到指定位置
        player.seekTo(currentIndex, 0);
        
        Log.d(TAG, "从列表播放歌曲: " + currentSong.getTitle() + ", 索引: " + currentIndex);
        
        // 如果没有准备好，先准备
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            player.prepare();
        }
        
        play();
    }
    
    // 根据当前播放模式设置ExoPlayer的循环模式和随机状态
    private void applyPlaybackMode() {
        switch (playbackMode) {
            case PLAYBACK_MODE_REPEAT_ALL:
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                player.setShuffleModeEnabled(false);
                Log.d(TAG, "设置播放模式: 列表循环");
                break;
            case PLAYBACK_MODE_REPEAT_ONE:
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                player.setShuffleModeEnabled(false);
                Log.d(TAG, "设置播放模式: 单曲循环");
                break;
            case PLAYBACK_MODE_SHUFFLE:
                // 对于随机播放，我们仍然需要全部循环
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                
                // 保存当前媒体项索引
                int currentItemIndex = player.getCurrentMediaItemIndex();
                
                // 启用随机播放
                player.setShuffleModeEnabled(true);
                
                // 如果播放器已经在播放，确保继续播放当前歌曲
                if (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY) {
                    player.seekTo(currentItemIndex, player.getCurrentPosition());
                }
                
                Log.d(TAG, "设置播放模式: 随机播放，保持当前歌曲索引: " + currentItemIndex);
                break;
        }
    }
    
    public void shufflePlaylist() {
        if (playlist.size() <= 1) return;
        
        // 保存当前歌曲
        Song currentSongBackup = currentSong;
        long currentPosition = player.getCurrentPosition();
        
        // 打乱列表
        List<Song> newPlaylist = new ArrayList<>(playlist);
        Collections.shuffle(newPlaylist);
        
        // 确保当前歌曲在第一位
        if (currentSongBackup != null) {
            newPlaylist.remove(currentSongBackup);
            newPlaylist.add(0, currentSongBackup);
        }
        
        // 设置播放模式为随机播放
        playbackMode = PLAYBACK_MODE_SHUFFLE;
        
        // 清除之前的播放列表，添加整个新列表
        List<MediaItem> items = new ArrayList<>();
        for (Song song : newPlaylist) {
            String url = NavidromeApi.getInstance(this).getStreamUrl(song.getId());
            items.add(MediaItem.fromUri(url));
        }
        
        // 清除播放列表并添加新的
        player.clearMediaItems();
        player.setMediaItems(items);
        
        // 更新播放列表
        playlist.clear();
        playlist.addAll(newPlaylist);
        
        // 设置当前索引为0（当前歌曲）
        currentIndex = 0;
        currentSong = playlist.get(currentIndex);
        
        // 跳转到第一首歌曲（当前歌曲）
        player.seekTo(0, currentPosition);
        
        // 准备播放器
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            player.prepare();
        }
        
        // 设置播放模式
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setShuffleModeEnabled(true);
        
        // 如果之前在播放，则继续播放
        if (player.isPlaying()) {
            player.play();
        }
        
        Log.d(TAG, "打乱播放列表，当前歌曲: " + currentSong.getTitle() + " 移至第一位");
    }

    public String getCurrentTitle() {
        return currentSong != null ? currentSong.getTitle() : "";
    }

    public String getCurrentArtist() {
        return currentSong != null ? currentSong.getArtist() : "";
    }

    public Song getCurrentSong() {
        return currentSong;
    }

    public long getCurrentPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    public void seekTo(long position) {
        if (player != null) {
            player.seekTo(position);
            updatePlaybackState();
        }
    }

    public void togglePlaybackMode() {
        // 保存之前的模式和当前索引
        int previousMode = playbackMode;
        int currentItemIndex = player.getCurrentMediaItemIndex();
        
        // 切换到下一个模式
        playbackMode = (playbackMode + 1) % 3;
        
        // TODO: 随机播放功能暂时禁用，直接跳过
        if (playbackMode == PLAYBACK_MODE_SHUFFLE) {
            playbackMode = PLAYBACK_MODE_REPEAT_ALL;
        }
        
        Log.d(TAG, "切换播放模式: " + previousMode + " -> " + playbackMode);
        
        // 应用播放模式
        if (playbackMode == PLAYBACK_MODE_REPEAT_ALL) {
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.setShuffleModeEnabled(false);
            Log.d(TAG, "切换播放模式: 列表循环");
        } else if (playbackMode == PLAYBACK_MODE_REPEAT_ONE) {
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.setShuffleModeEnabled(false);
            Log.d(TAG, "切换播放模式: 单曲循环");
        }
        
        updatePlaybackState();
    }

    public int getPlaybackMode() {
        return playbackMode;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (player != null) {
            handler.removeCallbacksAndMessages(null);
            player.release();
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        releaseAudioFocus();
        stopForeground(true); // 服务销毁时，移除通知
        stopUiUpdate(); // 服务销毁时，确保停止更新
        Log.d(TAG, "PlayerService销毁");
    }

    // 新增方法，供Activity调用
    public void setUiVisible(boolean visible) {
        this.isUiVisible = visible;
        if (visible) {
            // 当UI变为可见时，立即启动或恢复UI更新
            startUiUpdate();
        } else {
            // 当UI变为不可见时，停止UI更新
            stopUiUpdate();
        }
    }
} 