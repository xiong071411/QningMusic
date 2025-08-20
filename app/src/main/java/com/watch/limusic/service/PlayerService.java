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
import android.content.SharedPreferences;

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
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.watch.limusic.cache.SmartDataSourceFactory;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.util.MimeTypes;

import com.watch.limusic.MainActivity;
import com.watch.limusic.R;
import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.model.Song;
import com.watch.limusic.cache.CacheManager;
import com.watch.limusic.download.LocalFileDetector;
import com.watch.limusic.util.NetworkUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.watch.limusic.database.MusicDatabase;
import com.watch.limusic.database.SongEntity;
import com.watch.limusic.database.EntityConverter;

public class PlayerService extends Service {
    private static final String TAG = "PlayerService";
    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final int PLAYBACK_MODE_REPEAT_ALL = 0;
    public static final int PLAYBACK_MODE_REPEAT_ONE = 1;
    public static final int PLAYBACK_MODE_SHUFFLE = 2;
    public static final String ACTION_PLAYBACK_STATE_CHANGED = "com.watch.limusic.PLAYBACK_STATE_CHANGED";

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
    private LocalFileDetector localFileDetector;
    
    // 已尝试过转码回退的歌曲，避免无限重试
    private final Set<String> transcodingFallbackTried = new HashSet<>();

	// 持久化相关
	private static final String PREFS_NAME = "player_prefs";
	private static final String KEY_SONG_ID = "last_song_id";
	private static final String KEY_TITLE = "last_title";
	private static final String KEY_ARTIST = "last_artist";
	private static final String KEY_ALBUM_ID = "last_album_id";
	private static final String KEY_POSITION = "last_position";
	private static final String KEY_IS_PLAYING = "last_is_playing";
	private static final String KEY_PLAYBACK_MODE = "last_playback_mode";
	private static final String KEY_PLAYLIST_IDS = "last_playlist_ids"; // 逗号分隔的歌曲ID列表
	private static final String KEY_PLAYLIST_INDEX = "last_playlist_index";

	// 诊断相关变量（带宽估计等）
	private long lastBitrateEstimate = -1L;
	private long totalBytesLoaded = 0L;
	private int totalLoadTimeMs = 0;

	// 缓冲策略（快速起播 + 更强的重缓冲门槛 + 更大持续缓冲）
	private static final int MIN_BUFFER_MS = 30_000; // 正常播放时至少维持 30s 缓冲
	private static final int MAX_BUFFER_MS = 120_000; // 最多缓冲 120s
	private static final int BUFFER_FOR_PLAYBACK_MS = 700; // 起播保持快速
	private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000; // 重缓冲后多攒一些再播，减少再卡顿

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

        // 初始化本地文件检测器
        localFileDetector = new LocalFileDetector(this);
        
        // 初始化播放器
        initializePlayer();
        
        // 初始化媒体会话
        initializeMediaSession();
        
        // 初始化音频管理器
        initializeAudioManager();
        
        // 获取唤醒锁
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiMusic:WakeLock");
        
        // 恢复上次播放状态（若有）
        restorePlaybackStateIfAvailable();
    }

    private void initializePlayer() {
        Context context = getApplicationContext();
        
		// 创建数据源工厂：本地文件直读、网络走缓存
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(
				new SmartDataSourceFactory(context)
        );
        
        // 创建轨道选择器
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        
        // 构建渲染器工厂：优先使用扩展解码器（如FLAC），并允许在解码器失败时回退
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true);
        
        // 构建播放器
		DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
				.setBufferDurationsMs(
					MIN_BUFFER_MS,
					MAX_BUFFER_MS,
					BUFFER_FOR_PLAYBACK_MS,
					BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
				.setPrioritizeTimeOverSizeThresholds(true)
				.build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
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
						logPlaybackDiagnostics("STATE_READY");
                        break;
                    case Player.STATE_BUFFERING:
                        Log.d(TAG, "正在缓冲");
						logPlaybackDiagnostics("STATE_BUFFERING");
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
				Log.e(TAG, "播放错误: code=" + error.errorCode + ", name=" + error.getErrorCodeName() + ", msg=" + error.getMessage());
                error.printStackTrace();
				logPlaybackDiagnostics("PLAYER_ERROR");

                // 针对FLAC硬解异常：优先尝试一次回退为服务器转码MP3（原位替换，保留播放列表与索引）
                try {
                    if (currentSong != null) {
                        String songId = currentSong.getId();
                        if (!transcodingFallbackTried.contains(songId)) {
                            transcodingFallbackTried.add(songId);
                            long resumePos = player != null ? Math.max(player.getCurrentPosition(), 0) : 0;
                            String fallback = navidromeApi.getTranscodedStreamUrl(songId, "mp3", 192);
                            MediaItem item = buildStreamingMediaItem(songId, fallback);
                            int idx = player.getCurrentMediaItemIndex();
                            if (idx >= 0 && idx < player.getMediaItemCount()) {
                                int count = player.getMediaItemCount();
                                List<MediaItem> currentItems = new ArrayList<>(count);
                                for (int i = 0; i < count; i++) {
                                    currentItems.add(player.getMediaItemAt(i));
                                }
                                currentItems.set(idx, item);
                                player.setMediaItems(currentItems, idx, resumePos);
                                player.prepare();
                                play();
                                Log.w(TAG, "检测到解码异常，已回退为转码MP3重试(原位替换): " + fallback);
                                return;
                            } else {
                                player.setMediaItem(item, resumePos);
                                player.prepare();
                                play();
                                Log.w(TAG, "检测到解码异常，已回退为转码MP3重试(单项替换): " + fallback);
                                return;
                            }
                        } else {
                            // 已尝试过回退仍失败：跳到下一首，避免死循环
                            Log.e(TAG, "转码回退仍失败，跳过当前歌曲: " + currentSong.getTitle());
                            next();
                            return;
                        }
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "回退转码时发生异常", ex);
                }
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
                sendPlaybackStateBroadcast();
            }
        });

		// 添加Analytics监听器，记录带宽估计与加载错误
		player.addAnalyticsListener(new AnalyticsListener() {
			@Override
			public void onBandwidthEstimate(EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
				PlayerService.this.totalLoadTimeMs = totalLoadTimeMs;
				PlayerService.this.totalBytesLoaded = totalBytesLoaded;
				PlayerService.this.lastBitrateEstimate = bitrateEstimate;
				Log.d(TAG, "带宽估计: bitrate=" + (bitrateEstimate / 1000) + " kbps, bytes=" + totalBytesLoaded + ", timeMs=" + totalLoadTimeMs);
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
                previousInternal(true);
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
        
        // 保存当前播放状态到本地，便于下次恢复
        persistPlaybackStateSafely();
    }
    
    // 发送广播，只包含必要的信息
    private void sendPlaybackStateBroadcast() {
        if (player == null) return;
        
        Intent intent = new Intent(ACTION_PLAYBACK_STATE_CHANGED);
        intent.putExtra("isPlaying", player.isPlaying());
        intent.putExtra("position", player.getCurrentPosition());
        intent.putExtra("duration", player.getDuration());
        intent.putExtra("playbackMode", playbackMode);
        if (currentSong != null) {
            intent.putExtra("title", currentSong.getTitle());
            intent.putExtra("artist", currentSong.getArtist());
            intent.putExtra("albumId", currentSong.getAlbumId());
        }
        sendBroadcast(intent);
    }

	// 将当前播放器/网络/缓存状态打印出来，辅助排查卡顿
	private void logPlaybackDiagnostics(String reason) {
		try {
			if (player == null) return;
			boolean netAvailable = NetworkUtils.isNetworkAvailable(this);
			boolean isWifi = NetworkUtils.isWifiNetwork(this);
			long position = player.getCurrentPosition();
			long duration = player.getDuration();
			long buffered = player.getBufferedPosition();
			int bufferedPercent = player.getBufferedPercentage();
			String url = null;
			boolean fromFile = false;
			boolean cached = false;
			if (currentSong != null) {
				String streamUrl = NavidromeApi.getInstance(this).getStreamUrl(currentSong.getId());
				url = getOptimalPlayUrl(currentSong, streamUrl);
				fromFile = url != null && url.startsWith("file://");
				if (!fromFile) {
					// 以稳定的自定义缓存键(songId)判断是否已缓存
					cached = CacheManager.getInstance(this).isCachedByKey(currentSong.getId());
				}
			}
			long cacheUsed = CacheManager.getInstance(this).getCacheUsageBytes();
			long cacheMax = CacheManager.getInstance(this).getMaxCacheBytes();

			Log.d(TAG, "诊断[" + reason + "]: state=" + player.getPlaybackState() + ", isPlaying=" + player.isPlaying());
			if (currentSong != null) {
				Log.d(TAG, "歌曲: " + currentSong.getTitle() + " - " + currentSong.getArtist() + ", 索引=" + currentIndex + "/" + playlist.size());
			}
			Log.d(TAG, "进度: pos=" + position + "ms, dur=" + duration + "ms, bufPos=" + buffered + "ms(" + bufferedPercent + "%)");
			Log.d(TAG, "网络: available=" + netAvailable + ", wifi=" + isWifi + ", 估算码率=" + (lastBitrateEstimate >= 0 ? (lastBitrateEstimate / 1000) + " kbps" : "N/A"));
			if (url != null) {
				Log.d(TAG, "来源: " + (fromFile ? "本地文件" : "流媒体") + ", url=" + url + ", 已缓存=" + cached);
			}
			Log.d(TAG, "缓存: used=" + cacheUsed + "/" + cacheMax + " bytes");
		} catch (Exception e) {
			Log.e(TAG, "打印诊断信息失败", e);
		}
	}
    
    // 用于UI更新的Runnable
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            // 只要UI可见就发送广播，不管是否播放
            if (isUiVisible && player != null) {
                sendPlaybackStateBroadcast();
                handler.postDelayed(this, 1000);
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
			logPlaybackDiagnostics("play()");
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
        sendPlaybackStateBroadcast();
    }

    public void previous() {
        previousInternal(false);
    }

    private void previousInternal(boolean forceToPrevious) {
        if (playlist.isEmpty()) {
            return;
        }

        // 耳机上一首：强制切到上一首；非强制时保留“>3s回到本曲起点”的体验
        if (!forceToPrevious && player.getCurrentPosition() > 3000) {
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
        Log.d(TAG, "播放上一首: 索引 " + currentIndex + (forceToPrevious ? " (forced)" : ""));
        sendPlaybackStateBroadcast();
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

        // 优先使用下载的文件
        String playUrl = getOptimalPlayUrl(song, streamUrl);

        // 创建媒体项
		MediaItem mediaItem = buildStreamingMediaItem(song.getId(), playUrl);
        player.clearMediaItems();
        player.setMediaItem(mediaItem);
        player.prepare();

        // 应用当前播放模式
        applyPlaybackMode();

        // 开始播放
        play();

        Log.d(TAG, "播放单曲: " + song.getTitle() + ", URL: " + playUrl);
    }

    /**
     * 获取最优播放URL - 优先使用下载文件，然后是流媒体
     */
    private String getOptimalPlayUrl(Song song, String streamUrl) {
        // 首先检查是否有下载的文件
        String downloadedPath = localFileDetector.getDownloadedSongPath(song.getId());
        if (downloadedPath != null) {
            Log.d(TAG, "使用下载文件播放: " + song.getTitle() + " -> " + downloadedPath);
            return "file://" + downloadedPath;
        }

        // 是否强制转码（设置页开关）：开启后，统一请求 mp3@320kbps 转码，避免硬件兼容差异
        try {
            SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
            boolean forceTrans = sp.getBoolean("force_transcode_non_mp3", false);
            if (forceTrans) {
                String forced = navidromeApi.getTranscodedStreamUrl(song.getId(), "mp3", 320);
                Log.d(TAG, "强制转码启用：统一请求 mp3@320kbps -> " + forced);
                return forced;
            }
        } catch (Exception ignore) {}

        // 针对手表端FLAC硬解码器不稳定：为FLAC优先构造转码URL回退
        try {
            boolean looksLikeFlac = false;
            if (streamUrl != null) {
                String lower = streamUrl.toLowerCase();
                looksLikeFlac = lower.contains("format=flac") || lower.endsWith(".flac") || lower.contains("audio/flac");
            }
            if (looksLikeFlac) {
                String transcoded = navidromeApi.getTranscodedStreamUrl(song.getId(), "mp3", 192);
                Log.d(TAG, "FLAC回退为转码MP3播放: " + song.getTitle() + " -> " + transcoded);
                return transcoded;
            }
        } catch (Exception ignore) {}

        // 如果没有下载文件，使用流媒体URL
        Log.d(TAG, "使用流媒体播放: " + song.getTitle() + " -> " + streamUrl);
        return streamUrl;
    }
    
    // 设置并播放整个列表，startIndex为开始播放的索引
    public void setPlaylist(List<Song> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        
        Log.d(TAG, "准备设置播放列表: " + songs.size() + " 首歌曲, 请求索引: " + startIndex + ", 当前播放模式: " + 
              (playbackMode == PLAYBACK_MODE_REPEAT_ALL ? "列表循环" : "单曲循环"));
        
        // 记录用户点击的歌曲
        Song clickedSong = null;
        if (startIndex >= 0 && startIndex < songs.size()) {
            clickedSong = songs.get(startIndex);
        }
        
        // 检查网络状态
        boolean isNetworkAvailable = NetworkUtils.isNetworkAvailable(this);
        
        // 保存播放列表，如果是离线模式，只保留已缓存的歌曲
        playlist.clear();
        
        if (!isNetworkAvailable) {
            // 离线模式：优先使用已下载的歌曲，然后是已缓存的歌曲
            List<Song> availableSongs = new ArrayList<>();
            for (Song song : songs) {
                // 首先检查是否已下载
                if (localFileDetector.isSongDownloaded(song)) {
                    availableSongs.add(song);
                    Log.d(TAG, "离线模式：添加已下载歌曲到播放列表: " + song.getTitle());
                } else if (CacheManager.getInstance(this).isCached(NavidromeApi.getInstance(this).getStreamUrl(song.getId()))) {
                    // 如果没有下载，检查是否已缓存
                    availableSongs.add(song);
                    Log.d(TAG, "离线模式：添加已缓存歌曲到播放列表: " + song.getTitle());
                }
            }

            if (availableSongs.isEmpty()) {
                Log.w(TAG, "离线模式下没有找到可播放的歌曲（已下载或已缓存），无法设置播放列表");
                return;
            }

            // 在过滤后的列表中定位用户点击的歌曲
            int filteredIndex = 0;
            if (clickedSong != null) {
                filteredIndex = -1;
                for (int i = 0; i < availableSongs.size(); i++) {
                    if (availableSongs.get(i).getId().equals(clickedSong.getId())) {
                        filteredIndex = i;
                        break;
                    }
                }
                if (filteredIndex == -1) {
                    // 用户点击的歌曲不在可用列表中，提示并返回
                    Log.w(TAG, "离线模式下，所选歌曲不可用（未下载/未缓存）");
                    return;
                }
            }

            playlist.addAll(availableSongs);
            Log.d(TAG, "离线模式：过滤后的播放列表包含 " + playlist.size() + " 首可播放歌曲");

            // 使用过滤后的索引开始播放
            startIndex = filteredIndex;
        } else {
            // 在线模式：使用完整列表
            playlist.addAll(songs);
        }
        
        currentIndex = Math.min(Math.max(startIndex, 0), playlist.size() - 1);
        currentSong = playlist.get(currentIndex);
        
        Log.d(TAG, "设置当前索引为: " + currentIndex + ", 歌曲: " + currentSong.getTitle());
        
        // 构建媒体项列表
        List<MediaItem> items = new ArrayList<>();
        for (Song song : playlist) {
            String streamUrl = NavidromeApi.getInstance(this).getStreamUrl(song.getId());
            String optimalUrl = getOptimalPlayUrl(song, streamUrl);
			items.add(buildStreamingMediaItem(song.getId(), optimalUrl));
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
			items.add(buildStreamingMediaItem(song.getId(), url));
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
		// 确保模式变化被持久化
		persistPlaybackStateSafely();
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
		// 退出时保存一次播放状态
		persistPlaybackStateSafely();
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

	// 安全地持久化当前播放状态
	private void persistPlaybackStateSafely() {
		try {
			SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
			SharedPreferences.Editor e = prefs.edit();
			if (currentSong != null) {
				e.putString(KEY_SONG_ID, currentSong.getId());
				e.putString(KEY_TITLE, currentSong.getTitle());
				e.putString(KEY_ARTIST, currentSong.getArtist());
				e.putString(KEY_ALBUM_ID, currentSong.getAlbumId());
			} else {
				e.remove(KEY_SONG_ID);
				e.remove(KEY_TITLE);
				e.remove(KEY_ARTIST);
				e.remove(KEY_ALBUM_ID);
			}
			long pos = player != null ? player.getCurrentPosition() : 0L;
			e.putLong(KEY_POSITION, Math.max(pos, 0));
			e.putBoolean(KEY_IS_PLAYING, player != null && player.isPlaying());
			e.putInt(KEY_PLAYBACK_MODE, playbackMode);
			// 保存播放列表（仅ID与索引）
			if (playlist != null && !playlist.isEmpty() && currentIndex >= 0 && currentIndex < playlist.size()) {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < playlist.size(); i++) {
					if (i > 0) sb.append(',');
					sb.append(playlist.get(i).getId());
				}
				e.putString(KEY_PLAYLIST_IDS, sb.toString());
				e.putInt(KEY_PLAYLIST_INDEX, currentIndex);
			} else {
				e.remove(KEY_PLAYLIST_IDS);
				e.remove(KEY_PLAYLIST_INDEX);
			}
			e.apply();
		} catch (Exception ex) {
			Log.e(TAG, "保存播放状态失败", ex);
		}
	}

	// 恢复上次播放状态（若有）
	private void restorePlaybackStateIfAvailable() {
		try {
			SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
			String songId = prefs.getString(KEY_SONG_ID, null);
			if (songId == null || songId.isEmpty()) {
				Log.d(TAG, "没有可恢复的播放状态");
				return;
			}
			String title = prefs.getString(KEY_TITLE, "");
			String artist = prefs.getString(KEY_ARTIST, "");
			String albumId = prefs.getString(KEY_ALBUM_ID, "");
			long position = prefs.getLong(KEY_POSITION, 0L);
			int savedMode = prefs.getInt(KEY_PLAYBACK_MODE, PLAYBACK_MODE_REPEAT_ALL);
			// 始终以暂停状态恢复
			boolean wasPlaying = false;

			// 恢复播放列表
			String idList = prefs.getString(KEY_PLAYLIST_IDS, null);
			int savedIndex = prefs.getInt(KEY_PLAYLIST_INDEX, -1);
			List<Song> restoredList = new ArrayList<>();
			if (idList != null && !idList.isEmpty()) {
				String[] ids = idList.split(",");
				// 尝试从数据库还原每首歌的信息
				MusicDatabase db = MusicDatabase.getInstance(this);
				for (String id : ids) {
					SongEntity se = db.songDao().getSongById(id);
					if (se != null) {
						restoredList.add(EntityConverter.toSong(se));
					} else {
						// 数据库没有命中时，降级用最小 Song，仅含必要字段
						Song s = new Song(id, "", "", "", null, navidromeApi.getStreamUrl(id), 0);
						restoredList.add(s);
					}
				}
			}

			this.playbackMode = savedMode;
			applyPlaybackMode();

			if (!restoredList.isEmpty() && savedIndex >= 0 && savedIndex < restoredList.size()) {
				// 有列表且索引有效：按列表恢复
				this.playlist.clear();
				this.playlist.addAll(restoredList);
				this.currentIndex = savedIndex;
				this.currentSong = playlist.get(currentIndex);
				// 重建媒体项
				List<MediaItem> items = new ArrayList<>();
				for (Song s : playlist) {
					String optimalUrl = getOptimalPlayUrl(s, navidromeApi.getStreamUrl(s.getId()));
					items.add(buildStreamingMediaItem(s.getId(), optimalUrl));
				}
				player.setMediaItems(items, currentIndex, /*startPositionMs*/ Math.max(position, 0));
				player.prepare();
			} else {
				// 仅恢复单曲
				Song restored = new Song(songId, title, artist, /*album*/ "", /*coverArt*/ null,
					/*streamUrl*/ navidromeApi.getStreamUrl(songId), /*duration*/ 0);
				restored.setAlbumId(albumId);
				this.currentSong = restored;
				String optimalUrl = getOptimalPlayUrl(restored, navidromeApi.getStreamUrl(songId));
				MediaItem item = buildStreamingMediaItem(restored.getId(), optimalUrl);
				player.setMediaItem(item);
				player.prepare();
				if (position > 0) player.seekTo(position);
			}

			// 始终暂停启动，等待用户主动播放
			player.pause();
			updatePlaybackState();
			Log.d(TAG, "已恢复上次播放状态(含列表): ids=" + (idList != null ? idList.length() : 0) + ", index=" + savedIndex + ", pos=" + position + ", mode=" + savedMode + ", playing=false");
		} catch (Exception ex) {
			Log.e(TAG, "恢复播放状态失败", ex);
		}
	}

    private MediaItem buildStreamingMediaItem(String songId, String url) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        // 根据URL是否为转码MP3设置MIME与自定义缓存键，避免与原始FLAC缓存混用
        String lower = url != null ? url.toLowerCase() : "";
        if (lower.contains("format=mp3")) {
            b.setMimeType(MimeTypes.AUDIO_MPEG)
             .setCustomCacheKey("stream_mp3_" + songId);
        } else {
            b.setCustomCacheKey("stream_raw_" + songId);
        }
        return b.build();
    }
} 