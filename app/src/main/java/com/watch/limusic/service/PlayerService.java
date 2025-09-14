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
import android.content.IntentFilter;
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
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.Format;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.HashMap;
import java.util.Map;

import com.watch.limusic.database.MusicDatabase;
import com.watch.limusic.database.SongEntity;
import com.watch.limusic.database.EntityConverter;
import com.watch.limusic.database.MusicRepository;
import com.watch.limusic.audio.AudioLevelBus;
import com.watch.limusic.audio.AudioTapProcessor;


public class PlayerService extends Service {
	private android.content.BroadcastReceiver configUpdatedReceiver;
    private static final String TAG = "PlayerService";
    private static final boolean DEBUG_VERBOSE = false; // 设为true可观察详细窗口/随机日志
    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int FULL_INJECT_THRESHOLD = 120; // 小列表全量注入阈值，扩大随机范围
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
	// 全局"所有歌曲"滑动窗口持久化
	private static final String KEY_GLOBAL_ALL_SONGS = "last_global_all_songs";
	private static final String KEY_GLOBAL_INDEX = "last_global_index";

	// 诊断相关变量（带宽估计等）
	private long lastBitrateEstimate = -1L;
	private long totalBytesLoaded = 0L;
	private int totalLoadTimeMs = 0;

	// 新增：后台IO执行器与时长缓存，避免主线程阻塞
	private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override public Thread newThread(Runnable r) {
			return new Thread(() -> {
				try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignore) {}
				r.run();
			}, "PlayerService-IO");
		}
	});
	private final Map<String, Long> durationCacheMs = new HashMap<>();
	
	// 维护 ExoPlayer 媒体列表与全量播放列表的索引基准：player.mediaIndex=0 对应的全量 playlist 索引
	private int mediaBasePlaylistIndex = 0;

	// 轻量缓存设置项，减少频繁读取SharedPreferences
	private volatile Boolean cachedForceTranscode = null;
	private long cachedForceTranscodeAtMs = 0L;
	private static final long SETTINGS_TTL_MS = 30_000L;

    // ====== 全局"所有歌曲"滑动窗口播放支持 ======
    private boolean useGlobalAllSongsMode = false;
    private int globalTotalCount = 0;          // 数据库中的"所有歌曲"总数
    private int windowStart = 0;               // 当前已注入播放器的全局起始索引（包含）
    private int windowEnd = 0;                 // 当前已注入播放器的全局结束索引（不包含）
    private static final int WINDOW_CHUNK = 60; // 每次扩边块大小
    private static final int WINDOW_GUARD = 4;  // 触发扩边的临界保护区
    private static final int WINDOW_MAX = 240;  // 窗口最大媒体项数量，超过后回收远端块
    private MusicRepository musicRepository;
    // 随机播放支持
    private final java.util.Random shuffleRandom = new java.util.Random();
    private final java.util.ArrayDeque<Integer> shuffleHistory = new java.util.ArrayDeque<>(64);

	// 缓冲策略（快速起播 + 更强的重缓冲门槛 + 更大持续缓冲）
	private static final int MIN_BUFFER_MS = 30_000; // 正常播放时至少维持 30s 缓冲
	private static final int MAX_BUFFER_MS = 120_000; // 最多缓冲 120s
	private static final int BUFFER_FOR_PLAYBACK_MS = 700; // 起播保持快速
	private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000; // 重缓冲后多攒一些再播，减少再卡顿

    // 睡眠定时（低开销）
    public enum SleepType { NONE, AFTER_MS, AFTER_CURRENT }
    private SleepType sleepType = SleepType.NONE;
    private Runnable sleepRunnable = null;
    private long sleepDeadlineElapsedMs = 0L;
    private boolean sleepWaitFinishOnExpire = false;
    public static final String ACTION_SLEEP_TIMER_CHANGED = "com.watch.limusic.SLEEP_TIMER_CHANGED";
    private static final String ACTION_SLEEP_ALARM = "com.watch.limusic.SLEEP_ALARM";
    private android.app.AlarmManager alarmManager;
    private android.app.PendingIntent sleepAlarmIntent;
    private final android.content.BroadcastReceiver sleepAlarmReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (intent == null || !ACTION_SLEEP_ALARM.equals(intent.getAction())) return;
            onSleepAlarmFired();
        }
    };

    // 非全局列表：随机"回到起点自动重洗"支持
    private String shuffleAnchorSongId = null;
    private boolean shuffleVisitedNonAnchor = false;

    // 非全局列表：窗口化扩边与首尾环绕支持（不修改 dataset，仅操作播放器队列）
    private int localBaseLenAtHeadAppend = -1;
    private int localAppendedHeadCount = 0;

    private AudioTapProcessor audioTapProcessor;

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
        try { musicRepository = MusicRepository.getInstance(this); } catch (Throwable ignore) {}
        // 新增：初始化音频可视化总线
        try { AudioLevelBus.init(getApplicationContext()); } catch (Throwable ignore) {}

        // 注册配置更新广播：立即切换到新服务器
        try {
            configUpdatedReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (!com.watch.limusic.api.NavidromeApi.ACTION_NAVIDROME_CONFIG_UPDATED.equals(intent.getAction())) return;
                    try { navidromeApi.reloadCredentials(); } catch (Exception ignore) {}
                    try { if (player != null) { player.stop(); player.clearMediaItems(); } } catch (Exception ignore) {}
                    currentSong = null;
                    mediaItems.clear();
                    playlist.clear();
                    transcodingFallbackTried.clear();
                    try { updatePlaybackState(); } catch (Exception ignore) {}
                }
            };
            registerReceiver(configUpdatedReceiver, new IntentFilter(com.watch.limusic.api.NavidromeApi.ACTION_NAVIDROME_CONFIG_UPDATED));
        } catch (Exception ignore) {}

        // 初始化本地文件检测器
        localFileDetector = new LocalFileDetector(this);
        
        // 初始化播放器
        initializePlayer();
        
        // 初始化媒体会话
        initializeMediaSession();
        
        // 初始化音频管理器
        initializeAudioManager();
        
        // 初始化闹钟管理与注册接收器
        try {
            alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ACTION_SLEEP_ALARM).setPackage(getPackageName());
            sleepAlarmIntent = android.app.PendingIntent.getBroadcast(this, 1001, i, android.os.Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            registerReceiver(sleepAlarmReceiver, new IntentFilter(ACTION_SLEEP_ALARM));
        } catch (Throwable ignore) {}
        
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
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
            @Override
            protected com.google.android.exoplayer2.audio.AudioSink buildAudioSink(Context context,
                                                                                  boolean enableFloatOutput,
                                                                                  boolean enableAudioTrackPlaybackParams,
                                                                                  boolean enableOffload) {
                // 基于默认AudioSink构建，并插入我们的AudioProcessor
                com.google.android.exoplayer2.audio.AudioSink defaultSink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams, enableOffload);
                try {
                    java.util.ArrayList<com.google.android.exoplayer2.audio.AudioProcessor> ps = new java.util.ArrayList<>();
                    audioTapProcessor = new com.watch.limusic.audio.AudioTapProcessor();
                    ps.add(audioTapProcessor);
                    return new com.google.android.exoplayer2.audio.DefaultAudioSink.Builder()
                            .setAudioProcessors(ps.toArray(new com.google.android.exoplayer2.audio.AudioProcessor[0]))
                            .build();
                } catch (Throwable t) {
                    return defaultSink;
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
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
                        try { if (audioTapProcessor != null) audioTapProcessor.setPlaying(player != null && player.isPlaying()); } catch (Throwable ignore) {}
                        updatePlaybackState();
                        logPlaybackDiagnostics("STATE_READY");
                        // 补发一次心跳广播，确保前台/未绑定场景也能拿到正确的时长与进度
                        sendPlaybackStateBroadcast();
                        // 额外延迟补发一帧，增大拿到有效duration的概率（轻量，不影响性能）
                        handler.postDelayed(new Runnable() { @Override public void run() { sendPlaybackStateBroadcast();
                            // 若处于"播完本首后暂停"，且此前时长未知，这里重算一次剩余并覆盖定时
                            if (sleepType == SleepType.AFTER_CURRENT) {
                                try {
                                    long remain = computeRemainMsSafe();
                                    if (sleepRunnable != null) {
                                        handler.removeCallbacks(sleepRunnable);
                                        sleepDeadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + Math.max(0, remain);
                                        handler.postDelayed(sleepRunnable, Math.max(0, sleepDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()));
                                    }
                                } catch (Throwable ignore) {}
                            }
                        } }, 250);
                        // 附带一次 AudioSessionId 变化广播，便于可视化绑定
                        try { sendAudioSessionBroadcast(); } catch (Throwable ignore) {}
                        break;
                    case Player.STATE_BUFFERING:
                        Log.d(TAG, "正在缓冲");
                        try { if (audioTapProcessor != null) audioTapProcessor.setPlaying(false); } catch (Throwable ignore) {}
                        logPlaybackDiagnostics("STATE_BUFFERING");
                        break;
                    case Player.STATE_ENDED:
                        Log.d(TAG, "播放结束");
                        try { if (audioTapProcessor != null) audioTapProcessor.setPlaying(false); } catch (Throwable ignore) {}
                        updatePlaybackState();
                        // 依赖 ExoPlayer 自身的 RepeatMode 处理循环，避免手动 seek 导致索引错乱
                        break;
                    case Player.STATE_IDLE:
                        Log.d(TAG, "播放器空闲");
                        try { if (audioTapProcessor != null) audioTapProcessor.setPlaying(false); } catch (Throwable ignore) {}
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlaybackState();
                try { if (audioTapProcessor != null) audioTapProcessor.setPlaying(isPlaying); } catch (Throwable ignore) {}
                try { sendPlaybackStateBroadcast(); } catch (Throwable ignore) {}
                try { sendAudioSessionBroadcast(); } catch (Throwable ignore) {}
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
                            String fallback = navidromeApi.getTranscodedStreamUrl(songId, "mp3", 320);
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
                    // 将播放器媒体索引映射为全量播放列表索引（全局模式下支持取模包络）
                    int mappedIndex = mediaBasePlaylistIndex + Math.max(0, newIndex);
                    if (useGlobalAllSongsMode && globalTotalCount > 0) {
                        mappedIndex = ((mappedIndex % globalTotalCount) + globalTotalCount) % globalTotalCount;
                    }
                    if (mappedIndex >= 0 && (!useGlobalAllSongsMode ? (mappedIndex < playlist.size()) : (globalTotalCount > 0))) {
                        currentIndex = mappedIndex;
                        // 在全局模式下，将全局索引映射到当前窗口的相对索引
                        Song mappedSong = null;
                        if (useGlobalAllSongsMode) {
                            try {
                                int baseLen = Math.max(0, windowEnd - windowStart);
                                int appendedHead = Math.max(0, playlist.size() - baseLen);
                                int rel;
                                if (mappedIndex >= windowStart && mappedIndex < windowEnd) {
                                    rel = mappedIndex - windowStart;
                                } else if (appendedHead > 0 && mappedIndex >= 0 && mappedIndex < appendedHead) {
                                    // 末尾追加了头块，mappedIndex 位于头块区
                                    rel = baseLen + mappedIndex;
                                } else {
                                    rel = -1;
                                }
                                if (rel >= 0 && rel < playlist.size()) {
                                    mappedSong = playlist.get(rel);
                                } else if (musicRepository != null) {
                                    // 兜底：直接从DB按全局索引拉取该首
                                    java.util.List<Song> one = musicRepository.getSongsRange(1, Math.max(0, mappedIndex));
                                    if (one != null && !one.isEmpty()) mappedSong = one.get(0);
                                }
                            } catch (Throwable ignore) {}
                        } else {
                            // 普通模式：playlist 即窗口列表，索引直接可用
                            try { mappedSong = playlist.get(currentIndex); } catch (Throwable ignore) {}
                        }
                        if (mappedSong != null) {
                            currentSong = mappedSong;
                        }
                        Log.d(TAG, "切换到歌曲: " + (currentSong != null ? currentSong.getTitle() : "?") + ", 索引: " + currentIndex);
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
                        // 切歌时补发一次广播，避免未绑定场景下UI停在0:00
                        sendPlaybackStateBroadcast();
                        // AFTER_CURRENT：自动切曲或单曲重复时立即暂停
                        if (sleepType == SleepType.AFTER_CURRENT) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                                pauseAndClearSleep();
                                return;
                            }
                        }
                        // 额外延迟补发一帧，增大拿到有效duration的概率（轻量，不影响性能）
                        handler.postDelayed(new Runnable() { @Override public void run() { sendPlaybackStateBroadcast(); } }, 250);
                        // 非全局 + 随机：回到起点自动重洗
                        if (!useGlobalAllSongsMode && playbackMode == PLAYBACK_MODE_SHUFFLE) {
                            try {
                                String curId = currentSong != null ? currentSong.getId() : null;
                                if (shuffleAnchorSongId == null) {
                                    shuffleAnchorSongId = curId;
                                    shuffleVisitedNonAnchor = false;
                                } else {
                                    if (curId != null && !curId.equals(shuffleAnchorSongId)) {
                                        shuffleVisitedNonAnchor = true;
                                    } else if (curId != null && curId.equals(shuffleAnchorSongId) && shuffleVisitedNonAnchor) {
                                        // 命中"回到起点"：重洗一次，但保持当前项不变
                                        int keep = player.getCurrentMediaItemIndex();
                                        long pos = Math.max(0, player.getCurrentPosition());
                                                                               // 强制重置随机顺序
                                       rerollLocalShuffleOrderKeepingCurrent();
                                       // 重置新一轮的锚点
                                       shuffleAnchorSongId = curId;
                                       shuffleVisitedNonAnchor = false;
                                        if (DEBUG_VERBOSE) Log.d(TAG, "随机：回到起点，已自动重洗并保持当前曲目");
                                    }
                                }
                            } catch (Throwable ignore) {}
                        }
                        // 全局模式：在临界处按需扩边
                        if (useGlobalAllSongsMode) {
                            try {
                                // 若已追加了头块且当前已进入头块区域，则将队列前部（基础窗口）裁剪掉，使队头对齐全局索引0
                                int baseLen = Math.max(0, windowEnd - windowStart);
                                int appendedHead = Math.max(0, playlist.size() - baseLen);
                                int cur = player.getCurrentMediaItemIndex();
                                if (appendedHead > 0 && cur >= baseLen && baseLen > 0) {
                                    int removeFront = baseLen;
                                    windowStart = 0;
                                    windowEnd = appendedHead;
                                    mediaBasePlaylistIndex = windowStart;
                                    try {
                                        if (removeFront <= playlist.size()) {
                                            playlist.subList(0, removeFront).clear();
                                        } else {
                                            playlist.clear();
                                        }
                                    } catch (Throwable ignore) {}
                                    final int rf = removeFront;
                                    handler.post(() -> { try { player.removeMediaItems(0, rf); } catch (Throwable ignore) {} });
                                }
                                expandWindowIfNeeded(newIndex);
                            } catch (Throwable t) { Log.w(TAG, "扩边失败: " + t.getMessage()); }
                        }
                    }
                }
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
        try { intent.putExtra("audioSessionId", player.getAudioSessionId()); } catch (Throwable ignore) {}
        long dur = player.getDuration();
        intent.putExtra("duration", dur);
        intent.putExtra("isDurationUnset", (dur <= 0 || dur == com.google.android.exoplayer2.C.TIME_UNSET));
        intent.putExtra("playbackMode", playbackMode);
        intent.putExtra("isSeekable", player.isCurrentMediaItemSeekable());
        // 音频类型（优先用Tracks.sampleMimeType识别）
        try {
            String audioType = detectCurrentAudioType();
            if (audioType == null) {
                com.google.android.exoplayer2.MediaItem ci = player.getCurrentMediaItem();
                if (ci != null && ci.localConfiguration != null) {
                    String mime = ci.localConfiguration.mimeType;
                    if (mime != null) {
                        String m = mime.toLowerCase();
                        if (m.contains("flac")) audioType = "FLAC"; else if (m.contains("mpeg") || m.contains("mp3")) audioType = "MP3";
                    }
                    if (audioType == null) {
                        String key = ci.localConfiguration.customCacheKey;
                        if (key != null) {
                            if (key.contains("_flac_")) audioType = "FLAC"; else if (key.contains("_mp3_")) audioType = "MP3";
                        }
                    }
                }
            }
            if (audioType != null) intent.putExtra("audioType", audioType);
        } catch (Throwable ignore) {}
        if (currentSong != null) {
            intent.putExtra("songId", currentSong.getId());
            intent.putExtra("title", currentSong.getTitle());
            intent.putExtra("artist", currentSong.getArtist());
            intent.putExtra("albumId", currentSong.getAlbumId());
        }
        // 若时长未知，尝试使用内存缓存兜底（避免主线程查库）
        if ((dur <= 0 || dur == com.google.android.exoplayer2.C.TIME_UNSET) && currentSong != null) {
            Long cached = durationCacheMs.get(currentSong.getId());
            if (cached != null && cached > 0) {
                intent.putExtra("fallbackDurationMs", cached);
            } else {
                // 异步多级兜底：本地文件 -> DB -> 服务器API
                final String sid = currentSong.getId();
                bgExecutor.execute(() -> {
                    long fallback = 0L;
                    try {
                        // 1) 本地下载文件
                        try {
                            String local = new com.watch.limusic.download.LocalFileDetector(this).getDownloadedSongPath(sid);
                            if (local != null) {
                                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                                mmr.setDataSource(local);
                                String s = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                                if (s != null) fallback = Math.max(fallback, Long.parseLong(s));
                                try { mmr.release(); } catch (Throwable ignore) {}
                            }
                        } catch (Throwable ignore) {}
                        // 2) 数据库
                        if (fallback <= 0) {
                    try {
                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getSongById(sid);
                                if (se != null && se.getDuration() > 0) fallback = Math.max(fallback, (long) se.getDuration() * 1000L);
                            } catch (Throwable ignore) {}
                        }
                        // 3) 服务器API（秒->毫秒）
                        if (fallback <= 0) {
                            try {
                                Integer secs = NavidromeApi.getInstance(this).getSongDurationSeconds(sid);
                                if (secs != null && secs > 0) fallback = Math.max(fallback, (long) secs * 1000L);
                            } catch (Throwable ignore) {}
                        }
                    } catch (Throwable ignore) {}
                    if (fallback > 0) {
                            synchronized (durationCacheMs) { durationCacheMs.put(sid, fallback); }
                        // 补发一次包含 fallback 的广播（切回主线程读取 player 状态）
                        final long fb = fallback;
                        handler.post(new Runnable() { @Override public void run() {
                            try {
                            Intent fix = new Intent(ACTION_PLAYBACK_STATE_CHANGED);
                            fix.putExtra("isPlaying", player != null && player.isPlaying());
                            fix.putExtra("position", player != null ? player.getCurrentPosition() : 0);
                            fix.putExtra("duration", player != null ? player.getDuration() : 0);
                            fix.putExtra("playbackMode", playbackMode);
                                fix.putExtra("fallbackDurationMs", fb);
                                fix.putExtra("isSeekable", player != null && player.isCurrentMediaItemSeekable());
                                // 附带一次audioType
                                try {
                                    String audioType2 = detectCurrentAudioType();
                                    if (audioType2 == null) {
                                        com.google.android.exoplayer2.MediaItem ci2 = player != null ? player.getCurrentMediaItem() : null;
                                        if (ci2 != null && ci2.localConfiguration != null) {
                                            String mime2 = ci2.localConfiguration.mimeType;
                                            if (mime2 != null) {
                                                String m2 = mime2.toLowerCase();
                                                if (m2.contains("flac")) audioType2 = "FLAC"; else if (m2.contains("mpeg") || m2.contains("mp3")) audioType2 = "MP3";
                                            }
                                            if (audioType2 == null) {
                                                String key2 = ci2.localConfiguration.customCacheKey;
                                                if (key2 != null) {
                                                    if (key2.contains("_flac_")) audioType2 = "FLAC"; else if (key2.contains("_mp3_")) audioType2 = "MP3";
                                                }
                                            }
                                        }
                                    }
                                    if (audioType2 != null) fix.putExtra("audioType", audioType2);
                                } catch (Throwable ignore2) {}
                            if (currentSong != null) {
                                fix.putExtra("songId", currentSong.getId());
                                fix.putExtra("title", currentSong.getTitle());
                                fix.putExtra("artist", currentSong.getArtist());
                                fix.putExtra("albumId", currentSong.getAlbumId());
                            }
                            sendBroadcast(fix);
                            } catch (Throwable ignore) {}
                        }});
                }
                });
            }
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
					cached = CacheManager.getInstance(this).isCachedByAnyKey(currentSong.getId());
				}
			}
			long cacheUsed = CacheManager.getInstance(this).getCacheUsageBytes();
			long cacheMax = CacheManager.getInstance(this).getMaxCacheBytes();

			Log.d(TAG, "诊断[" + reason + "]: state=" + player.getPlaybackState() + ", isPlaying=" + player.isPlaying());
			if (currentSong != null) {
				int total = useGlobalAllSongsMode && globalTotalCount > 0 ? globalTotalCount : playlist.size();
				Log.d(TAG, "歌曲: " + currentSong.getTitle() + " - " + currentSong.getArtist() + ", 索引=" + currentIndex + "/" + total);
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
                long intervalMs = 500; // 默认 0.5s
                try {
                    android.content.SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                    int mode = sp.getInt("progress_broadcast_mode", 0);
                    if (mode == 1) intervalMs = 1500; else if (mode == 2) intervalMs = 2500;
                } catch (Exception ignore) {}
                handler.postDelayed(this, intervalMs);
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
        // 全局"所有歌曲"模式：
        if (useGlobalAllSongsMode) {
            if (playbackMode == PLAYBACK_MODE_SHUFFLE) {
                int target = pickRandomGlobalIndexAvoidingCurrent();
                jumpToGlobalIndex(target, 0L, true);
            } else {
                try { player.seekToNextMediaItem(); } catch (Throwable ignore) {}
            }
            Log.d(TAG, "播放下一首: 索引 " + currentIndex);
            sendPlaybackStateBroadcast();
            return;
        }
        // 非全局 + 随机：使用队列跳转
        if (playbackMode == PLAYBACK_MODE_SHUFFLE) {
            try { player.seekToNextMediaItem(); } catch (Throwable ignore) {}
            Log.d(TAG, "播放下一首(随机): 当前mediaIndex=" + player.getCurrentMediaItemIndex());
            sendPlaybackStateBroadcast();
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
        
        // 若目标不在当前窗口内，则按"前5/后15"重建窗口
        int mediaCount = Math.max(0, player.getMediaItemCount());
        int rel = currentIndex - mediaBasePlaylistIndex;
        boolean outOfWindow = (rel < 0 || rel >= mediaCount);
        if (outOfWindow) {
            final int PREV_CHUNK = 5;
            final int NEXT_CHUNK = 15;
            int wStart = Math.max(0, currentIndex - PREV_CHUNK);
            int wEnd = Math.min(playlist.size(), currentIndex + NEXT_CHUNK + 1);
            mediaBasePlaylistIndex = wStart;
            List<MediaItem> windowItems = new ArrayList<>();
            for (int i = wStart; i < wEnd; i++) {
                Song s = playlist.get(i);
                String streamUrl = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                String optimalUrl = getOptimalPlayUrl(s, streamUrl);
                windowItems.add(buildStreamingMediaItem(s.getId(), optimalUrl));
            }
            int startIdxInWindow = Math.max(0, currentIndex - wStart);
            try {
                player.setMediaItems(windowItems, startIdxInWindow, 0);
                // 维持当前播放模式（含随机/循环）
                applyPlaybackMode();
                player.prepare();
            } catch (Throwable ignore) {}
        } else {
        int targetMediaIndex = Math.max(0, currentIndex - mediaBasePlaylistIndex);
        targetMediaIndex = Math.min(Math.max(0, targetMediaIndex), Math.max(0, player.getMediaItemCount() - 1));
        player.seekTo(targetMediaIndex, 0);
        }
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
        
        // 耳机上一首：强制切到上一首；非强制时保留">3s回到本曲起点"的体验
        if (!forceToPrevious && player.getCurrentPosition() > 3000) {
            player.seekTo(0);
            return;
        }
        
        // 全局"所有歌曲"模式：
        if (useGlobalAllSongsMode) {
            if (playbackMode == PLAYBACK_MODE_SHUFFLE) {
                // 随机模式下上一首：从历史中回退；没有历史则随机挑一个不同于当前
                Integer prev = shuffleHistory.pollLast();
                int target = (prev != null) ? prev : pickRandomGlobalIndexAvoidingCurrent();
                jumpToGlobalIndex(target, 0L, false);
            } else {
                try { player.seekToPreviousMediaItem(); } catch (Throwable ignore) {}
            }
            Log.d(TAG, "播放上一首: 索引 " + currentIndex + (forceToPrevious ? " (forced)" : ""));
            sendPlaybackStateBroadcast();
            return;
        }
        // 非全局 + 随机：使用队列跳转
        if (playbackMode == PLAYBACK_MODE_SHUFFLE) {
            try { player.seekToPreviousMediaItem(); } catch (Throwable ignore) {}
            Log.d(TAG, "播放上一首(随机): 当前mediaIndex=" + player.getCurrentMediaItemIndex() + (forceToPrevious ? " (forced)" : ""));
            sendPlaybackStateBroadcast();
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
        
        // 若目标不在当前窗口内，则重建窗口
        int mediaCount = Math.max(0, player.getMediaItemCount());
        int rel = currentIndex - mediaBasePlaylistIndex;
        boolean outOfWindow = (rel < 0 || rel >= mediaCount);
        if (outOfWindow) {
            final int PREV_CHUNK = 5;
            final int NEXT_CHUNK = 15;
            int wStart = Math.max(0, currentIndex - PREV_CHUNK);
            int wEnd = Math.min(playlist.size(), currentIndex + NEXT_CHUNK + 1);
            mediaBasePlaylistIndex = wStart;
            List<MediaItem> windowItems = new ArrayList<>();
            for (int i = wStart; i < wEnd; i++) {
                Song s = playlist.get(i);
                String streamUrl = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                String optimalUrl = getOptimalPlayUrl(s, streamUrl);
                windowItems.add(buildStreamingMediaItem(s.getId(), optimalUrl));
            }
            int startIdxInWindow = Math.max(0, currentIndex - wStart);
            try {
                player.setMediaItems(windowItems, startIdxInWindow, 0);
                applyPlaybackMode();
                player.prepare();
            } catch (Throwable ignore) {}
        } else {
        int targetMediaIndex = Math.max(0, currentIndex - mediaBasePlaylistIndex);
        targetMediaIndex = Math.min(Math.max(0, targetMediaIndex), Math.max(0, player.getMediaItemCount() - 1));
        player.seekTo(targetMediaIndex, 0);
        }
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
            if (isCurrentSong(song)) {
                Log.d(TAG, "使用下载文件播放: " + song.getTitle() + " -> " + downloadedPath);
            }
            return "file://" + downloadedPath;
        }

        // 是否强制转码（设置页开关）：开启后，统一请求 mp3@320kbps 转码，避免硬件兼容差异
        try {
            SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
            boolean forceTrans;
            long now = System.currentTimeMillis();
            if (cachedForceTranscode != null && (now - cachedForceTranscodeAtMs) < SETTINGS_TTL_MS) {
                forceTrans = cachedForceTranscode;
            } else {
                forceTrans = sp.getBoolean("force_transcode_non_mp3", false);
                cachedForceTranscode = forceTrans;
                cachedForceTranscodeAtMs = now;
            }
            if (forceTrans) {
                String forced = navidromeApi.getTranscodedStreamUrl(song.getId(), "mp3", 320);
                if (isCurrentSong(song)) {
                    Log.d(TAG, "强制转码启用：统一请求 mp3@320kbps -> " + forced);
                }
                return forced;
            }
        } catch (Exception ignore) {}

        // FLAC 原始优先：如果是 FLAC，不再提前改为mp3，保留原始URL，失败时在 onPlayerError 中回退
        try {
            boolean looksLikeFlac = false;
            if (streamUrl != null) {
                String lower = streamUrl.toLowerCase();
                looksLikeFlac = lower.contains("format=flac") || lower.endsWith(".flac") || lower.contains("audio/flac");
            }
            if (looksLikeFlac) {
                if (isCurrentSong(song)) {
                    Log.d(TAG, "检测到FLAC，优先播放原始FLAC: " + song.getTitle());
                }
                return streamUrl;
            }
        } catch (Exception ignore) {}

        // 如果没有下载文件，使用流媒体URL
        if (isCurrentSong(song)) {
            Log.d(TAG, "使用流媒体播放: " + song.getTitle() + " -> " + streamUrl);
        }
        return streamUrl;
    }
    
    // 设置并播放整个列表，startIndex为开始播放的索引
    public void setPlaylist(List<Song> songs, int startIndex) {
        // 将重操作转移到服务内部后台线程，避免通过本地Binder在UI线程执行
        bgExecutor.execute(() -> {
            try { setPlaylistInternal(songs, startIndex); } catch (Throwable t) { Log.e(TAG, "setPlaylistInternal failed", t); }
        });
    }

    // 私有实现：原有重逻辑迁移至此（仅在服务内部线程调用）
    private void setPlaylistInternal(List<Song> songs, int startIndex) {
        // 普通模式：使用传入列表。若此前处于全局模式，退出之。
        useGlobalAllSongsMode = false;
        // 重置本地窗口扩边与随机锚点状态
        localBaseLenAtHeadAppend = -1;
        localAppendedHeadCount = 0;
        shuffleAnchorSongId = null;
        shuffleVisitedNonAnchor = false;
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
            // 离线模式：过滤不可播放的歌曲，仅保留已下载或已缓存的条目
            List<Song> filtered = new ArrayList<>();
            int filteredIndex = -1;
            for (int i = 0; i < songs.size(); i++) {
                Song s = songs.get(i);
                boolean isDownloaded = localFileDetector != null && localFileDetector.isSongDownloaded(s.getId());
                boolean cachedByKey = CacheManager.getInstance(this).isCachedByAnyKey(s.getId());
                if (isDownloaded || cachedByKey) {
                    if (i == startIndex) filteredIndex = filtered.size();
                    filtered.add(s);
                }
            }
            if (filtered.isEmpty()) {
                Log.w(TAG, "离线模式下没有可播放的歌曲");
                return;
            }
            playlist.addAll(filtered);
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
        
        // 普通模式：按小窗口注入（保持内存友好），不做后台整体补齐
        final int PREV_CHUNK = 5;
        final int NEXT_CHUNK = 15;
        int wStart = Math.max(0, currentIndex - PREV_CHUNK);
        int wEnd = Math.min(playlist.size(), currentIndex + NEXT_CHUNK + 1);
        int startIndexInWindow = currentIndex - wStart;
        mediaBasePlaylistIndex = wStart;
        List<MediaItem> windowItems = new ArrayList<>();
        for (int i = wStart; i < wEnd; i++) {
            Song s = playlist.get(i);
            String streamUrl = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
            String optimalUrl = getOptimalPlayUrl(s, streamUrl);
            windowItems.add(buildStreamingMediaItem(s.getId(), optimalUrl));
        }
        // 在主线程应用到 ExoPlayer，避免跨线程潜在风险
        handler.post(() -> {
            try {
        player.stop();
        player.clearMediaItems();
        player.setMediaItems(windowItems, startIndexInWindow, 0);
        player.prepare();
        applyPlaybackMode();
        play();
            } catch (Throwable t) { Log.e(TAG, "apply media items failed", t); }
        });
        
        Log.d(TAG, "窗口化设置播放列表: 总 " + playlist.size() + " 首, 窗口[" + wStart + "," + wEnd + "] 起播索引=" + startIndexInWindow);
    }
    
    // 从播放列表中播放指定索引的歌曲
    private void playSongFromPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) {
            return;
        }
        
        currentIndex = index;
        currentSong = playlist.get(index);
        
        // 不清除MediaItems，只跳转到指定位置
        int targetMediaIndex = Math.max(0, currentIndex - mediaBasePlaylistIndex);
        targetMediaIndex = Math.min(Math.max(0, targetMediaIndex), Math.max(0, player.getMediaItemCount() - 1));
        player.seekTo(targetMediaIndex, 0);
        
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
                if (useGlobalAllSongsMode) {
                    // 全局模式：禁用内建shuffle，改为自定义全局随机
                    player.setShuffleModeEnabled(false);
                    Log.d(TAG, "设置播放模式: 全局随机（自定义）");
                } else {
                    // 非全局随机：小列表全量注入，扩大随机覆盖范围
                    try {
                        int currentItemIndex = Math.max(0, player.getCurrentMediaItemIndex());
                        long curPos = Math.max(0, player.getCurrentPosition());
                        int mediaCount = Math.max(0, player.getMediaItemCount());
                        int listSize = (playlist != null ? playlist.size() : 0);
                        if (listSize > 0 && listSize <= FULL_INJECT_THRESHOLD && mediaCount != listSize) {
                            // 以当前歌曲为基准，重建全量媒体项
                            List<MediaItem> items = new ArrayList<>(listSize);
                            for (int i = 0; i < listSize; i++) {
                                Song s = playlist.get(i);
                                String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                                items.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
                            }
                            // 定位当前曲在全量列表中的索引
                            int keep = 0;
                            if (currentSong != null) {
                                for (int i = 0; i < listSize; i++) { if (currentSong.getId().equals(playlist.get(i).getId())) { keep = i; break; } }
                            } else {
                                // 退化：按原 mediaIndex 映射回全量
                                keep = Math.min(Math.max(0, currentIndex), Math.max(0, listSize - 1));
                            }
                            mediaBasePlaylistIndex = 0;
                            player.setMediaItems(items, keep, curPos);
                            player.prepare();
                            player.setShuffleModeEnabled(true);
                            player.seekTo(keep, curPos);
                        } else {
                    player.setShuffleModeEnabled(true);
                    if (player.getPlaybackState() == Player.STATE_READY) {
                                player.seekTo(currentItemIndex, curPos);
                            }
                        }
                    } catch (Throwable t) {
                        // 安全兜底
                        player.setShuffleModeEnabled(true);
                    }
                     // 记录随机锚点：以当前歌曲为一轮起点
                     shuffleAnchorSongId = (currentSong != null ? currentSong.getId() : null);
                     shuffleVisitedNonAnchor = false;
                    Log.d(TAG, "设置播放模式: 随机播放（本地列表） 保持索引:" + (player != null ? Math.max(0, player.getCurrentMediaItemIndex()) : 0));
                }
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
            boolean seekable = false;
            long dur = 0L;
            try { seekable = player.isCurrentMediaItemSeekable(); } catch (Throwable ignore) {}
            try { dur = player.getDuration(); } catch (Throwable ignore) {}
            if (!seekable || dur <= 0) {
                Log.d(TAG, "忽略 seek: 不可定位或时长未知 (seekable=" + seekable + ", dur=" + dur + ")");
                return;
            }
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
        } else if (playbackMode == PLAYBACK_MODE_SHUFFLE) {
            applyPlaybackMode();
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
        try { if (configUpdatedReceiver != null) unregisterReceiver(configUpdatedReceiver); } catch (Exception ignore) {}
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
			// 保存全局"所有歌曲"模式
			e.putBoolean(KEY_GLOBAL_ALL_SONGS, useGlobalAllSongsMode);
			if (useGlobalAllSongsMode) {
				// 仅保存全局索引，避免保存庞大列表
				e.putInt(KEY_GLOBAL_INDEX, Math.max(0, currentIndex));
				// 清理普通播放列表持久化，避免恢复冲突
				e.remove(KEY_PLAYLIST_IDS);
				e.remove(KEY_PLAYLIST_INDEX);
			} else {
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

			// 优先处理全局"所有歌曲"模式的恢复
			boolean wasGlobal = prefs.getBoolean(KEY_GLOBAL_ALL_SONGS, false);
			if (wasGlobal) {
				int center = Math.max(0, prefs.getInt(KEY_GLOBAL_INDEX, 0));
				// 初始化全局窗口但不自动播放
				try {
					playAllSongsFromGlobalRestore(center, Math.max(position, 0));
					this.playbackMode = savedMode;
					applyPlaybackMode();
					player.pause();
					updatePlaybackState();
					Log.d(TAG, "已恢复上次播放状态(全局所有歌曲): index=" + center + ", pos=" + position + ", playing=false");
					return;
				} catch (Throwable t) {
					Log.w(TAG, "恢复全局所有歌曲失败，回退普通路径", t);
				}
			}

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
				mediaBasePlaylistIndex = 0;
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
				mediaBasePlaylistIndex = 0;
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
        if (lower.contains("format=mp3") || lower.endsWith(".mp3")) {
            b.setMimeType(MimeTypes.AUDIO_MPEG)
             .setCustomCacheKey("stream_mp3_" + songId);
        } else if (lower.contains("format=flac") || lower.endsWith(".flac") || lower.contains("audio/flac")) {
            b.setMimeType(MimeTypes.AUDIO_FLAC)
             .setCustomCacheKey("stream_flac_" + songId);
        } else {
            b.setCustomCacheKey("stream_raw_" + songId);
        }
        return b.build();
    }

    // 仅对当前真正要播放的歌曲输出详细日志，批量构建播放列表时不打印
    private boolean isCurrentSong(Song s) {
        try {
            return s != null && currentSong != null && s.getId() != null && s.getId().equals(currentSong.getId());
        } catch (Exception ignore) {
            return false;
        }
    }

    // ============ 全局"所有歌曲"播放入口（滑动窗口） ============
    public void playAllSongsFromGlobal(int globalIndex) {
        // 将重操作转移到服务内部后台线程，避免通过本地Binder在UI线程执行
        bgExecutor.execute(() -> {
            try { playAllSongsFromGlobalInternal(globalIndex); } catch (Throwable t) { Log.e(TAG, "playAllSongsFromGlobalInternal failed", t); }
        });
    }

    // 私有实现：原有重逻辑迁移至此（仅在服务内部线程调用）
    private void playAllSongsFromGlobalInternal(int globalIndex) {
        try {
            if (musicRepository == null) musicRepository = MusicRepository.getInstance(this);
        } catch (Throwable ignore) {}
        if (musicRepository == null) {
            Log.w(TAG, "MusicRepository 不可用，回退为普通列表模式");
            // 回退：仅播放当前点击项
            List<Song> one = new ArrayList<>();
            try { Song s = musicRepository.getSongsRange(1, Math.max(0, globalIndex)).get(0); one.add(s); } catch (Throwable ignore) {}
            setPlaylistInternal(one, 0);
            return;
        }
        useGlobalAllSongsMode = true;
        globalTotalCount = Math.max(0, musicRepository.getSongCount());
        if (globalTotalCount <= 0) return;
        int center = Math.min(Math.max(0, globalIndex), globalTotalCount - 1);
        windowStart = Math.max(0, center - WINDOW_GUARD - (WINDOW_CHUNK / 2));
        windowEnd = Math.min(globalTotalCount, windowStart + WINDOW_CHUNK);
        mediaBasePlaylistIndex = windowStart;

        // 构建首块媒体项
        List<Song> songs = musicRepository.getSongsRange(Math.max(0, windowEnd - windowStart), windowStart);
        if (songs == null) songs = new ArrayList<>();
        playlist.clear();
        playlist.addAll(songs);
        currentIndex = center;
        currentSong = playlist.get(Math.max(0, center - windowStart));

        List<MediaItem> items = new ArrayList<>();
        for (Song s : songs) {
            String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
            String opt = getOptimalPlayUrl(s, url);
            items.add(buildStreamingMediaItem(s.getId(), opt));
        }
        int startInWindow = center - windowStart;
        // 在主线程应用到 ExoPlayer，避免跨线程潜在风险
        handler.post(() -> {
            try {
        player.stop();
        player.clearMediaItems();
        player.setMediaItems(items, Math.max(0, startInWindow), 0);
        player.prepare();
        applyPlaybackMode();
        play();
            } catch (Throwable t) { Log.e(TAG, "apply global media items failed", t); }
        });
        Log.d(TAG, "全局所有歌曲：初始化窗口[" + windowStart + "," + windowEnd + "] center=" + center + " startInWindow=" + startInWindow);
    }

    private void expandWindowIfNeeded(int playerIndex) {
        try {
            if (!useGlobalAllSongsMode || musicRepository == null) return;
            int mediaCount = player.getMediaItemCount();
            if (mediaCount <= 0) return;
            // 前向扩边
            if (playerIndex >= Math.max(0, mediaCount - 1 - WINDOW_GUARD)) {
                // 计算全局下一段
                int needFrom = windowEnd;
                if (needFrom < globalTotalCount) {
                    int needTo = Math.min(globalTotalCount, needFrom + WINDOW_CHUNK);
                    List<Song> more = musicRepository.getSongsRange(Math.max(0, needTo - needFrom), needFrom);
                    if (more != null && !more.isEmpty()) {
                        List<MediaItem> after = new ArrayList<>();
                        for (Song s : more) {
                            String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                            after.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
                        }
                        playlist.addAll(more);
                        int add = more.size();
                        windowEnd += add;
                        handler.post(() -> { try { player.addMediaItems(after); } catch (Throwable ignore) {} });
                    }
                } else {
                    // 已到全局末尾：为循环模式预追加头块
                    if (globalTotalCount > 0) {
                        int headTo = Math.min(globalTotalCount, WINDOW_CHUNK);
                        List<Song> head = musicRepository.getSongsRange(headTo, 0);
                        if (head != null && !head.isEmpty()) {
                            List<MediaItem> after = new ArrayList<>();
                            for (Song s : head) {
                                String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                                after.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
                            }
                            playlist.addAll(head); // 注意：playlist 此时可能超过 totalCount（仅用于映射方便）
                            handler.post(() -> { try { player.addMediaItems(after); } catch (Throwable ignore) {} });
                        }
                    }
                }
            }
            // 后向扩边
            if (playerIndex <= WINDOW_GUARD) {
                if (windowStart > 0) {
                    int needTo = windowStart;
                    int needFrom = Math.max(0, needTo - WINDOW_CHUNK);
                    List<Song> more = musicRepository.getSongsRange(Math.max(0, needTo - needFrom), needFrom);
                    if (more != null && !more.isEmpty()) {
                        List<MediaItem> before = new ArrayList<>();
                        for (Song s : more) {
                            String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                            before.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
                        }
                        playlist.addAll(0, more);
                        int add = more.size();
                        windowStart -= add;
                        mediaBasePlaylistIndex = windowStart;
                        int p = player.getCurrentMediaItemIndex();
                        handler.post(() -> {
                            try {
                                player.addMediaItems(0, before);
                                // 维持当前曲目：头插后当前media索引需要后移 add 位
                                player.seekTo(p + add, Math.max(0, player.getCurrentPosition()));
                            } catch (Throwable ignore) {}
                        });
                    }
                }
            }
            // 回收：窗口过大时移除远端块
            int newCount = player.getMediaItemCount();
            if (newCount > WINDOW_MAX) {
                int removeFront = Math.max(0, newCount - WINDOW_MAX);
                // 不移除保护区与当前附近
                int cur = player.getCurrentMediaItemIndex();
                removeFront = Math.min(removeFront, Math.max(0, cur - WINDOW_GUARD));
                if (removeFront > 0) {
                    int finalRemoveFront = removeFront;
                    windowStart += finalRemoveFront;
                    mediaBasePlaylistIndex = windowStart;
                    // 同步移除内存窗口列表前段，保持与播放器一致
                    try {
                        if (finalRemoveFront <= playlist.size()) {
                            playlist.subList(0, finalRemoveFront).clear();
                        } else {
                            playlist.clear();
                        }
                    } catch (Throwable ignore) {}
                    handler.post(() -> { try { player.removeMediaItems(0, finalRemoveFront); } catch (Throwable ignore) {} });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "expandWindowIfNeeded 异常", t);
        }
    }

    // 全局所有歌曲：用于恢复（不自动播放，按position定位）
    private void playAllSongsFromGlobalRestore(int globalIndex, long positionMs) {
        try { if (musicRepository == null) musicRepository = MusicRepository.getInstance(this); } catch (Throwable ignore) {}
        if (musicRepository == null) return;
        useGlobalAllSongsMode = true;
        globalTotalCount = Math.max(0, musicRepository.getSongCount());
        if (globalTotalCount <= 0) return;
        int center = Math.min(Math.max(0, globalIndex), Math.max(0, globalTotalCount - 1));
        windowStart = Math.max(0, center - WINDOW_GUARD - (WINDOW_CHUNK / 2));
        windowEnd = Math.min(globalTotalCount, windowStart + WINDOW_CHUNK);
        mediaBasePlaylistIndex = windowStart;

        List<Song> songs = musicRepository.getSongsRange(Math.max(0, windowEnd - windowStart), windowStart);
        if (songs == null) songs = new ArrayList<>();
        playlist.clear();
        playlist.addAll(songs);
        currentIndex = center;
        int startInWindow = center - windowStart;
        currentSong = playlist.isEmpty() ? null : playlist.get(Math.max(0, startInWindow));

        List<MediaItem> items = new ArrayList<>();
        for (Song s : songs) {
            String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
            items.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
        }
        player.stop();
        player.clearMediaItems();
        player.setMediaItems(items, Math.max(0, startInWindow), Math.max(0, positionMs));
        player.prepare();
        applyPlaybackMode();
    }

    // ============ 全局随机播放：工具方法 ============
    private int pickRandomGlobalIndexAvoidingCurrent() {
        if (globalTotalCount <= 1) return Math.max(0, currentIndex);
        int r = currentIndex;
        for (int tries = 0; tries < 5; tries++) {
            int cand = shuffleRandom.nextInt(Math.max(1, globalTotalCount));
            if (cand != currentIndex) { r = cand; break; }
        }
        return r;
    }

    private void jumpToGlobalIndex(int targetGlobalIndex, long positionMs, boolean recordHistory) {
        if (!useGlobalAllSongsMode || musicRepository == null || globalTotalCount <= 0) return;
        int t = ((targetGlobalIndex % globalTotalCount) + globalTotalCount) % globalTotalCount;
        // 若目标在现有窗口内，直接 seek；否则重建窗口以目标为中心
        if (t >= windowStart && t < windowEnd) {
            int mediaIdx = Math.max(0, t - mediaBasePlaylistIndex);
            mediaIdx = Math.min(mediaIdx, Math.max(0, player.getMediaItemCount() - 1));
            final int m = mediaIdx;
            handler.post(() -> { try { player.seekTo(m, Math.max(0, positionMs)); } catch (Throwable ignore) {} });
        } else {
            int center = t;
            int wStart = Math.max(0, center - WINDOW_GUARD - (WINDOW_CHUNK / 2));
            int wEnd = Math.min(globalTotalCount, wStart + WINDOW_CHUNK);
            List<Song> songs = musicRepository.getSongsRange(Math.max(0, wEnd - wStart), wStart);
            if (songs == null) songs = new ArrayList<>();
            playlist.clear();
            playlist.addAll(songs);
            windowStart = wStart;
            windowEnd = wEnd;
            mediaBasePlaylistIndex = windowStart;
            currentIndex = center;
            int startInWindow = center - windowStart;
            List<MediaItem> items = new ArrayList<>();
            for (Song s : songs) {
                String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                items.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
            }
            handler.post(() -> {
                try {
                    player.stop();
                    player.clearMediaItems();
                    player.setMediaItems(items, Math.max(0, startInWindow), Math.max(0, positionMs));
                    player.setShuffleModeEnabled(false);
                    player.prepare();
                    play();
                } catch (Throwable ignore) {}
            });
        }
        if (recordHistory) {
            // 记录历史，便于未来做"上一首"在随机模式下回退
            if (shuffleHistory.size() >= 64) shuffleHistory.pollFirst();
            shuffleHistory.addLast(t);
        }
    }

    // ========== 睡眠定时 API ==========
    public void setSleepTimerMinutes(int minutes, boolean waitFinishCurrent) {
        if (minutes <= 0) { cancelSleepTimer(); return; }
        try { if (sleepRunnable != null) handler.removeCallbacks(sleepRunnable); } catch (Throwable ignore) {}
        cancelSleepAlarmSafe();
        sleepType = SleepType.AFTER_MS;
        sleepWaitFinishOnExpire = waitFinishCurrent;
        long delayMs = Math.max(0, minutes) * 60_000L;
        sleepDeadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + delayMs;
        sleepRunnable = new Runnable() { @Override public void run() {
            if (sleepWaitFinishOnExpire) {
                // 到点转为"播完当前后暂停"
                sleepType = SleepType.AFTER_CURRENT;
                long remain = computeRemainMsSafe();
                try { if (player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE) player.setRepeatMode(Player.REPEAT_MODE_OFF); } catch (Throwable ignore) {}
                sleepRunnable = new Runnable() { @Override public void run() { pauseAndClearSleep(); } };
                sleepDeadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + Math.max(0, remain);
                handler.postDelayed(sleepRunnable, Math.max(0, sleepDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()));
                broadcastSleepChanged(remain);
            } else {
                pauseAndClearSleep();
            }
        } };
        handler.postDelayed(sleepRunnable, Math.max(0, sleepDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()));
        // AlarmManager 兜底：到点触发 onSleepAlarmFired，避免个别设备延迟Handler执行
        scheduleSleepAlarmAt(sleepDeadlineElapsedMs);
        broadcastSleepChanged(delayMs);
    }

    public void setSleepStopAfterCurrent() {
        try { if (sleepRunnable != null) handler.removeCallbacks(sleepRunnable); } catch (Throwable ignore) {}
        cancelSleepAlarmSafe();
        sleepType = SleepType.AFTER_CURRENT;
        // 基于剩余时长挂起一次回调，额外 +500ms 余量
        long remain = computeRemainMsSafe();
        sleepRunnable = new Runnable() { @Override public void run() { pauseAndClearSleep(); } };
        sleepDeadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + Math.max(0, remain);
        handler.postDelayed(sleepRunnable, Math.max(0, sleepDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()));
        // AFTER_CURRENT 依赖切曲事件触发暂停，这里不安排闹钟直接pause以免提前暂停
        broadcastSleepChanged(remain);
    }

    public void cancelSleepTimer() {
        try { if (sleepRunnable != null) handler.removeCallbacks(sleepRunnable); } catch (Throwable ignore) {}
        sleepRunnable = null;
        sleepType = SleepType.NONE;
        sleepWaitFinishOnExpire = false;
        sleepDeadlineElapsedMs = 0L;
        cancelSleepAlarmSafe();
        broadcastSleepChanged(0);
    }

    public SleepType getSleepType() { return sleepType; }

    public long getSleepRemainMs() {
        if (sleepType == SleepType.NONE) return 0L;
        long now = android.os.SystemClock.elapsedRealtime();
        return Math.max(0, sleepDeadlineElapsedMs - now);
    }

    public static class SleepTimerState {
        public final SleepType type;
        public final long remainMs;
        public final boolean waitFinishOnExpire;
        public SleepTimerState(SleepType t, long r, boolean w) { this.type = t; this.remainMs = r; this.waitFinishOnExpire = w; }
    }

    public SleepTimerState getSleepTimerState() {
        return new SleepTimerState(sleepType, getSleepRemainMs(), sleepWaitFinishOnExpire);
    }

    private void pauseAndClearSleep() {
        try { if (player != null) player.pause(); } catch (Throwable ignore) {}
        try { if (sleepRunnable != null) handler.removeCallbacks(sleepRunnable); } catch (Throwable ignore) {}
        sleepRunnable = null;
        sleepType = SleepType.NONE;
        sleepWaitFinishOnExpire = false;
        sleepDeadlineElapsedMs = 0L;
        cancelSleepAlarmSafe();
        broadcastSleepChanged(0);
    }

    private void broadcastSleepChanged(long remainMs) {
        try {
            Intent i = new Intent(ACTION_SLEEP_TIMER_CHANGED);
            i.putExtra("type", sleepType.name());
            i.putExtra("remainMs", Math.max(0, remainMs));
            sendBroadcast(i);
        } catch (Throwable ignore) {}
    }

    private long computeRemainMsSafe() {
        long pos = 0L, dur = 0L;
        try { if (player != null) pos = Math.max(0, player.getCurrentPosition()); } catch (Throwable ignore) {}
        try { if (player != null) dur = Math.max(0, player.getDuration()); } catch (Throwable ignore) {}
        if (dur <= 0) {
            // 尝试读取数据库缓存时长
            try {
                SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String sid = sp.getString(KEY_SONG_ID, null);
                if (sid != null) {
                    SongEntity se = MusicDatabase.getInstance(this).songDao().getSongById(sid);
                    if (se != null && se.getDuration() > 0) dur = (long) se.getDuration() * 1000L;
                }
            } catch (Throwable ignore) {}
        }
        return Math.max(0, dur - pos) + 500L; // 余量
    }

    private void scheduleSleepAlarmAt(long elapsedDeadlineMs) {
        try {
            if (alarmManager == null || sleepAlarmIntent == null) return;
            long triggerAt = Math.max(elapsedDeadlineMs, android.os.SystemClock.elapsedRealtime());
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, sleepAlarmIntent);
            } else if (android.os.Build.VERSION.SDK_INT >= 19) {
                alarmManager.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, sleepAlarmIntent);
            } else {
                alarmManager.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, sleepAlarmIntent);
            }
        } catch (Throwable ignore) {}
    }

    private void cancelSleepAlarmSafe() {
        try { if (alarmManager != null && sleepAlarmIntent != null) alarmManager.cancel(sleepAlarmIntent); } catch (Throwable ignore) {}
    }

    private void onSleepAlarmFired() {
        // 若普通定时：直接暂停；若需要"播完当前再暂停"，则转换为AFTER_CURRENT并挂一次剩余
        if (sleepType == SleepType.AFTER_MS) {
            if (sleepWaitFinishOnExpire) {
                sleepType = SleepType.AFTER_CURRENT;
                long remain = computeRemainMsSafe();
                try { if (player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE) player.setRepeatMode(Player.REPEAT_MODE_OFF); } catch (Throwable ignore) {}
                try { if (sleepRunnable != null) handler.removeCallbacks(sleepRunnable); } catch (Throwable ignore) {}
                sleepRunnable = new Runnable() { @Override public void run() { pauseAndClearSleep(); } };
                sleepDeadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + Math.max(0, remain);
                handler.postDelayed(sleepRunnable, Math.max(0, sleepDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()));
                // AFTER_CURRENT 不再保留闹钟，依赖切曲与挂起回调
            } else {
                pauseAndClearSleep();
            }
        }
    }

    // 基于当前选择的音轨格式检测音频类型
    private String detectCurrentAudioType() {
        try {
            if (player == null) return null;
            Tracks tracks = player.getCurrentTracks();
            if (tracks == null) return null;
            for (int gi = 0; gi < tracks.getGroups().size(); gi++) {
                Tracks.Group g = tracks.getGroups().get(gi);
                if (g == null) continue;
                if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
                for (int ti = 0; ti < g.length; ti++) {
                    if (!g.isTrackSelected(ti)) continue;
                    Format f = g.getTrackFormat(ti);
                    if (f == null) continue;
                    String sm = f.sampleMimeType;
                    if (sm == null) continue;
                    String m = sm.toLowerCase();
                    if (m.contains("flac")) return "FLAC";
                    if (m.contains("mpeg") || m.contains("mp3")) return "MP3";
                    if (m.contains("aac")) return "AAC";
                    if (m.contains("vorbis")) return "OGG";
                    if (m.contains("opus")) return "OPUS";
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    public String getCurrentAudioType() {
        String t = detectCurrentAudioType();
        if (t != null) return t;
        try {
            MediaItem ci = player != null ? player.getCurrentMediaItem() : null;
            if (ci != null && ci.localConfiguration != null) {
                String mime = ci.localConfiguration.mimeType;
                if (mime != null) {
                    String m = mime.toLowerCase();
                    if (m.contains("flac")) return "FLAC"; else if (m.contains("mpeg") || m.contains("mp3")) return "MP3";
                }
                String key = ci.localConfiguration.customCacheKey;
                if (key != null) {
                    if (key.contains("_flac_")) return "FLAC"; else if (key.contains("_mp3_")) return "MP3";
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // 非全局歌单列表：根据播放器 media 索引在临界处按需扩边（重建窗口）
    private void expandLocalWindowIfNeeded(int playerIndex) {
        try {
            if (useGlobalAllSongsMode) return;
            int mediaCount = player != null ? player.getMediaItemCount() : 0;
            if (mediaCount <= 0 || playlist == null || playlist.isEmpty()) return;
            final int PREV_CHUNK = 5;
            final int NEXT_CHUNK = 15;
            // 近尾部：向后扩一段（通过重建窗口替代直接追加，保持实现简单稳定）
            if (playerIndex >= Math.max(0, mediaCount - 1 - WINDOW_GUARD)) {
                int target = mediaBasePlaylistIndex + playerIndex + 1; // 预计下一首的全量索引
                if (target < playlist.size()) {
                    int wStart = Math.max(0, target - PREV_CHUNK);
                    int wEnd = Math.min(playlist.size(), target + NEXT_CHUNK + 1);
                    mediaBasePlaylistIndex = wStart;
                    List<MediaItem> items = new ArrayList<>();
                    for (int i = wStart; i < wEnd; i++) {
                        Song s = playlist.get(i);
                        String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                        items.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
                    }
                    int keep = Math.max(0, player.getCurrentMediaItemIndex());
                    long pos = Math.max(0, player.getCurrentPosition());
                    handler.post(() -> {
                        try {
                            player.setMediaItems(items, Math.max(0, (mediaBasePlaylistIndex + keep) - wStart), pos);
                            applyPlaybackMode();
                            player.prepare();
                            //Log.d(TAG, "本地窗口扩边：重建到[" + wStart + "," + wEnd + "] 保持当前项");
                        } catch (Throwable ignore) {}
                    });
                }
            }
            // 近头部：向前扩一段
            if (playerIndex <= WINDOW_GUARD) {
                int target = mediaBasePlaylistIndex + playerIndex - 1;
                if (target >= 0) {
                    int wStart = Math.max(0, Math.min(target - PREV_CHUNK, Math.max(0, playlist.size() - 1)));
                    int wEnd = Math.min(playlist.size(), wStart + (PREV_CHUNK + NEXT_CHUNK + 1));
                    mediaBasePlaylistIndex = wStart;
                    List<MediaItem> items = new ArrayList<>();
                    for (int i = wStart; i < wEnd; i++) {
                        Song s = playlist.get(i);
                        String url = NavidromeApi.getInstance(this).getStreamUrl(s.getId());
                        items.add(buildStreamingMediaItem(s.getId(), getOptimalPlayUrl(s, url)));
                    }
                    int keep = Math.max(0, player.getCurrentMediaItemIndex());
                    long pos = Math.max(0, player.getCurrentPosition());
                    handler.post(() -> {
                        try {
                            player.setMediaItems(items, Math.max(0, (mediaBasePlaylistIndex + keep) - wStart), pos);
                            applyPlaybackMode();
                            player.prepare();
                            //Log.d(TAG, "本地窗口扩边：前向重建到[" + wStart + "," + wEnd + "] 保持当前项");
                        } catch (Throwable ignore) {}
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "expandLocalWindowIfNeeded 异常", t);
        }
    }

    // 强制刷新本地随机顺序（优先使用 DefaultShuffleOrder；失败则回退为重新设置媒体项）
    private void rerollLocalShuffleOrderKeepingCurrent() {
        try {
            if (player == null) return;
            int count = player.getMediaItemCount();
            if (count <= 1) return;
            int keep = Math.max(0, player.getCurrentMediaItemIndex());
            long pos = Math.max(0, player.getCurrentPosition());
            // 优先使用 DefaultShuffleOrder
            try {
                Class<?> orderClz = Class.forName("com.google.android.exoplayer2.source.DefaultShuffleOrder");
                java.lang.reflect.Constructor<?> ctor = orderClz.getConstructor(int.class, long.class);
                Object order = ctor.newInstance(count, new java.util.Random().nextLong());
                Class<?> iface = Class.forName("com.google.android.exoplayer2.source.ShuffleOrder");
                java.lang.reflect.Method m = player.getClass().getMethod("setShuffleOrder", iface);
                m.invoke(player, order);
                if (DEBUG_VERBOSE) Log.d(TAG, "已通过 DefaultShuffleOrder 重置随机顺序");
                return;
            } catch (Throwable reflectFail) {
                if (DEBUG_VERBOSE) Log.d(TAG, "DefaultShuffleOrder 不可用，回退为重设媒体项");
            }
            // 回退：重设媒体项以促使内部重建随机序列
            List<MediaItem> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                try { items.add(player.getMediaItemAt(i)); } catch (Throwable ignore) {}
            }
            player.setShuffleModeEnabled(false);
            player.setMediaItems(items, keep, pos);
            player.prepare();
            player.setShuffleModeEnabled(true);
            player.seekTo(keep, pos);
        } catch (Throwable e) {
            Log.w(TAG, "重置本地随机顺序失败", e);
        }
    }

    // 新增：广播音频会话ID，供可视化视图绑定
    private void sendAudioSessionBroadcast() {
        try {
            if (player == null) return;
            int sid = player.getAudioSessionId();
            if (sid <= 0) return;
            Intent i = new Intent("com.watch.limusic.AUDIO_SESSION_CHANGED");
            i.putExtra("audioSessionId", sid);
            sendBroadcast(i);
        } catch (Throwable ignore) {}
    }

    public int getAudioSessionIdSafe() {
        try { return player != null ? player.getAudioSessionId() : 0; } catch (Throwable ignore) { return 0; }
    }
} 