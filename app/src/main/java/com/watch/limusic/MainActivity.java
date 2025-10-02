package com.watch.limusic;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import com.google.android.material.navigation.NavigationView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;
import androidx.annotation.NonNull;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import androidx.core.view.GravityCompat;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.view.WindowManager;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.Button;
import android.view.View;
import android.view.ViewStub;
import android.media.AudioManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.watch.limusic.adapter.SongAdapter;
import com.watch.limusic.model.Song;
import com.watch.limusic.service.PlayerService;
import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.api.SubsonicResponse;
import com.watch.limusic.model.Album;
import com.watch.limusic.adapter.AlbumAdapter;
import com.watch.limusic.view.LetterIndexDialog;
import com.watch.limusic.database.MusicRepository;
import com.watch.limusic.download.DownloadManager;
import com.watch.limusic.download.LocalFileDetector;
import com.watch.limusic.migration.DownloadSystemMigration;
import com.watch.limusic.util.NetworkUtils;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.watch.limusic.cache.CacheManager;
import com.watch.limusic.util.BlurUtils;
import com.watch.limusic.adapter.SectionHeaderAdapter;
import com.watch.limusic.adapter.DownloadTaskAdapter;
import com.watch.limusic.adapter.DownloadedSongAdapter;
import com.watch.limusic.model.DownloadInfo;
import com.watch.limusic.model.DownloadStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.util.Log;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.core.content.ContextCompat;
import com.watch.limusic.model.SongWithIndex;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        SongAdapter.OnSongClickListener,
        SongAdapter.OnDownloadClickListener {
    
    private static final String TAG = "MainActivity";
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private RecyclerView recyclerView;
    private LinearLayoutManager layoutManager;
    private android.widget.ImageButton btnLocateCurrent;
    // 用于滚动期延迟处理 DB 刷新，避免闪烁与跳位
    private int lastKnownScrollState = RecyclerView.SCROLL_STATE_IDLE;
    private boolean pendingDbUpdate = false;
    private ImageView albumArt;
    private TextView songTitle;
    private TextView songArtist;
    private ImageButton playPauseButton;
    private ImageButton repeatModeButton;
    private SeekBar seekBar;
    private ProgressBar progressBar;
    private SongAdapter songAdapter;
    private AlbumAdapter albumAdapter;
    private PlayerService playerService;
    private boolean bound = false;
    private Toolbar toolbar;
    private String currentView = "albums"; // 当前视图类型：albums, songs, artists, playlists
    private static final int PAGE_SIZE = 30;  // 每页加载的专辑数量（更小的首屏分页，提升首屏速度）
    private int currentOffset = 0;  // 当前加载偏移量
    private boolean isLoading = false;  // 是否正在加载
    private boolean hasMoreData = true;  // 是否还有更多数据
    private int albumsScrollPos = 0;     // 专辑列表：上次可见位置
    private int albumsScrollOffset = 0;  // 专辑列表：上次偏移（用于精确还原）
    private int artistsScrollPos = 0;     // 艺术家列表：上次可见位置
    private int artistsScrollOffset = 0;  // 艺术家列表：上次偏移（用于精确还原）
    private boolean userIsSeeking = false;
    private TextView timeDisplay;
    private String originalTitle = "";
    private boolean isSeeking = false;
    private String lastAlbumId = "";
    private MusicRepository musicRepository; // 音乐数据存储库
    private boolean isNetworkAvailable = true; // 网络是否可用
    private DownloadManager downloadManager; // 下载管理器
    private LocalFileDetector localFileDetector; // 本地文件检测器
    // 占位/空态/错误态视图
    private View skeletonContainer;
    private View emptyContainer;
    private View errorContainer;
    private TextView errorMessageView;
    private TextView emptyMessageView;
    private Button retryEmptyButton;
    private Button retryErrorButton;
    // 搜索页底部提示栏
    private TextView bottomHintBar;
    private com.watch.limusic.adapter.SearchFooterAdapter searchFooterAdapter;
    private com.watch.limusic.adapter.SearchFooterAdapter getOrInitSearchFooter() {
        if (searchFooterAdapter == null) searchFooterAdapter = new com.watch.limusic.adapter.SearchFooterAdapter();
        return searchFooterAdapter;
    }
    
    // 新增：抽屉切换器持有引用，便于动态切换导航图标
    private ActionBarDrawerToggle drawerToggle;
    
    // 新增：双击返回退出的时间控制
    private long lastBackPressedTime = 0L;
    private static final long BACK_EXIT_INTERVAL = 2000L; // 毫秒
    
    // 搜索视图状态（嵌入式）
    private String searchQuery = "";
    private static final int SEARCH_PAGE_SIZE = 50;
    private int searchOffset = 0;
    private boolean searchLoading = false;
    private boolean searchHasMore = true;
    private int searchRequestId = 0;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchDebounce = new Runnable() {
        @Override public void run() { try { startSearch(true); } catch (Exception ignore) {} }
    };
    private android.widget.EditText searchInput;
    private android.widget.ImageButton btnClearSearch;
    private androidx.recyclerview.widget.RecyclerView.OnScrollListener searchScrollListener;
    private final java.util.ArrayList<com.watch.limusic.model.Song> searchResults = new java.util.ArrayList<>();
    
    // 最近一次字母跳转的目标字母（用于数据库更新后纠正定位）
    private String pendingJumpLetter = null;
    
    // 新增：艺术家适配器引用
    private com.watch.limusic.adapter.ArtistAdapter artistAdapter;
    
    // 检测缓存状态的广播接收器
    private BroadcastReceiver cacheStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.watch.limusic.CACHE_STATUS_CHANGED")) {
                String songId = intent.getStringExtra("songId");
                boolean isCached = intent.getBooleanExtra("isCached", false);
                
                if (songId == null) return;
                // 更新适配器中的缓存状态（根据当前适配器类型分发）
                RecyclerView.Adapter<?> adapter = recyclerView != null ? recyclerView.getAdapter() : null;
                if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                    ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).updateSongCacheStatus(songId, isCached);
                } else if (songAdapter != null) {
                    songAdapter.updateSongCacheStatus(songId, isCached);
                }
            }
        }
    };
    
    // 检测网络状态变化的广播接收器
    private BroadcastReceiver networkStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.net.conn.CONNECTIVITY_CHANGE")) {
                // 检查网络状态变化
                boolean newNetworkStatus = NetworkUtils.isNetworkAvailable(MainActivity.this);
                
                // 只有当状态发生变化时才进行处理
                if (isNetworkAvailable != newNetworkStatus) {
                    isNetworkAvailable = newNetworkStatus;
                    
                    // 更新Repository中的网络状态
                    musicRepository.setNetworkAvailable(isNetworkAvailable);
                    
                    // 显示网络状态变化提示
                    String message = isNetworkAvailable ? 
                            "网络已连接，将使用在线数据" : 
                            "网络已断开，将使用离线数据";
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    
                    // 如果切换到了在线模式，可以刷新数据
                    if (isNetworkAvailable && "albums".equals(currentView)) {
                        currentOffset = 0;
                        loadAlbums();
                    }
                }
            }
        }
    };

    private BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            String songId = intent.getStringExtra(DownloadManager.EXTRA_SONG_ID);
            if (songId == null || songAdapter == null) return;

            switch (action) {
                case DownloadManager.ACTION_DOWNLOAD_PROGRESS: {
                    int progress = intent.getIntExtra(DownloadManager.EXTRA_PROGRESS, 0);
                    RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                    if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                        ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).updateSongDownloadProgress(songId, progress);
                    } else if (songAdapter != null) {
                    songAdapter.updateSongDownloadProgress(songId, progress);
                    }
                    break; }
                case DownloadManager.ACTION_DOWNLOAD_COMPLETE: {
                    RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                    if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                        ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).updateSongDownloadStatus(songId);
                    } else if (songAdapter != null) {
                    songAdapter.updateSongDownloadStatus(songId);
                    }
                    Intent cacheIntent = new Intent("com.watch.limusic.CACHE_STATUS_CHANGED");
                    cacheIntent.putExtra("songId", songId);
                    cacheIntent.putExtra("isCached", true);
                    sendBroadcast(cacheIntent);
                    break; }
                case DownloadManager.ACTION_DOWNLOAD_FAILED: {
                    RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                    if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                        ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).updateSongDownloadStatus(songId);
                    } else if (songAdapter != null) {
                    songAdapter.updateSongDownloadStatus(songId);
                    }
                    break; }
                case DownloadManager.ACTION_DOWNLOAD_CANCELED: {
                    RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                    if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                        ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).updateSongDownloadStatus(songId);
                    } else if (songAdapter != null) {
                    songAdapter.updateSongDownloadStatus(songId);
                    }
                    break; }
            }
        }
    };

    // 数据库歌曲更新广播：刷新"所有歌曲"范围适配器的总数和字母映射
    private final BroadcastReceiver dbSongsUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"com.watch.limusic.DB_SONGS_UPDATED".equals(intent.getAction())) return;
            if (!"songs".equals(currentView)) return;
            try {
                // 若正在滚动，延迟合并刷新到空闲再处理，避免即时全量变更导致跳位
                if (lastKnownScrollState == RecyclerView.SCROLL_STATE_DRAGGING || lastKnownScrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                    pendingDbUpdate = true;
                    return;
                }
                RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                    int total = musicRepository.getSongCount();
                    java.util.Map<String, Integer> letterOffsets = musicRepository.getLetterOffsetMap();
                    com.watch.limusic.adapter.AllSongsRangeAdapter range = (com.watch.limusic.adapter.AllSongsRangeAdapter) adapter;
                    // 若总数变化，采用差量更新避免全量闪烁
                    int oldTotal = range.getTotalCount();
                    if (total != oldTotal) {
                        // 刷新前记录位置与偏移
                        int firstPos = 0; int firstOffset = 0;
                        try {
                            if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                                androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                                firstPos = lm.findFirstVisibleItemPosition();
                                android.view.View v = lm.findViewByPosition(firstPos);
                                firstOffset = v != null ? v.getTop() : 0;
                            }
                        } catch (Exception ignore) {}
                        range.applyTotalCountAndDiff(total);
                        range.setLetterOffsetMap(letterOffsets);
                        // 恢复位置
                        try {
                            if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                                ((androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(Math.max(0, firstPos), firstOffset);
                            }
                        } catch (Exception ignore) {}
                    } else {
                        range.setLetterOffsetMap(letterOffsets);
                    }
                    // 若存在待跳转的字母，入库完成后立即纠正到该字母首项并预取三页
                    if ("songs".equals(currentView) && pendingJumpLetter != null) {
                        Integer pos = letterOffsets.get(pendingJumpLetter);
                        if (pos != null && pos >= 0 && pos < total) {
                            if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                                ((androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
                            } else {
                                recyclerView.scrollToPosition(pos);
                            }
                            range.prefetchAround(pos);
                        }
                        pendingJumpLetter = null;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "刷新所有歌曲范围适配器失败: " + e.getMessage());
            }
        }
    };

    // 服务器配置更新广播：清库并跳转到"所有歌曲"且刷新
    private final BroadcastReceiver navidromeConfigUpdatedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!com.watch.limusic.api.NavidromeApi.ACTION_NAVIDROME_CONFIG_UPDATED.equals(intent.getAction())) return;
            try { com.watch.limusic.api.NavidromeApi.getInstance(MainActivity.this).reloadCredentials(); } catch (Exception ignore) {}
            try {
                // 清空歌曲表，避免旧服务器数据残留
                musicRepository.purgeAllOnServerSwitch();
            } catch (Exception ignore) {}
            try {
                // 写入最新服务器签名，避免后续 onStart 再次重复清库
                String sig = buildCurrentServerSignature();
                if (sig != null && !sig.isEmpty()) {
                    SharedPreferences sp = getSharedPreferences("ui_prefs", MODE_PRIVATE);
                    sp.edit().putString("last_server_signature", sig).apply();
                }
            } catch (Exception ignore) {}
            try {
                // 导航到"所有歌曲"并刷新
                resetUiForNewView();
                loadAllSongs();
                Toast.makeText(MainActivity.this, "已切换至新服务器，列表已刷新", Toast.LENGTH_SHORT).show();
            } catch (Exception ignore) {}
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PlayerService.LocalBinder binder = (PlayerService.LocalBinder) service;
            playerService = binder.getService();
            bound = true;
            // 立即告知服务UI可见
            playerService.setUiVisible(true); 
            updatePlaybackState();
            // 若是因用户点击播放而触发绑定，则开始播放
            if (pendingStartPlayOnBind) {
                pendingStartPlayOnBind = false;
                try { playerService.play(); } catch (Exception ignore) {}
            }
            // 若存在排队的点歌请求，优先设置播放列表；否则如是全局索引请求则从全局播放
            if (pendingPlaylist != null && !pendingPlaylist.isEmpty() && pendingPlaylistRebasedIndex >= 0) {
                try { playerService.setPlaylist(pendingPlaylist, pendingPlaylistRebasedIndex); } catch (Exception ignore) {}
                try {
                    android.content.SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                    boolean wantOpen = sp.getBoolean("auto_open_full_player", false);
                    if (wantOpen) {
                        if (!hasPerformedFirstUserPlay) {
                            hasPerformedFirstUserPlay = true; // 冷启动首次播放：跳过展开
                        } else {
                            openFullPlayer();
                        }
                    }
                } catch (Exception ignore) {}
                pendingPlaylist = null;
                pendingPlaylistRebasedIndex = -1;
            } else if (pendingPlaylist == null && pendingPlaylistRebasedIndex >= 0) {
                try { playerService.playAllSongsFromGlobal(pendingPlaylistRebasedIndex); } catch (Exception ignore) {}
                try {
                    android.content.SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                    boolean wantOpen = sp.getBoolean("auto_open_full_player", false);
                    if (wantOpen) {
                        if (!hasPerformedFirstUserPlay) {
                            hasPerformedFirstUserPlay = true; // 冷启动首次播放：跳过展开
                        } else {
                            openFullPlayer();
                        }
                    }
                } catch (Exception ignore) {}
                pendingPlaylistRebasedIndex = -1;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    private BroadcastReceiver playbackStateReceiver = new BroadcastReceiver() {
        // 添加一个变量来控制进度条更新频率
        private long lastProgressUpdateTime = 0;
        private static final long PROGRESS_UPDATE_INTERVAL = 500; // 毫秒

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.watch.limusic.PLAYBACK_STATE_CHANGED")) {
                long position = intent.getLongExtra("position", 0);
                long duration = intent.getLongExtra("duration", 0);
                boolean durationUnset = (duration <= 0) || (duration == com.google.android.exoplayer2.C.TIME_UNSET);
                boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
                int mode = intent.getIntExtra("playbackMode", PlayerService.PLAYBACK_MODE_REPEAT_ALL);
                String title = intent.getStringExtra("title");
                String artist = intent.getStringExtra("artist");
                String albumId = intent.getStringExtra("albumId");
                String songIdFromSvc = intent.getStringExtra("songId");
                long fallbackDurationMs = intent.getLongExtra("fallbackDurationMs", 0L);
                boolean isSeekable = intent.getBooleanExtra("isSeekable", false);
                String audioType = intent.getStringExtra("audioType");
                lastAudioType = audioType;
                lastIsSeekable = isSeekable;
                lastDurationUnset = durationUnset;
                if (songIdFromSvc != null && !songIdFromSvc.equals(lastSongIdFromBroadcast)) {
                    lastSongIdFromBroadcast = songIdFromSvc;
                    // 曲目变更时清空跨曲目的待提交seek
                    pendingSeekMs = -1;
                    pendingSeekSongId = null;
                }
                
                // 更新播放/暂停按钮
                playPauseButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                
                // 更新歌曲信息（只有当标题真正变化时才更新，避免重置滚动）
                if (title != null && !title.equals(songTitle.getText().toString())) {
                    songTitle.setText(title);
                    // 重新设置滚动属性
                    songTitle.setSelected(true);
                }
                if (artist != null) songArtist.setText(artist);
                // 全屏播放器：同步标题/艺术家并保持标题走马灯
                if (isFullPlayerVisible) {
                    if (fullSongTitle != null && title != null && !title.equals(fullSongTitle.getText() != null ? fullSongTitle.getText().toString() : "")) {
                        fullSongTitle.setText(title);
                        try { fullSongTitle.setSelected(true); } catch (Exception ignore) {}
                    }
                    if (fullSongArtist != null && artist != null && !userIsSeeking) {
                        fullSongArtist.setText(artist);
                    }
                }
                
                // 计算可用的时长：优先用 Exo 的 duration，若无则用服务端提供的 fallback，再不行保持为0（显示 --:--）
                long effectiveDuration = (!durationUnset && duration > 0) ? duration : (fallbackDurationMs > 0 ? fallbackDurationMs : 0);

                // 曲目切换或时长未知时的保护：避免残留上一首的末尾进度
                boolean isDurationUnknown = effectiveDuration <= 0;
                boolean titleChanged = title != null && !title.equals(songTitle.getText() != null ? songTitle.getText().toString() : "");
                if (isDurationUnknown || titleChanged) {
                    try {
                        seekBar.setMax((int) Math.max(0, effectiveDuration));
                        seekBar.setProgress(0);
                        progressBar.setMax((int) Math.max(0, effectiveDuration));
                        progressBar.setProgress(0);
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.invalidate();
                        // 时间显示：未知总时长时显示 00:00 / --:--
                        if (timeDisplay != null) {
                            if (effectiveDuration > 0) {
                                updateTimeDisplay(0, effectiveDuration);
                            } else {
                                timeDisplay.setText("00:00 / --:--");
                            }
                        }
                    } catch (Exception ignore) {}
                }

                // 优化进度条更新：只有在不拖动且满足更新间隔时才更新进度条
                long currentTime = System.currentTimeMillis();
                if (!userIsSeeking && currentTime - lastProgressUpdateTime >= PROGRESS_UPDATE_INTERVAL) {
                    lastProgressUpdateTime = currentTime;
                    if (!suppressProgressFromBroadcast || currentTime > suppressUntilMs) {
                        suppressProgressFromBroadcast = false;
                        if (seekBar != null && effectiveDuration > 0) {
                    seekBar.setMax((int) effectiveDuration);
                        seekBar.setProgress((int) position);
                        }
                        if (progressBar != null && effectiveDuration > 0) {
                            progressBar.setMax((int) effectiveDuration);
                        progressBar.setProgress((int) position);
                        }
                        if (isFullPlayerVisible && !userIsSeeking && effectiveDuration > 0) {
                        if (fullSeekBar != null) fullSeekBar.setProgress((int) position);
                        if (fullProgress != null) fullProgress.setProgress((int) position);
                        }
                    }
                    if (timeDisplay != null && effectiveDuration > 0) {
                        updateTimeDisplay(position, effectiveDuration);
                    }
                }

                // 歌曲切换时刷新歌词数据
                if (songIdFromSvc != null) {
                    try {
                        SharedPreferences sp2 = getSharedPreferences("player_prefs", MODE_PRIVATE);
                        String lastSid = sp2.getString("last_song_id_for_lyrics", null);
                        if (!songIdFromSvc.equals(lastSid)) {
                            sp2.edit().putString("last_song_id_for_lyrics", songIdFromSvc).apply();
                            if (lyricsController != null) {
                                lyricsController.loadLyricsIfNeeded();
                            }
                        }
                    } catch (Exception ignore) {}
                }

                // 触发一次歌词tick（仅换行刷新）
                if (lyricsController != null) {
                    lyricsController.onPlaybackTick();
                }

                // 分发"当前播放"状态给当前列表适配器
                try {
                    RecyclerView.Adapter<?> adapter = recyclerView != null ? recyclerView.getAdapter() : null;
                    if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                        ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).setCurrentPlaying(songIdFromSvc, isPlaying);
                        updateLocateButtonVisibility(songIdFromSvc);
                    } else if (songAdapter != null && adapter == songAdapter) {
                        songAdapter.setCurrentPlaying(songIdFromSvc, isPlaying);
                        updateLocateButtonVisibility(songIdFromSvc);
                    } else if ("downloads".equals(currentView) && downloadedSongAdapter != null) {
                        downloadedSongAdapter.setCurrentPlaying(songIdFromSvc, isPlaying);
                        updateLocateButtonVisibility(songIdFromSvc);
                    }
                } catch (Exception ignore) {}
                
                // 更新播放模式按钮
                updatePlaybackModeButton();

                // 若全屏播放器可见，同步刷新其UI与背景（当 songId 变化）
                if (isFullPlayerVisible && fullPlayerOverlay != null) {
                    if (btnPlayPauseFull != null) {
                        btnPlayPauseFull.setImageResource(isPlaying ? R.drawable.ic_pause_rounded : R.drawable.ic_play_rounded);
                    }
                    // 音频类型徽标显示（广播未携带时尝试主动查询）
                    try {
                        boolean show = getSharedPreferences("player_prefs", MODE_PRIVATE).getBoolean("show_audio_type_badge", true);
                        if (fullAudioBadge != null) {
                            String badge = audioType;
                            if ((badge == null || badge.isEmpty())) {
                                badge = safeGetAudioType();
                            }
                            if (show && badge != null && !badge.isEmpty()) {
                                fullAudioBadge.setText(badge);
                                fullAudioBadge.setVisibility(View.VISIBLE);
                            } else {
                                fullAudioBadge.setVisibility(View.GONE);
                            }
                        }
                    } catch (Exception ignore) {}
                    if (effectiveDuration > 0) {
                        if (fullSeekBar != null) fullSeekBar.setMax((int) effectiveDuration);
                        if (fullProgress != null) fullProgress.setMax((int) effectiveDuration);
                        if (!userIsSeeking && (currentTime - lastProgressUpdateTime > PROGRESS_UPDATE_INTERVAL || position == 0)) {
                            if (fullSeekBar != null) fullSeekBar.setProgress((int) position);
                            if (fullProgress != null) fullProgress.setProgress((int) position);
                        }
                    }
                    // 若之前在时长未知时记录了待执行的 seek，且现在时长可用，则立即执行一次
                    if (pendingSeekMs >= 0 && effectiveDuration > 0 && bound && playerService != null && isSeekable && !durationUnset) {
                        int ps = pendingSeekMs;
                        pendingSeekMs = -1;
                        try { playerService.seekTo(Math.min(ps, (int) effectiveDuration)); } catch (Exception ignore) {}
                    }
                    updateFullPlaybackModeButton();
                    // 背景：若歌曲变更则刷新
                    if (songIdFromSvc != null && !songIdFromSvc.equals(lastBgSongId)) {
                        lastBgSongId = songIdFromSvc;
                        try { applyFullPlayerBackground(); } catch (Exception ignore) {}
                    }
                }
                // 全局：仅当待提交seek绑定的歌曲ID与当前广播的songId一致时，才执行延后seek
                if (pendingSeekMs >= 0 && effectiveDuration > 0 && bound && playerService != null && isSeekable && !durationUnset) {
                    boolean sameSong = (pendingSeekSongId != null && pendingSeekSongId.equals(songIdFromSvc));
                    if (sameSong) {
                        int ps2 = pendingSeekMs;
                        pendingSeekMs = -1;
                        pendingSeekSongId = null;
                        try { playerService.seekTo(Math.min(ps2, (int) effectiveDuration)); } catch (Exception ignore) {}
                    }
                }

                // 更新专辑封面与全屏播放器背景（基于albumId或songId变化）
                if (albumId != null && !albumId.isEmpty() && !albumId.equals(lastAlbumId)) {
                    lastAlbumId = albumId;
                    String coverArtUrl;
                    String localCover = MainActivity.this.localFileDetector.getDownloadedAlbumCoverPath(albumId);
                    if (localCover != null) {
                        coverArtUrl = "file://" + localCover;
                    } else {
                        coverArtUrl = NavidromeApi.getInstance(context).getCoverArtUrl(albumId);
                    }
                    boolean isLocal = coverArtUrl.startsWith("file://");
                    if (isLocal) {
                        Glide.with(context)
                            .load(coverArtUrl)
                            .override(150, 150)
                            .placeholder(R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(albumArt);
                    } else {
                        Glide.with(context)
                            .load(coverArtUrl)
                            .override(150, 150)
                            .placeholder(R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .signature(new ObjectKey(albumId))
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .into(albumArt);
                    }
                    // songId 或 albumId 变化时，立即刷新全屏播放器背景
                    if (isFullPlayerVisible && (songIdFromSvc != null && !songIdFromSvc.equals(lastBgSongId))) {
                        lastBgSongId = songIdFromSvc;
                        try { applyFullPlayerBackground(); } catch (Exception ignore) {}
                    }
                }
            }
        }
    };

    private com.watch.limusic.adapter.PlaylistAdapter playlistAdapter;
    private com.watch.limusic.repository.PlaylistRepository playlistRepository;

    // 歌单相关广播接收器
    private final BroadcastReceiver playlistsUpdatedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("playlists".equals(currentView)) {
                // 若正在滚动，延迟刷新到空闲后合并，避免抖动
                if (lastKnownScrollState == RecyclerView.SCROLL_STATE_DRAGGING || lastKnownScrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                    pendingDbUpdate = true;
                    return;
                }
                // 轻量去抖：空闲后延迟一点再刷新
                recyclerView.postDelayed(() -> {
                    if ("playlists".equals(currentView) && lastKnownScrollState == RecyclerView.SCROLL_STATE_IDLE) {
                        loadPlaylists();
                    }
                }, 180);
            }
        }
    };
    private final BroadcastReceiver playlistChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("playlist_detail".equals(currentView)) {
                long pid = intent.getLongExtra("playlistLocalId", -1L);
                long now = System.currentTimeMillis();
                if (pid > 0) {
                    if (pid == suppressPlaylistLocalId && now < suppressPlaylistChangedUntilMs) return;
                    // 滚动期间延迟刷新，空闲后再合并
                    if (lastKnownScrollState == RecyclerView.SCROLL_STATE_DRAGGING || lastKnownScrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                        pendingDbUpdate = true;
                        return;
                    }
                    recyclerView.postDelayed(() -> {
                        if (!"playlist_detail".equals(currentView)) return;
                        new Thread(() -> {
                            List<com.watch.limusic.model.Song> songs2 = playlistRepository.getSongsInPlaylist(pid, 500, 0);
                            runOnUiThread(() -> songAdapter.processAndSubmitListKeepOrder(songs2));
                        }).start();
                    }, 150);
                }
            }
        }
    };

    // 选择模式状态
    private boolean selectionMode = false;
    private java.util.LinkedHashSet<String> selectedSongIds = new java.util.LinkedHashSet<>();
    
    // 当前打开的歌单ID（用于详情页刷新/拦截添加到自身）
    private long currentPlaylistLocalId = -1L;
    private String currentPlaylistTitle = null;
    
    // "所有歌曲"范围适配器引用
    private com.watch.limusic.adapter.AllSongsRangeAdapter rangeAdapter;

    // Swipe 刷新
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;

    // 当前专辑信息（用于标题恢复与刷新）
    private String currentAlbumId = null;
    private String currentAlbumTitle = null;

    // 拖动排序助手（歌单详情用）
    private androidx.recyclerview.widget.ItemTouchHelper itemTouchHelper;

	// 抑制窗口：重排提交后短时间内忽略自身歌单的刷新广播，避免松手后列表跳动
	private long suppressPlaylistChangedUntilMs = 0L;
	private long suppressPlaylistLocalId = -1L;

    private View fullPlayerOverlay; // 全屏播放器覆盖层根视图
    private ViewStub fullPlayerStub; // 懒加载占位
    private boolean isFullPlayerVisible = false; // 覆盖层显示状态

    // 全屏播放器控件引用
    private ImageButton btnPrevFull;
    private ImageButton btnPlayPauseFull;
    private ImageButton btnNextFull;
    private ImageButton btnPlayModeFull;
    private SeekBar fullSeekBar;
    private ProgressBar fullProgress;
    private TextView fullSongTitle;
    private TextView fullSongArtist;
    private TextView fullAudioBadge;
    private ImageButton btnVolumeToggle;
    private ImageButton btnMore;
    private View volumeOverlay;
    private SeekBar volumeSeek;
    private View sleepOverlay;
    private SeekBar sleepSeek;
    private android.widget.CheckBox sleepAfterCurrentCheck;
    private AudioManager audioManager;
    private ImageView fullBgImage;

    // 新增：歌词控制器与页指示器
    private com.watch.limusic.LyricsController lyricsController;
    private android.widget.ImageView dotMain;
    private android.widget.ImageView dotLyrics;
    private int currentFullPage = 0; // 0=主控, 1=歌词
    
    // 新增：冷启动首次点击播放时不自动展开全屏播放器
    private boolean hasPerformedFirstUserPlay = false;
    
    // 统一消息提示队列，避免短时间多条提示叠加
    private final java.util.ArrayDeque<String> tipQueue = new java.util.ArrayDeque<>();
    private boolean tipShowing = false;
    private final android.os.Handler tipHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tipDequeueRunnable = new Runnable() {
        @Override public void run() {
            tipShowing = false;
            showNextTipIfAny();
        }
    };
    
    private void updateLocateButtonVisibility(String currentSongId) {
        if (btnLocateCurrent == null || recyclerView == null) return;
        try {
            RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
            // 下载管理页（ConcatAdapter）：定位到"已下载"分区
            if ("downloads".equals(currentView) && adapter instanceof androidx.recyclerview.widget.ConcatAdapter) {
                String sid = currentSongId;
                if (sid == null) {
                    if (bound && playerService != null && playerService.getCurrentSong() != null) {
                        sid = playerService.getCurrentSong().getId();
                    } else {
                        SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                        sid = sp.getString("last_song_id", null);
                    }
                }
                if (sid == null || downloadedSongAdapter == null) { btnLocateCurrent.setVisibility(View.GONE); return; }
                int innerPos = downloadedSongAdapter.getPositionBySongId(sid);
                if (innerPos < 0) { btnLocateCurrent.setVisibility(View.GONE); return; }
                int offset = 0;
                offset += 1; // headerActive
                if (downloadsHeaderActiveExpanded && downloadTaskAdapter != null) offset += downloadTaskAdapter.getItemCount();
                offset += 1; // headerCompleted
                int absPos = offset + innerPos;
                if (!(recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager)) {
                    btnLocateCurrent.setVisibility(View.VISIBLE);
                    return;
                }
                androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                int first = lm.findFirstVisibleItemPosition();
                int last = lm.findLastVisibleItemPosition();
                boolean inView = absPos >= first && absPos <= last;
                btnLocateCurrent.setVisibility(inView ? View.GONE : View.VISIBLE);
                return;
            }

            // 仅在歌曲列表或歌单详情使用
            boolean isSongList = (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) || (adapter == songAdapter);
            if (!isSongList) { btnLocateCurrent.setVisibility(View.GONE); return; }

            String sid = currentSongId;
            if (sid == null) {
                if (bound && playerService != null && playerService.getCurrentSong() != null) {
                    sid = playerService.getCurrentSong().getId();
                } else {
                    SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                    sid = sp.getString("last_song_id", null);
                }
            }
            if (sid == null) { btnLocateCurrent.setVisibility(View.GONE); return; }

            int pos = -1;
            if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                pos = ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).getPositionBySongId(sid);
            } else if (songAdapter != null) {
                pos = songAdapter.getPositionBySongId(sid);
            }
            // 未加载（pos<0）也显示按钮，允许触发预取+定位
            if (pos < 0) { btnLocateCurrent.setVisibility(View.VISIBLE); return; }

            if (!(recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager)) {
                btnLocateCurrent.setVisibility(View.VISIBLE);
                return;
            }
            androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
            int first = lm.findFirstVisibleItemPosition();
            int last = lm.findLastVisibleItemPosition();
            boolean inView = pos >= first && pos <= last;
            boolean show;
            if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                // 未加载到目标歌曲时也显示按钮；加载后若不在可视范围也显示
                show = (pos < 0) || !inView;
            } else {
                show = !inView;
            }
            btnLocateCurrent.setVisibility(show ? View.VISIBLE : View.GONE);
        } catch (Exception e) {
            btnLocateCurrent.setVisibility(View.GONE);
        }
    }

    private void locateCurrentPlaying() {
        try {
            if (recyclerView == null) return;
            RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
            if (adapter == null) return;
            String sid = null;
            if (bound && playerService != null && playerService.getCurrentSong() != null) {
                sid = playerService.getCurrentSong().getId();
            }
            if (sid == null) sid = getSharedPreferences("player_prefs", MODE_PRIVATE).getString("last_song_id", null);
            if (sid == null) return;

            // 下载管理页定位到"已下载"分区
            if ("downloads".equals(currentView) && adapter instanceof androidx.recyclerview.widget.ConcatAdapter) {
                if (downloadedSongAdapter == null) return; // 未初始化
                int innerPos = downloadedSongAdapter.getPositionBySongId(sid);
                if (innerPos < 0) return; // 未下载
                if (!downloadsHeaderCompletedExpanded) { downloadsHeaderCompletedExpanded = true; rebuildDownloadsConcat(); }
                int offset = 0;
                offset += 1; // headerActive
                if (downloadsHeaderActiveExpanded && downloadTaskAdapter != null) offset += downloadTaskAdapter.getItemCount();
                offset += 1; // headerCompleted
                int absPos = offset + innerPos;
                smoothCenterTo(absPos);
                return;
            }

            int targetPos = -1;
            if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                com.watch.limusic.adapter.AllSongsRangeAdapter ra = (com.watch.limusic.adapter.AllSongsRangeAdapter) adapter;
                targetPos = ra.getPositionBySongId(sid);
                if (targetPos < 0) {
                    // 使用数据库精确计算全局索引，避免偏差
                    int approx = -1;
                    try {
                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getSongById(sid);
                        if (se != null) {
                            String title = se.getTitle() != null ? se.getTitle() : "";
                            String ini = se.getInitial();
                            if (ini == null || ini.isEmpty()) {
                                try { ini = com.watch.limusic.util.PinyinUtil.getFirstLetter(title); } catch (Exception ignore) {}
                            }
                            int cat = 2;
                            if ("#".equals(ini)) cat = 0; else if (ini.length() == 1 && Character.isDigit(ini.charAt(0))) cat = 1;
                            approx = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getGlobalIndex(cat, ini, title);
                        }
                    } catch (Exception ignore) {}
                    if (approx < 0) {
                        // 回退：以当前可见作为锚点
                        try {
                            int anchor = 0;
                            if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                                anchor = ((androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager()).findFirstVisibleItemPosition();
                            }
                            approx = Math.max(0, anchor);
                        } catch (Exception ignore) { approx = 0; }
                    }
                    try { ra.prefetchAround(approx); } catch (Exception ignore) {}
                    try { smoothCenterTo(approx); } catch (Exception ignore) {}
                    final String finalSid = sid;
                    // 连续两次检查，加载到后立即居中
                    recyclerView.postDelayed(() -> {
                        int pos2 = ra.getPositionBySongId(finalSid);
                        if (pos2 >= 0) { smoothCenterTo(pos2); return; }
                        recyclerView.postDelayed(() -> {
                            int pos3 = ra.getPositionBySongId(finalSid);
                            if (pos3 >= 0) { smoothCenterTo(pos3); return; }
                            // 第三次兜底检查
                            recyclerView.postDelayed(() -> {
                                int pos4 = ra.getPositionBySongId(finalSid);
                                if (pos4 >= 0) smoothCenterTo(pos4);
                            }, 320);
                        }, 260);
                    }, 160);
                    return;
                }
            } else if (songAdapter != null) {
                targetPos = songAdapter.getPositionBySongId(sid);
            }
            if (targetPos >= 0) {
                // 居中定位
                smoothCenterTo(targetPos);
            }
        } catch (Exception ignore) {}
    }

    private void smoothCenterTo(int adapterPosition) {
        try {
            if (!(recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager)) {
                recyclerView.scrollToPosition(adapterPosition);
                return;
            }
            final androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
            final int rvHeight = recyclerView.getHeight();
            final int topPad = recyclerView.getPaddingTop();
            final int bottomPad = recyclerView.getPaddingBottom();
            final int avail = Math.max(0, rvHeight - topPad - bottomPad);
            int itemH = 0;
            try {
                int first = lm.findFirstVisibleItemPosition();
                if (first >= 0) {
                    View any = lm.findViewByPosition(first);
                    if (any != null && any.getHeight() > 0) itemH = any.getHeight();
                }
            } catch (Exception ignore) {}
            if (itemH <= 0) itemH = dpToPx(52);
            final int coarseOffset = topPad + Math.max(0, (avail - itemH) / 2);
            lm.scrollToPositionWithOffset(adapterPosition, coarseOffset);
            final int finalItemH = itemH;
            recyclerView.post(() -> {
                try {
                    View tgt = lm.findViewByPosition(adapterPosition);
                    int h = (tgt != null && tgt.getHeight() > 0) ? tgt.getHeight() : finalItemH;
                    int exactOffset = topPad + Math.max(0, (avail - h) / 2);
                    lm.scrollToPositionWithOffset(adapterPosition, exactOffset);
                } catch (Exception ignore) {}
            });
        } catch (Exception e) {
            try { recyclerView.scrollToPosition(adapterPosition); } catch (Exception ignore) {}
        }
    }

    private void smoothCenterToOffset(int adapterPosition, int extraOffsetPx) {
        // 兼容旧调用：直接居中，不做额外偏移
        smoothCenterTo(adapterPosition);
    }

    private final BroadcastReceiver uiSettingsChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!"com.watch.limusic.UI_SETTINGS_CHANGED".equals(intent.getAction())) return;
            if (isFullPlayerVisible) {
                try { applyFullPlayerBackground(); } catch (Exception ignore) {}
                // 省电模式切换时，更新页指示器和可见性（省电下禁用歌词页）
                try {
                    if (isLowPowerEnabled()) {
                        currentFullPage = 0;
                        applyFullPageVisibility();
                    }
                    updatePageIndicator();
                } catch (Exception ignore) {}
                // 设置变更时也同步一次徽标显示
                trySyncFullPlayerUi();
            }
        }
    };

    private String lastBgSongId = null;

    private boolean suppressProgressFromBroadcast = false;
    private long suppressUntilMs = 0L;
    private int pendingSeekMs = -1;
    private boolean lastIsSeekable = false;
    private boolean lastDurationUnset = true;
    private String lastSongIdFromBroadcast = null;
    private String pendingSeekSongId = null;
    private String lastAudioType = null;

    // 新增：实时拖拽切页状态
    private boolean isDraggingPages = false;
    private float dragStartX = 0f;
    private float dragStartY = 0f;
    private float lastDragDx = 0f;
    private int horizontalTouchSlopPx = 0;
    private boolean isSettling = false;

    private int dpToPx(int dp) { return Math.round(getResources().getDisplayMetrics().density * dp); }

    private View findViewByIdName(View root, String idName) {
        try {
            int id = getResources().getIdentifier(idName, "id", getPackageName());
            return id != 0 && root != null ? root.findViewById(id) : null;
        } catch (Exception e) { return null; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置主题
        setTheme(R.style.AppTheme);
        
        // 设置状态栏颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(getResources().getColor(R.color.background));
            window.setNavigationBarColor(getResources().getColor(R.color.background));
        }
        
        setContentView(R.layout.activity_main);

        // 初始化覆盖层引用（懒加载）
        fullPlayerStub = findViewById(R.id.full_player_stub) instanceof ViewStub ? (ViewStub) findViewById(R.id.full_player_stub) : null;
        fullPlayerOverlay = null;

        // 初始化Repository
        musicRepository = MusicRepository.getInstance(this);
        playlistRepository = com.watch.limusic.repository.PlaylistRepository.getInstance(this);

        // 初始化下载管理器
        downloadManager = DownloadManager.getInstance(this);
        localFileDetector = new LocalFileDetector(this);

        // 绑定占位/空态/错误态视图
        skeletonContainer = findViewById(R.id.skeleton_container);
        emptyContainer = findViewById(R.id.empty_container);
        errorContainer = findViewById(R.id.error_container);
        errorMessageView = findViewById(R.id.error_message);
        emptyMessageView = findViewById(R.id.empty_message);
        retryEmptyButton = findViewById(R.id.btn_retry_empty);
        retryErrorButton = findViewById(R.id.btn_retry_error);
        bottomHintBar = findViewById(R.id.bottom_hint_bar);

        View.OnClickListener retry = v -> {
            showSkeleton();
            if ("playlists".equals(currentView)) {
                loadPlaylists();
            } else if ("songs".equals(currentView)) {
                loadAllSongs();
            } else if ("albums".equals(currentView)) {
                loadAlbums();
            } else {
                loadAlbums();
            }
        };
        retryEmptyButton.setOnClickListener(retry);
        retryErrorButton.setOnClickListener(retry);

        // 处理可能的外部请求：打开某个歌单详情
        try {
            long reqPid = getIntent() != null ? getIntent().getLongExtra("open_playlist_local_id", -1L) : -1L;
            if (reqPid > 0) {
                // 读取名称以更新标题
                com.watch.limusic.database.PlaylistEntity pe = com.watch.limusic.database.MusicDatabase.getInstance(this).playlistDao().getByLocalId(reqPid);
                String name = pe != null ? pe.getName() : "";
                openPlaylistDetail(reqPid, name);
                // 清理一次性参数，避免后续 onResume 重复触发
                getIntent().removeExtra("open_playlist_local_id");
            }
        } catch (Exception ignore) {}

        // 初始展示骨架屏
        showSkeleton();

        // 执行下载系统迁移（如果需要）
        performDownloadSystemMigration();

        // 检查网络状态
        isNetworkAvailable = NetworkUtils.isNetworkAvailable(this);
        musicRepository.setNetworkAvailable(isNetworkAvailable);

        // 初始化视图
        initViews();
        
        // 设置导航抽屉
        setupDrawer();
        
        // 设置RecyclerView
        setupRecyclerView();
        
        // 设置播放控制
        setupPlaybackControls();
        
        // 绑定小播放器信息区点击以打开全屏播放器
        View miniInfo = findViewById(R.id.mini_player_info_container);
        if (miniInfo != null) {
            miniInfo.setOnClickListener(v -> openFullPlayer());
        }
        
        // 将小播放器的专辑封面区域也作为打开全屏播放器的入口
        View albumArtView = findViewById(R.id.album_art);
        if (albumArtView != null) {
            albumArtView.setOnClickListener(v -> openFullPlayer());
        }
        
        // 在未绑定服务时，先从本地偏好恢复上次离开时的播放器UI（仅UI，不启动播放）
        restorePlayerUiFromPrefs();
        
        // 检查配置，加载数据
        checkConfiguration();
        
        // 注册缓存状态变化广播接收器
        IntentFilter cacheFilter = new IntentFilter("com.watch.limusic.CACHE_STATUS_CHANGED");
        registerReceiver(cacheStatusReceiver, cacheFilter);
        
        // 注册网络状态变化广播接收器
        IntentFilter networkFilter = new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE");
        registerReceiver(networkStatusReceiver, networkFilter);
        
        // UI设置变更广播（常驻，保证在设置页顶起时也能接收）
        try { registerReceiver(uiSettingsChangedReceiver, new IntentFilter("com.watch.limusic.UI_SETTINGS_CHANGED")); } catch (Exception ignore) {}
        
        // 注册下载相关的本地广播
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_PROGRESS));
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_FAILED));
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_CANCELED));
        lbm.registerReceiver(downloadsUiReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_PROGRESS));
        lbm.registerReceiver(downloadsUiReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        lbm.registerReceiver(downloadsUiReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_FAILED));
        lbm.registerReceiver(downloadsUiReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_CANCELED));
        
        // 启动时预加载数据
        preloadData();
    }
    
    /**
     * 预加载应用数据，确保离线时有数据可用
     */
    private void preloadData() {
        if (isNetworkAvailable) {
            new Thread(() -> {
                try {
                    try { com.watch.limusic.api.NavidromeApi.getInstance(this).reloadCredentials(); } catch (Exception ignore) {}
                    // 获取数据库中的歌曲数量
                    int songCount = musicRepository.getSongCount();
                    Log.d(TAG, "启动时预加载 - 数据库中的歌曲数量: " + songCount);
                    
                    // 如果数据库中的歌曲数量太少，预加载一些歌曲
                    if (songCount < 50) {
                        Log.d(TAG, "启动时预加载 - 数据库中歌曲数量不足，开始预加载");
                        
                        // 若配置尚未就绪，跳过本次预加载
                        if (!isApiConfiguredSafe()) return;
                        
                        // 加载所有歌曲
                        List<Song> songs = NavidromeApi.getInstance(this).getAllSongs();
                        if (songs != null && !songs.isEmpty()) {
                            musicRepository.saveSongsToDatabase(songs);
                            Log.d(TAG, "启动时预加载 - 保存了 " + songs.size() + " 首歌曲到数据库");
                        }
                        
                        // 加载专辑
                        List<Album> albums = musicRepository.getAlbums("newest", 20, 0);
                        if (albums != null && !albums.isEmpty()) {
                            Log.d(TAG, "启动时预加载 - 获取了 " + albums.size() + " 张专辑");
                            
                            // 对于每个专辑，预加载并保存其歌曲到数据库
                            for (Album album : albums) {
                                try {
                                    if (!isApiConfiguredSafe()) break;
                                    List<Song> albumSongs = NavidromeApi.getInstance(this).getAlbumSongs(album.getId());
                                    if (albumSongs != null && !albumSongs.isEmpty()) {
                                        musicRepository.saveSongsToDatabase(albumSongs);
                                        Log.d(TAG, "启动时预加载 - 保存专辑 " + album.getName() + " 的 " + albumSongs.size() + " 首歌曲");
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "启动时预加载 - 加载专辑 " + album.getName() + " 的歌曲失败", e);
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "启动时预加载 - 数据库中已有足够歌曲，跳过预加载");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "启动时预加载数据失败", e);
                }
            }).start();
        } else {
            Log.d(TAG, "启动时预加载 - 网络不可用，跳过预加载");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        
        // 延迟绑定服务：仅在需要播放或用户交互时再绑定
        // bindService();
        
        // 注册播放状态接收器
        IntentFilter filter = new IntentFilter("com.watch.limusic.PLAYBACK_STATE_CHANGED");
        registerReceiver(playbackStateReceiver, filter);
        
        // 监听数据库更新
                    try { registerReceiver(dbSongsUpdatedReceiver, new IntentFilter("com.watch.limusic.DB_SONGS_UPDATED")); } catch (Exception ignore) {}
            // 监听滚动状态，用于在滚动中延迟合并刷新
            try {
                recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                        super.onScrollStateChanged(rv, newState);
                        lastKnownScrollState = newState;
                        if (newState == RecyclerView.SCROLL_STATE_IDLE && pendingDbUpdate) {
                            pendingDbUpdate = false;
                            // 主动触发一次处理（相当于重发一遍 DB_SONGS_UPDATED 的处理）
                            try {
                                int total = musicRepository.getSongCount();
                                java.util.Map<String, Integer> letterOffsets = musicRepository.getLetterOffsetMap();
                                RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                                if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                                    com.watch.limusic.adapter.AllSongsRangeAdapter range = (com.watch.limusic.adapter.AllSongsRangeAdapter) adapter;
                                    int oldTotal = range.getTotalCount();
                                    if (total != oldTotal) {
                                        int firstPos = 0; int firstOffset = 0;
                                        try {
                                            if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                                                androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                                                firstPos = lm.findFirstVisibleItemPosition();
                                                android.view.View v = lm.findViewByPosition(firstPos);
                                                firstOffset = v != null ? v.getTop() : 0;
                                            }
                                        } catch (Exception ignore2) {}
                                        range.applyTotalCountAndDiff(total);
                                        range.setLetterOffsetMap(letterOffsets);
                                        try {
                                            if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                                                ((androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(Math.max(0, firstPos), firstOffset);
                                            }
                                        } catch (Exception ignore3) {}
                                    } else {
                                        range.setLetterOffsetMap(letterOffsets);
                                    }
                                }
                            } catch (Exception ignore4) {}
                        }
                    }
                });
            } catch (Exception ignore) {}
        
        // 监听服务器配置更新
        try { registerReceiver(navidromeConfigUpdatedReceiver, new IntentFilter(com.watch.limusic.api.NavidromeApi.ACTION_NAVIDROME_CONFIG_UPDATED)); } catch (Exception ignore) {}

        // 服务器签名自检：若变更则清库并刷新"所有歌曲"
        try { checkAndHandleServerSignatureChange(); } catch (Exception ignore) {}
        
        // 设置UI可见性标志（如已绑定再标记）
        if (bound && playerService != null) {
            playerService.setUiVisible(true);
        }
        
        // 若正在播放或最近在播，则条件绑定服务以恢复心跳广播
        maybeBindIfPlaying();

        try { registerReceiver(uiSettingsChangedReceiver, new IntentFilter("com.watch.limusic.UI_SETTINGS_CHANGED")); } catch (Exception ignore) {}
        // 返回前台时，如全屏播放器可见，按最新设置立即应用背景
        if (isFullPlayerVisible) {
            try { applyFullPlayerBackground(); } catch (Exception ignore) {}
            // 同步一次徽标显示
            trySyncFullPlayerUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // 刷新歌曲缓存状态
        if (songAdapter != null && "songs".equals(currentView)) {
            songAdapter.refreshCacheStatus();
        }
        
        // 检查网络状态并更新
        boolean newNetworkStatus = NetworkUtils.isNetworkAvailable(this);
        if (isNetworkAvailable != newNetworkStatus) {
            isNetworkAvailable = newNetworkStatus;
            musicRepository.setNetworkAvailable(isNetworkAvailable);
        }
        
        // 确保数据库中有足够的歌曲数据
        new Thread(() -> {
            try {
                // 获取数据库中的歌曲数量
                int songCount = musicRepository.getSongCount();
                Log.d(TAG, "数据库中的歌曲数量: " + songCount);
                
                // 如果数据库中的歌曲数量太少，并且有网络连接，则预加载一些歌曲
                if (songCount < 10 && isNetworkAvailable) {
                    Log.d(TAG, "数据库中歌曲数量不足，预加载歌曲");
                    try { com.watch.limusic.api.NavidromeApi.getInstance(this).reloadCredentials(); } catch (Exception ignore) {}
                    if (!isApiConfiguredSafe()) return;
                    List<Song> songs = NavidromeApi.getInstance(this).getAllSongs();
                    if (songs != null && !songs.isEmpty()) {
                        musicRepository.saveSongsToDatabase(songs);
                        Log.d(TAG, "预加载了 " + songs.size() + " 首歌曲到数据库");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "预加载歌曲失败", e);
            }
        }).start();

        // 全屏可见时校正当前页显隐，避免叠层混合
        if (isFullPlayerVisible && fullPlayerOverlay != null) {
            // 始终先回主控页再应用一次，再切回当前记录页，确保状态一致
            int keep = currentFullPage;
            currentFullPage = 0;
            applyFullPageVisibility();
            updatePageIndicator();
            if (keep == 1) {
                currentFullPage = 1;
                applyFullPageVisibility();
                updatePageIndicator();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        
        // 取消注册播放状态接收器
        try {
            unregisterReceiver(playbackStateReceiver);
        } catch (IllegalArgumentException e) {
            // 接收器可能未注册，忽略
        }
        
        // 取消注册 DB 更新接收器
        try { unregisterReceiver(dbSongsUpdatedReceiver); } catch (Exception ignore) {}
        try { unregisterReceiver(navidromeConfigUpdatedReceiver); } catch (Exception ignore) {}
        
        // 设置UI不可见标志
        if (bound && playerService != null) {
            playerService.setUiVisible(false);
        }
        // 退出时更新滚动位置与当前视图
        if ("album_songs".equals(currentView)) {
            saveLastView("album_songs", currentAlbumId, currentAlbumTitle);
        } else if ("playlist_detail".equals(currentView)) {
            saveLastView("playlist_detail", currentPlaylistLocalId);
        } else {
            saveLastView(currentView);
        }

        try { unregisterReceiver(uiSettingsChangedReceiver); } catch (Exception ignore) {}
    }

    private void initViews() {
        // 初始化视图
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        recyclerView = findViewById(R.id.recycler_view);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        if (swipeRefresh != null) {
            swipeRefresh.setEnabled(true);
            swipeRefresh.setOnRefreshListener(() -> {
                if ("playlists".equals(currentView)) {
                    new Thread(() -> {
                        try { playlistRepository.syncPlaylistsHeader(); } catch (Exception ignore) {}
                        try { com.watch.limusic.database.MusicDatabase.getInstance(this).playlistDao().deleteOrphanLocalPlaylists(); } catch (Exception ignore) {}
                        runOnUiThread(() -> { loadPlaylists(); swipeRefresh.setRefreshing(false); });
                    }).start();
                } else if ("playlist_detail".equals(currentView)) {
                    long pid = currentPlaylistLocalId;
                    new Thread(() -> {
                        boolean refreshed = playlistRepository.validateAndMaybeRefreshFromServer(pid);
                        java.util.List<com.watch.limusic.model.Song> s2 = playlistRepository.getSongsInPlaylist(pid, 500, 0);
                        runOnUiThread(() -> {
                            if (songAdapter != null) songAdapter.processAndSubmitListKeepOrder(s2);
                            swipeRefresh.setRefreshing(false);
                        });
                    }).start();
                } else if ("songs".equals(currentView)) {
                    new Thread(() -> {
                        try {
                            if (isNetworkAvailable) {
                                java.util.List<com.watch.limusic.model.Song> online = com.watch.limusic.api.NavidromeApi.getInstance(this).getAllSongs();
                                if (online != null && !online.isEmpty()) musicRepository.saveSongsToDatabase(online);
                            }
                        } catch (Exception ignore) {}
                        runOnUiThread(() -> {
                            if (rangeAdapter != null) {
                                rangeAdapter.clearCache();
                                int total = musicRepository.getSongCount();
                                java.util.Map<String, Integer> letterOffsets = musicRepository.getLetterOffsetMap();
                                rangeAdapter.setTotalCount(total);
                                rangeAdapter.setLetterOffsetMap(letterOffsets);
                                rangeAdapter.prefetch(0);
                            }
                            swipeRefresh.setRefreshing(false);
                        });
                    }).start();
                } else if ("albums".equals(currentView)) {
                    // 重新加载专辑列表
                    loadAlbums();
                    swipeRefresh.setRefreshing(false);
                } else if ("album_songs".equals(currentView)) {
                    final String aid = currentAlbumId;
                    new Thread(() -> {
                        java.util.List<com.watch.limusic.model.Song> songs = musicRepository.getAlbumSongs(aid);
                        runOnUiThread(() -> {
                            if (songAdapter == null) songAdapter = new SongAdapter(MainActivity.this, MainActivity.this);
                            recyclerView.setAdapter(songAdapter);
                            songAdapter.setShowCoverArt(false);
                            songAdapter.processAndSubmitList(songs);
                            swipeRefresh.setRefreshing(false);
                        });
                    }).start();
                } else {
                    swipeRefresh.setRefreshing(false);
                }
            });
        }
        albumArt = findViewById(R.id.album_art);
        songTitle = findViewById(R.id.song_title);
        songArtist = findViewById(R.id.song_artist);
        playPauseButton = findViewById(R.id.btn_play_pause);
        repeatModeButton = findViewById(R.id.btn_repeat_mode);
        seekBar = findViewById(R.id.seek_bar);
        progressBar = findViewById(R.id.progress_bar);
        toolbar = findViewById(R.id.toolbar);
        timeDisplay = findViewById(R.id.time_display);
        btnLocateCurrent = findViewById(R.id.btn_locate_current);
        if (btnLocateCurrent != null) {
            btnLocateCurrent.setOnClickListener(v -> locateCurrentPlaying());
        }
        // 下载管理：移除任何全局触摸拦截可能干扰长按
        try { recyclerView.setOnTouchListener(null); } catch (Exception ignore) {}

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("专辑");
        }
        
        // 设置导航视图监听器
        navigationView.setNavigationItemSelectedListener(this);

        // 注册歌单相关广播：头部变化与明细变化
        try {
            registerReceiver(playlistsUpdatedReceiver, new android.content.IntentFilter("com.watch.limusic.PLAYLISTS_UPDATED"));
        } catch (Exception ignore) {}
        try {
            registerReceiver(playlistChangedReceiver, new android.content.IntentFilter("com.watch.limusic.PLAYLIST_CHANGED"));
        } catch (Exception ignore) {}
        
        // 设置标题滚动效果
        setupTitleScrolling();
    }

    private void setupDrawer() {
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        
        // 保存引用，便于后续切换为返回箭头
        this.drawerToggle = toggle;
        
        // 禁用侧滑手势，但允许通过按钮打开
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
        
        // 确保左上角按钮可以点击（根视图下作为汉堡菜单开关）
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
        
        navigationView.setNavigationItemSelectedListener(this);
    }

    private void setupRecyclerView() {
        // 定位按钮初始可见性更新（在RecyclerView准备后）
        try { updateLocateButtonVisibility(null); } catch (Exception ignore) {}

        // 初始化布局为线性布局，用于专辑视图
        layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        
        // 优化RecyclerView性能
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        
        // 添加分割线
        DividerItemDecoration divider = new DividerItemDecoration(this, layoutManager.getOrientation());
        divider.setDrawable(ContextCompat.getDrawable(this, R.drawable.list_divider));
        recyclerView.addItemDecoration(divider);
        
        // 初始化适配器
        albumAdapter = new AlbumAdapter(this);
        songAdapter = new SongAdapter(this, this);

        // 设置下载监听器
        songAdapter.setOnDownloadClickListener(this);
        
        // 设置专辑点击事件
        albumAdapter.setOnAlbumClickListener(album -> {
            Log.d(TAG, "专辑点击监听器触发: " + album.getName() + ", ID: " + album.getId());
            
            // 确保专辑ID不为空
            if (album.getId() == null || album.getId().isEmpty()) {
                Toast.makeText(this, "专辑ID无效，无法加载歌曲", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 进入专辑详情前，记录当前专辑列表滚动位置
            recordAlbumsScrollPosition();
            
            // 加载专辑歌曲
            loadAlbumSongs(album.getId());
        });
        
        // 设置滚动监听
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                
                if (!isLoading && hasMoreData && currentView.equals("albums")) {
                    int visibleItemCount = layoutManager.getChildCount();
                    int totalItemCount = layoutManager.getItemCount();
                    int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();

                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 5) {
                        loadMoreAlbums();
                    }
                }
                // 滚动时更新定位按钮显隐（仅对歌曲列表有效）
                try { updateLocateButtonVisibility(null); } catch (Exception ignore) {}
            }
        });
        
        // 默认显示专辑列表
        recyclerView.setAdapter(albumAdapter);
        searchFooterAdapter = null;
        // 初始化时更新一次定位按钮显隐
        try { updateLocateButtonVisibility(null); } catch (Exception ignore) {}
    }

    private void setupPlaybackControls() {
        // 为整个播放控制区域添加点击监听器，防止点击事件穿透到下面的列表
        View playerControls = findViewById(R.id.player_controls);
        playerControls.setOnClickListener(v -> {
            // 点击播放控制区域时不做任何操作，只是消费点击事件
        });
        
        // 防止边缘滑动手势冲突
        View seekBarContainer = findViewById(R.id.seek_bar_container);
        seekBarContainer.setOnTouchListener((v, event) -> {
            // 消费所有触摸事件，防止它们传递到系统
            return true;
        });
        
        // 确保进度条可见，并明确设置为0进度
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressBar.invalidate();
        
        // 设置播放/暂停按钮点击事件
        playPauseButton.setOnClickListener(v -> {
            v.setPressed(true);
            togglePlayback();
        });
        
        repeatModeButton.setOnClickListener(v -> {
            v.setPressed(true);
            if (bound && playerService != null) {
                playerService.togglePlaybackMode();
                updatePlaybackModeButton();
            }
        });

        // 增强歌曲标题滚动效果
        setupTitleScrolling();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean shouldUpdatePlayer = true;
            
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // 只更新UI进度条，不立即更新播放器进度
                    progressBar.setProgress(progress);
                    
                    // 更新时间显示
                    if (isSeeking && bound && playerService != null) {
                        long dur = playerService.getDuration();
                        if (dur <= 0) {
                            // 尝试读取本地 DB 兜底时长
                            try {
                                SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                                String sid = sp.getString("last_song_id", null);
                                long fb = 0L;
                                if (sid != null) {
                                    com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).songDao().getSongById(sid);
                                    if (se != null && se.getDuration() > 0) fb = (long) se.getDuration() * 1000L;
                                }
                                updateTimeDisplay(progress, fb);
                            } catch (Exception ignore) { updateTimeDisplay(progress, 0); }
                        } else {
                            updateTimeDisplay(progress, dur);
                        }
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userIsSeeking = true;
                isSeeking = true;
                shouldUpdatePlayer = true;
                
                // 隐藏艺术家名称
                songArtist.setVisibility(View.GONE);
                
                // 显示时间文本
                timeDisplay.setVisibility(View.VISIBLE);
                timeDisplay.setText("00:00 / 00:00");
                
                if (bound && playerService != null) {
                    long dur = playerService.getDuration();
                    if (dur <= 0) {
                        long fb = 0L;
                        try {
                            SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                            String sid = sp.getString("last_song_id", null);
                            if (sid != null) {
                                com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).songDao().getSongById(sid);
                                if (se != null && se.getDuration() > 0) fb = (long) se.getDuration() * 1000L;
                            }
                        } catch (Exception ignore) {}
                        updateTimeDisplay(playerService.getCurrentPosition(), fb);
                    } else {
                        updateTimeDisplay(playerService.getCurrentPosition(), dur);
                    }
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userIsSeeking = false;
                isSeeking = false;
                
                // 隐藏时间文本
                timeDisplay.setVisibility(View.GONE);
                
                // 恢复显示艺术家名称
                songArtist.setVisibility(View.VISIBLE);
                
                // 仅在拖动结束时更新播放器进度，提高滑动流畅性
                if (bound && playerService != null) {
                    int progress = seekBar.getProgress();
                    if (seekBar.getMax() <= 0) {
                        pendingSeekMs = progress;
                        pendingSeekSongId = (playerService != null && playerService.getCurrentSong() != null) ? playerService.getCurrentSong().getId() : null;
                        Toast.makeText(MainActivity.this, "正在获取时长，稍后将跳转", Toast.LENGTH_SHORT).show();
                    } else {
                        // 仅在可寻址且时长有效时放行
                        long durNow = playerService.getDuration();
                        boolean okSeek = false;
                        try { okSeek = playerService != null && playerService.isPlaying(); } catch (Exception ignore) {}
                        boolean isSeekableNow = false;
                        try { isSeekableNow = bound; } catch (Exception ignore) {}
                        // 使用最近一次广播的 isSeekable 与 durationUnset 判据
                        if (lastIsSeekable && !lastDurationUnset) {
                            playerService.seekTo(progress);
                        } else {
                            pendingSeekMs = progress;
                            pendingSeekSongId = (playerService != null && playerService.getCurrentSong() != null) ? playerService.getCurrentSong().getId() : null;
                            Toast.makeText(MainActivity.this, "当前曲目暂不支持拖动，已记录目标位置", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                // 抑制过期广播与刷新小进度条
                suppressProgressFromBroadcast = true;
                suppressUntilMs = System.currentTimeMillis() + 600L;
                                    progressBar.setProgress(seekBar.getProgress());
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.invalidate();
            }
        });
    }

    private void updatePlaybackModeButton() {
        if (bound && playerService != null) {
            int mode = playerService.getPlaybackMode();
            int iconRes;
            switch (mode) {
                case PlayerService.PLAYBACK_MODE_REPEAT_ALL:
                    iconRes = R.drawable.ic_repeat;
                    break;
                case PlayerService.PLAYBACK_MODE_REPEAT_ONE:
                    iconRes = R.drawable.ic_repeat_one;
                    break;
                case PlayerService.PLAYBACK_MODE_SHUFFLE:
                    iconRes = R.drawable.ic_shuffle;
                    break;
                default:
                    iconRes = R.drawable.ic_repeat_off;
                    break;
            }
            repeatModeButton.setImageResource(iconRes);
        }
    }

    private void bindService() {
        Intent intent = new Intent(this, PlayerService.class);
        bindService(intent, connection, BIND_AUTO_CREATE);
    }

    private boolean pendingStartPlayOnBind = false;
    private void togglePlayback() {
        if (!bound) {
            // 首次交互时绑定服务，连接后开始播放
            pendingStartPlayOnBind = true;
            bindService();
            return;
        }
            if (playerService.isPlaying()) {
                playerService.pause();
                playPauseButton.setImageResource(R.drawable.ic_play);
            } else {
                playerService.play();
                playPauseButton.setImageResource(R.drawable.ic_pause);
        }
    }

    private void updatePlaybackState() {
        if (bound) {
            boolean isPlaying = playerService.isPlaying();
            playPauseButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            
            songTitle.setText(playerService.getCurrentTitle());
            songArtist.setText(playerService.getCurrentArtist());
            
            // 更新专辑封面（优先本地，再回退网络，并为网络添加稳定签名）
            String albumId = playerService.getCurrentSong() != null ? playerService.getCurrentSong().getAlbumId() : null;
            String key = (albumId != null && !albumId.isEmpty()) ? albumId : playerService.getCurrentSong() != null ? playerService.getCurrentSong().getCoverArtUrl() : null;
            String localCover = (albumId != null && !albumId.isEmpty()) ? localFileDetector.getDownloadedAlbumCoverPath(albumId) : null;
            String coverArtUrl = (localCover != null) ? ("file://" + localCover) : (key != null ? NavidromeApi.getInstance(this).getCoverArtUrl(key) : null);
            
            if (coverArtUrl != null) {
                boolean isLocal = coverArtUrl.startsWith("file://");
                RequestOptions optsLarge = new RequestOptions()
                    .format(DecodeFormat.PREFER_RGB_565)
                    .disallowHardwareConfig()
                    .dontAnimate()
                    .override(150, 150)
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art);
                if (isLocal) {
                    Glide.with(this)
                        .load(coverArtUrl)
                        .apply(optsLarge.diskCacheStrategy(DiskCacheStrategy.NONE))
                        .into(albumArt);
                } else {
                    Glide.with(this)
                        .load(coverArtUrl)
                        .apply(optsLarge.diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .signature(new ObjectKey(key != null ? key : "")))
                        .into(albumArt);
                }
            } else {
                albumArt.setImageResource(R.drawable.default_album_art);
            }
            
            updatePlaybackModeButton();
        }
    }

    @Override
    public void onSongClick(Song song, int position) {
        if (bound && playerService != null) {
            try {
                // 获取当前显示的整个歌曲列表作为播放列表
                List<Song> currentList = null;
                RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                int playIndexOverride = -1;
                if ("downloads".equals(currentView) && downloadedSongAdapter != null) {
                    currentList = downloadedSongAdapter.getSongList();
                    try { playIndexOverride = downloadedSongAdapter.getPositionBySongId(song.getId()); } catch (Exception ignore) {}
                } else if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                    // 所有歌曲：不再在UI线程整表查询，直接请求服务端全局滑动窗口从当前位置播放
                    // 在线/离线下的处理差异：离线下为了保证可播放性，仍回退构建本地列表并过滤
                    if (!isNetworkAvailable) {
                        // 离线回退：仅当需要离线保证时再取本地列表
                    currentList = new java.util.ArrayList<>();
                    int total = musicRepository.getSongCount();
                    List<Song> range = musicRepository.getSongsRange(Math.max(0, total), 0);
                    currentList.addAll(range);
                    }
                } else if (songAdapter != null) {
                    currentList = songAdapter.getSongList();
                }
                if (!(adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter)) {
                if (currentList == null || currentList.isEmpty()) {
                    Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show();
                    return;
                    }
                }
                // 在离线模式下，检查歌曲是否已离线可用（已下载优先，其次缓存）
                if (!isNetworkAvailable) {
                    boolean isDownloaded = localFileDetector.isSongDownloaded(song);
boolean isCached = CacheManager.getInstance(this).isCachedByAnyKey(song.getId());
if (!(isDownloaded || isCached)) {
                        Toast.makeText(this, "离线模式下只能播放已缓存的歌曲", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "离线模式下尝试播放未缓存的歌曲: " + song.getTitle());
                        return;
                    }
                    Log.d(TAG, "离线模式下播放可用歌曲(下载/缓存): " + song.getTitle());
                }
                // 显示加载提示
                Toast.makeText(this, "正在加载: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                // 预先更新UI
                songTitle.setText(song.getTitle());
                songArtist.setText(song.getArtist());
                // 确保歌曲有专辑ID，用于显示封面
                if (song.getAlbumId() == null || song.getAlbumId().isEmpty()) {
                    if (song.getCoverArtUrl() != null && !song.getCoverArtUrl().isEmpty()) {
                        song.setAlbumId(song.getCoverArtUrl());
                    }
                }
                // 预先加载封面（省略代码保持不变）
                if (song.getAlbumId() != null && !song.getAlbumId().isEmpty()) {
                    String albumId = song.getAlbumId();
                    String key = albumId;
                    String localCover = localFileDetector.getDownloadedAlbumCoverPath(albumId);
                    String coverArtUrl = (localCover != null) ? ("file://" + localCover) : NavidromeApi.getInstance(this).getCoverArtUrl(key);
                    boolean isLocal = coverArtUrl.startsWith("file://");
                    if (isLocal) {
                        Glide.with(this)
                            .load(coverArtUrl)
                            .override(150, 150)
                            .placeholder(R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(albumArt);
                    } else {
                        Glide.with(this)
                            .load(coverArtUrl)
                            .override(150, 150)
                            .placeholder(R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .signature(new ObjectKey(key))
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .into(albumArt);
                    }
                }
                // 设置播放
                RecyclerView.Adapter<?> adp = recyclerView.getAdapter();
                if (adp instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                    try {
                        if (!isNetworkAvailable) {
                            if (currentList == null || currentList.isEmpty()) {
                                int total = musicRepository.getSongCount();
                                currentList = musicRepository.getSongsRange(Math.max(0, total), 0);
                            }
                            playerService.setPlaylist(currentList, position);
                        } else {
                            playerService.playAllSongsFromGlobal(position);
                        }
                    } catch (Exception ignore) {}
                    try {
                        android.content.SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                        boolean wantOpen = sp.getBoolean("auto_open_full_player", false);
                        if (wantOpen) {
                            if (!hasPerformedFirstUserPlay) {
                                hasPerformedFirstUserPlay = true; // 冷启动首次播放：跳过展开
                            } else {
                                openFullPlayer();
                            }
                        }
                    } catch (Exception ignore) {}
                } else {
                    int indexToPlay = playIndexOverride >= 0 ? playIndexOverride : position;
                    playerService.setPlaylist(currentList, indexToPlay);
                    try {
                        android.content.SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                        boolean wantOpen = sp.getBoolean("auto_open_full_player", false);
                        if (wantOpen) {
                            if (!hasPerformedFirstUserPlay) {
                                hasPerformedFirstUserPlay = true; // 冷启动首次播放：跳过展开
                            } else {
                                openFullPlayer();
                            }
                        }
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                Log.e("MainActivity", "准备播放时出错", e);
                Toast.makeText(this, "播放出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            // 未绑定时：保存待播放请求，绑定后自动执行
            RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
            if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                // 记录为全局索引请求，绑定后由 onServiceConnected 执行全局播放
                pendingPlaylist = null;
                pendingPlaylistRebasedIndex = position;
            } else {
                if ("downloads".equals(currentView) && downloadedSongAdapter != null) {
                    pendingPlaylist = new java.util.ArrayList<>(downloadedSongAdapter.getSongList());
                    pendingPlaylistRebasedIndex = downloadedSongAdapter.getPositionBySongId(song.getId());
            } else {
            pendingPlaylist = buildCurrentPlaylistFor(position);
                }
            }
            bindService();
            Toast.makeText(this, "正在连接播放服务…", Toast.LENGTH_SHORT).show();
        }
    }

    private List<Song> buildCurrentPlaylistFor(int clickPosition) {
        List<Song> list = null;
        int rebased = clickPosition;
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
            list = new java.util.ArrayList<>();
            int total = musicRepository.getSongCount();
            List<Song> range = musicRepository.getSongsRange(Math.max(0, total), 0);
            list.addAll(range);
            rebased = Math.min(Math.max(0, clickPosition), list.size() - 1);
        } else if (songAdapter != null) {
            list = songAdapter.getSongList();
        }
        pendingPlaylistRebasedIndex = rebased;
        return list != null ? list : java.util.Collections.emptyList();
    }

    private List<Song> pendingPlaylist = null;
    private int pendingPlaylistRebasedIndex = -1;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.clear();
        if ("songs".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) {
                menu.findItem(R.id.action_letter_index).setVisible(!selectionMode);
            }
            if (menu.findItem(R.id.action_enter_selection) != null) {
                menu.findItem(R.id.action_enter_selection).setVisible(!selectionMode);
            }
            if (menu.findItem(R.id.action_add_to_playlist) != null) {
                menu.findItem(R.id.action_add_to_playlist).setVisible(selectionMode);
            }
            if (menu.findItem(R.id.action_download_selected) != null) menu.findItem(R.id.action_download_selected).setVisible(selectionMode);
        } else if ("search".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) menu.findItem(R.id.action_letter_index).setVisible(false);
            if (menu.findItem(R.id.action_enter_selection) != null) menu.findItem(R.id.action_enter_selection).setVisible(false);
            if (menu.findItem(R.id.action_add_to_playlist) != null) menu.findItem(R.id.action_add_to_playlist).setVisible(selectionMode);
            if (menu.findItem(R.id.action_download_selected) != null) menu.findItem(R.id.action_download_selected).setVisible(selectionMode);
        } else if ("album_songs".equals(currentView) || "artist_songs".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) {
                menu.findItem(R.id.action_letter_index).setVisible(false);
            }
            if (menu.findItem(R.id.action_enter_selection) != null) menu.findItem(R.id.action_enter_selection).setVisible(!selectionMode);
            if (menu.findItem(R.id.action_add_to_playlist) != null) {
                menu.findItem(R.id.action_add_to_playlist).setVisible(selectionMode);
            }
            if (menu.findItem(R.id.action_download_selected) != null) menu.findItem(R.id.action_download_selected).setVisible(selectionMode);
        } else if ("artists".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) {
                // 当艺术家可索引字母为空时，隐藏按钮
                boolean hasLetters = false;
                try {
                    if (recyclerView != null && recyclerView.getAdapter() instanceof com.watch.limusic.adapter.ArtistAdapter) {
                        com.watch.limusic.adapter.ArtistAdapter aa = (com.watch.limusic.adapter.ArtistAdapter) recyclerView.getAdapter();
                        java.util.List<String> ls = aa.getAvailableIndexLetters();
                        hasLetters = ls != null && !ls.isEmpty();
                    }
                } catch (Exception ignore) {}
                menu.findItem(R.id.action_letter_index).setVisible(hasLetters);
            }
            if (menu.findItem(R.id.action_enter_selection) != null) menu.findItem(R.id.action_enter_selection).setVisible(false);
            if (menu.findItem(R.id.action_add_to_playlist) != null) {
                menu.findItem(R.id.action_add_to_playlist).setVisible(false);
            }
        } else if ("playlists".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_playlist_list, menu);
        } else if ("playlist_detail".equals(currentView)) {
            // 歌单详情：选择模式显示“添加到歌单”和“删除所选”与“下载”
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) menu.findItem(R.id.action_letter_index).setVisible(false);
            if (menu.findItem(R.id.action_enter_selection) != null) menu.findItem(R.id.action_enter_selection).setVisible(!selectionMode);
            if (menu.findItem(R.id.action_add_to_playlist) != null) menu.findItem(R.id.action_add_to_playlist).setVisible(selectionMode);
            if (menu.findItem(R.id.action_download_selected) != null) menu.findItem(R.id.action_download_selected).setVisible(selectionMode);
            // 追加“删除所选”
            if (selectionMode) getMenuInflater().inflate(R.menu.menu_playlist_detail_selection, menu);
        } else if ("downloads".equals(currentView)) {
            if (selectionMode) {
                getMenuInflater().inflate(R.menu.menu_downloads_selection, menu);
                // 下载页多选：显示“添加到歌单”和“删除所选”
                if (menu.findItem(R.id.action_add_to_playlist) != null) menu.findItem(R.id.action_add_to_playlist).setVisible(true);
                if (menu.findItem(R.id.action_delete_selected) != null) menu.findItem(R.id.action_delete_selected).setVisible(true);
            } else {
                getMenuInflater().inflate(R.menu.menu_downloads, menu);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_enter_selection) {
            if (selectionMode) return true;
            selectionMode = true;
            selectedSongIds.clear();
            if ("downloads".equals(currentView)) {
                if (downloadedSongAdapter != null) {
                    downloadedSongAdapter.setSelectionMode(true);
                    downloadedSongAdapter.setOnSelectionChangedListener(count -> {
                        selectedSongIds.clear();
                        selectedSongIds.addAll(downloadedSongAdapter.getSelectedIdsInOrder());
                        updateSelectionTitle();
                        invalidateOptionsMenu();
                    });
                }
            } else {
                RecyclerView.Adapter<?> ad = recyclerView.getAdapter();
                if (ad instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                    com.watch.limusic.adapter.AllSongsRangeAdapter ra = (com.watch.limusic.adapter.AllSongsRangeAdapter) ad;
                    ra.setSelectionMode(true);
                    ra.setOnSelectionChangedListener(count -> {
                        selectedSongIds.clear();
                        selectedSongIds.addAll(ra.getSelectedIdsInOrder());
                        updateSelectionTitle();
                        invalidateOptionsMenu();
                    });
                } else if (ad instanceof com.watch.limusic.adapter.SongAdapter) {
                    com.watch.limusic.adapter.SongAdapter sa = (com.watch.limusic.adapter.SongAdapter) ad;
                    sa.setSelectionMode(true);
                    sa.setOnSelectionChangedListener(count -> {
                        selectedSongIds.clear();
                        selectedSongIds.addAll(sa.getSelectedIdsInOrder());
                        updateSelectionTitle();
                        invalidateOptionsMenu();
                    });
                }
            }
            updateSelectionTitle();
            invalidateOptionsMenu();
            updateNavigationForSelectionMode();
            return true;
        }
        // 歌单详情的“删除所选”改由 action_delete_selected 处理；此分支不再复用 action_add_to_playlist
        if (id == R.id.action_letter_index) {
            showLetterIndexDialog();
            return true;
        } else if (id == R.id.action_add_to_playlist) {
            if (!selectionMode) return true;
            if (selectedSongIds == null || selectedSongIds.isEmpty()) {
                Toast.makeText(this, "未选择歌曲", Toast.LENGTH_SHORT).show();
                return true;
            }
            // 选择歌单/新建
            java.util.List<com.watch.limusic.database.PlaylistEntity> lists = com.watch.limusic.database.MusicDatabase.getInstance(this).playlistDao().getAll();
            if (lists == null || lists.isEmpty()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("无歌单")
                    .setMessage("无歌单，请先创建歌单")
                    .setPositiveButton("新建", (d,w) -> {
                        final android.widget.EditText input = new android.widget.EditText(this);
                        input.setHint("输入歌单名称");
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("新建歌单")
                            .setView(input)
                            .setPositiveButton("创建", (d2,w2) -> {
                                String name = input.getText() != null ? input.getText().toString() : "";
                                try {
                                    java.util.List<String> ordered = new java.util.ArrayList<>(selectedSongIds);
                                    playlistRepository.createPlaylistAndAddSongs(name, false, ordered, (skipped, serverOk) -> runOnUiThread(() -> {
                                        StringBuilder tip = new StringBuilder();
                                        if (skipped != null && !skipped.isEmpty()) {
                                            String joined = android.text.TextUtils.join("、", skipped);
                                            tip.append("跳过已存在：").append(joined);
                                        }
                                        tip.append(tip.length()>0?"\n":"").append(serverOk?"歌单创建并保存成功":"本地已保存，服务器创建/同步未完成，可稍后重试");
                                        showQueuedTip(tip.toString());
                                        exitSelectionMode();
                                    }));
                                } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return true;
            }
            // 去重：同名保留已绑定serverId的项；若均未绑定则保留最新changedAt
            java.util.LinkedHashMap<String, com.watch.limusic.database.PlaylistEntity> dedup = new java.util.LinkedHashMap<>();
            if (lists != null) {
                java.util.Collections.sort(lists, (a,b) -> Long.compare(b.getChangedAt(), a.getChangedAt()));
                for (com.watch.limusic.database.PlaylistEntity pe : lists) {
                    String key = pe.getName();
                    com.watch.limusic.database.PlaylistEntity existing = dedup.get(key);
                    if (existing == null) { dedup.put(key, pe); }
                    else {
                        boolean existingBound = existing.getServerId() != null && !existing.getServerId().isEmpty();
                        boolean currentBound = pe.getServerId() != null && !pe.getServerId().isEmpty();
                        if (!existingBound && currentBound) { dedup.put(key, pe); }
                    }
                }
            }
            java.util.ArrayList<com.watch.limusic.database.PlaylistEntity> viewList = new java.util.ArrayList<>(dedup.values());
            CharSequence[] names = new CharSequence[viewList.size()+1];
            for (int i=0;i<viewList.size();i++) names[i] = viewList.get(i).getName() + " ("+viewList.get(i).getSongCount()+")";
            names[viewList.size()] = "新建歌单";
            // 使用自定义视图的简洁弹窗：搜索 + 列表 + 单选
            android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_to_playlist, null);
            androidx.recyclerview.widget.RecyclerView rv = dialogView.findViewById(R.id.rv_playlists);
            com.watch.limusic.adapter.PlaylistPickerAdapter picker = new com.watch.limusic.adapter.PlaylistPickerAdapter(viewList);
            picker.setOnItemSelectedListener(entity -> {});
            rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
            rv.setAdapter(picker);
            try {
                rv.setHasFixedSize(true);
                rv.setItemAnimator(null);
                rv.getRecycledViewPool().setMaxRecycledViews(0, 16);
                // 适配手表屏：最大高度为屏幕高度的60%，超出则可滚动
                int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
                rv.getLayoutParams().height = maxHeight;
            } catch (Exception ignore) {}
            androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择歌单")
                .setView(dialogView)
                .create();
            dlg.setOnShowListener(dd -> {
                android.widget.Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
                android.widget.Button btnNew = dialogView.findViewById(R.id.btn_new);
                android.widget.Button btnAdd = dialogView.findViewById(R.id.btn_add);
                btnAdd.setText("添加(" + selectedSongIds.size() + ")");
                btnAdd.setEnabled(false);
                picker.setOnItemSelectedListener(entity -> btnAdd.setEnabled(true));
                btnCancel.setOnClickListener(v -> dlg.dismiss());
                btnAdd.setOnClickListener(v -> {
                    com.watch.limusic.database.PlaylistEntity sel = picker.getSelected();
                    if (sel == null) return;
                    performAddToPlaylist(sel.getLocalId());
                    dlg.dismiss();
                });
                btnNew.setOnClickListener(v -> {
                    final android.widget.EditText input = new android.widget.EditText(this);
                    input.setHint("输入歌单名称");
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("新建歌单")
                        .setView(input)
                        .setPositiveButton("创建", (d2,w2) -> {
                            String name = input.getText() != null ? input.getText().toString() : "";
                            try {
                                java.util.List<String> ordered = new java.util.ArrayList<>(selectedSongIds);
                                playlistRepository.createPlaylistAndAddSongs(name, false, ordered, (skipped, serverOk) -> runOnUiThread(() -> {
                                    StringBuilder tip = new StringBuilder();
                                    if (skipped != null && !skipped.isEmpty()) {
                                        String joined = android.text.TextUtils.join("、", skipped);
                                        tip.append("跳过已存在：").append(joined);
                                    }
                                    tip.append(tip.length()>0?"\n":"").append(serverOk?"歌单创建并保存成功":"本地已保存，服务器创建/同步未完成，可稍后重试");
                                    showQueuedTip(tip.toString());
                                    exitSelectionMode();
                                }));
                                dlg.dismiss();
                            } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
            });
            dlg.show();
            return true;
        } else if (id == R.id.action_new_playlist) {
            // 歌单视图下的新建
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setHint("输入歌单名称");
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("新建歌单")
                    .setView(input)
                    .setPositiveButton("创建", (d, w) -> {
                        String name = input.getText() != null ? input.getText().toString() : "";
                        try {
                            playlistRepository.createPlaylist(name, false);
                            loadPlaylists();
                        } catch (Exception e) {
                            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        } else if (id == android.R.id.home) {
            if ("album_songs".equals(currentView)) {
                navigateBackToAlbums();
            } else if ("artist_songs".equals(currentView)) {
                navigateBackToArtists();
            } else if ("playlist_detail".equals(currentView)) {
                navigateBackToPlaylists();
            } else if ("search".equals(currentView)) {
                            // 搜索页：Home键作为返回上一层，恢复搜索Toolbar并保持搜索导航模式
            applySearchToolbar();
            enterSearchNavigationMode();
            } else {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            }
            return true;
        }
        if ("downloads".equals(currentView)) {
            if (selectionMode && id == R.id.action_delete_selected) {
                if (!selectionMode || selectedSongIds == null || selectedSongIds.isEmpty()) return true;
                final java.util.List<String> ids = new java.util.ArrayList<>(selectedSongIds);
                final java.util.List<String> titles = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getTitlesByIds(ids);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < titles.size(); i++) {
                    sb.append(i + 1).append(". ").append(titles.get(i)).append("\n");
                }
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText(sb.toString());
                tv.setTextColor(getResources().getColor(R.color.text_primary));
                tv.setTextSize(14);
                int pad = (int) (getResources().getDisplayMetrics().density * 12);
                tv.setPadding(pad, pad, pad, pad);
                sv.addView(tv);
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("确认删除以下歌曲？")
                    .setView(sv)
                    .setPositiveButton("删除", (d,w) -> {
                        for (String sid : ids) {
                            try {
                                boolean deleted = com.watch.limusic.download.DownloadManager.getInstance(this).deleteDownload(sid);
                                try { com.watch.limusic.database.DownloadRepository.getInstance(this).deleteDownload(sid); } catch (Exception ignore) {}
                                Intent cacheIntent = new Intent("com.watch.limusic.CACHE_STATUS_CHANGED");
                                cacheIntent.putExtra("songId", sid);
                                cacheIntent.putExtra("isCached", false);
                                sendBroadcast(cacheIntent);
                            } catch (Exception ignore) {}
                        }
                        // 立即清空“已下载”适配器中的歌曲，避免UI残留
                        try { if (downloadedSongAdapter != null) downloadedSongAdapter.processAndSubmitListKeepOrder(new java.util.ArrayList<>()); } catch (Exception ignore) {}
                        refreshDownloadsData();
                        // 为防止异步DB写入延迟，增加一次/二次延迟刷新
                        try { recyclerView.postDelayed(this::refreshDownloadsData, 250); } catch (Exception ignore) {}
                        try { recyclerView.postDelayed(this::refreshDownloadsData, 600); } catch (Exception ignore) {}
                        exitSelectionMode();
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return true;
            }
            if (id == R.id.action_pause_all) { pauseAllDownloads(); return true; }
            if (id == R.id.action_resume_all) { resumeAllDownloads(); return true; }
            if (id == R.id.action_cancel_all) { confirmCancelAllDownloads(); return true; }
        }
        if (selectionMode && id == R.id.action_download_selected) {
            if (selectedSongIds == null || selectedSongIds.isEmpty()) { Toast.makeText(this, "未选择歌曲", Toast.LENGTH_SHORT).show(); return true; }
            new Thread(() -> {
                for (String sid : new java.util.ArrayList<>(selectedSongIds)) {
                    try {
                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getSongById(sid);
                        if (se != null) {
                            com.watch.limusic.model.Song s = new com.watch.limusic.model.Song(se.getId(), se.getTitle(), se.getArtist(), se.getAlbum(), se.getCoverArt(), se.getStreamUrl(), se.getDuration());
                            s.setAlbumId(se.getAlbumId());
                            com.watch.limusic.download.DownloadManager.getInstance(this).downloadSong(s);
                        }
                    } catch (Exception ignore) {}
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, "已开始下载所选歌曲", Toast.LENGTH_SHORT).show();
                    exitSelectionMode();
                });
            }).start();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.nav_all_songs) {
            hideBottomHint();
            try { if (emptyContainer != null) emptyContainer.setVisibility(View.GONE); if (errorContainer != null) errorContainer.setVisibility(View.GONE); } catch (Exception ignore) {}
            currentView = "songs";
            loadAllSongs();
            invalidateOptionsMenu();
            try { updateLocateButtonVisibility(null); } catch (Exception ignore) {}
        } else if (id == R.id.nav_playlists) {
            hideBottomHint();
            try { if (emptyContainer != null) emptyContainer.setVisibility(View.GONE); if (errorContainer != null) errorContainer.setVisibility(View.GONE); } catch (Exception ignore) {}
            Log.d(TAG, "Navigation: playlists clicked");
            currentView = "playlists";
            loadPlaylists();
            invalidateOptionsMenu();
        } else if (id == R.id.nav_artists) {
            hideBottomHint();
            try { if (emptyContainer != null) emptyContainer.setVisibility(View.GONE); if (errorContainer != null) errorContainer.setVisibility(View.GONE); } catch (Exception ignore) {}
            currentView = "artists";
            loadArtists();
            invalidateOptionsMenu();
        } else if (id == R.id.nav_albums) {
            hideBottomHint();
            try { if (emptyContainer != null) emptyContainer.setVisibility(View.GONE); if (errorContainer != null) errorContainer.setVisibility(View.GONE); } catch (Exception ignore) {}
            currentView = "albums";
            recyclerView.setAdapter(albumAdapter);
            loadAlbums();
            invalidateOptionsMenu();
            ensureProgressBarVisible();
            try { updateLocateButtonVisibility(null); } catch (Exception ignore) {}
        } else if (id == R.id.nav_search) {
            hideBottomHint();
            loadSearchEmbedded();
            invalidateOptionsMenu();
        } else if (id == R.id.nav_downloads) {
            openDownloadsEmbedded();
            invalidateOptionsMenu();
        } else if (id == R.id.nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.nav_about) {
            showAboutDialog();
        }

        drawerLayout.closeDrawers();
        return true;
    }

    private void loadAlbums() {
        if (isLoading) return;
        
        // 进入根视图的导航模式（汉堡菜单）
        enterRootNavigationMode();
        
        // 重置状态和列表
        hasMoreData = true;
        currentOffset = 0;
        
        // 强制重置状态避免早退
        isLoading = false;
        // 显示加载中
        progressBar.setVisibility(View.VISIBLE);
        isLoading = true;

        // 保证骨架屏已显示
        showSkeleton();

        // 确保专辑适配器附着
        if (albumAdapter == null) {
            albumAdapter = new AlbumAdapter(MainActivity.this);
            albumAdapter.setOnAlbumClickListener(album -> {
                Log.d(TAG, "专辑点击监听器触发: " + album.getName() + ", ID: " + album.getId());
                if (album.getId() == null || album.getId().isEmpty()) {
                    Toast.makeText(this, "专辑ID无效，无法加载歌曲", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 进入专辑详情前，记录当前专辑列表滚动位置
                recordAlbumsScrollPosition();
                
                loadAlbumSongs(album.getId());
            });
        }
        if (recyclerView.getAdapter() != albumAdapter) {
            recyclerView.setAdapter(albumAdapter);
            searchFooterAdapter = null;
        }

        // 1) 先快速渲染本地缓存，提升首屏速度
        new Thread(() -> {
            try {
                final List<Album> cached = musicRepository.getAlbumsFromDatabase(0, PAGE_SIZE);
                runOnUiThread(() -> {
                    if (cached != null && !cached.isEmpty()) {
                        currentView = "albums";
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setTitle("专辑");
                        }
                        // 切换为根视图导航（汉堡）
                        enterRootNavigationMode();
                        if (albumAdapter == null) {
                            albumAdapter = new AlbumAdapter(MainActivity.this);
                            albumAdapter.setOnAlbumClickListener(album -> {
                                Log.d(TAG, "专辑点击监听器触发: " + album.getName() + ", ID: " + album.getId());
                                if (album.getId() == null || album.getId().isEmpty()) {
                                    Toast.makeText(this, "专辑ID无效，无法加载歌曲", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                // 进入专辑详情前，记录当前专辑列表滚动位置
                                recordAlbumsScrollPosition();
                                
                                loadAlbumSongs(album.getId());
                            });
                            recyclerView.setAdapter(albumAdapter);
                            searchFooterAdapter = null;
                        } else {
                            if (recyclerView.getAdapter() != albumAdapter) {
                                recyclerView.setAdapter(albumAdapter);
                                searchFooterAdapter = null;
                            }
                        }
                        albumAdapter.setAlbums(cached);
                        currentOffset = cached.size();
                        // 首屏已有数据，隐藏骨架屏
                        if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                    }
                });
            } catch (Exception ignore) {}
        }).start();
        
        // 2) 并行发起网络请求，返回后覆盖列表
        new Thread(() -> {
            try {
                final List<Album> albums = musicRepository.getAlbums("newest", PAGE_SIZE, 0);
                
                runOnUiThread(() -> {
                    if (albums != null && !albums.isEmpty()) {
                        // 更新UI
                        currentView = "albums";
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setTitle("专辑");
                        }
                        
                        // 切换为根视图导航（汉堡）
                        enterRootNavigationMode();
                        
                        // 设置适配器
                        if (albumAdapter == null) {
                            albumAdapter = new AlbumAdapter(MainActivity.this);
                            albumAdapter.setOnAlbumClickListener(album -> {
                                Log.d(TAG, "专辑点击监听器触发: " + album.getName() + ", ID: " + album.getId());
                                if (album.getId() == null || album.getId().isEmpty()) {
                                    Toast.makeText(this, "专辑ID无效，无法加载歌曲", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                // 进入专辑详情前，记录当前专辑列表滚动位置
                                recordAlbumsScrollPosition();
                                
                                loadAlbumSongs(album.getId());
                            });
                            recyclerView.setAdapter(albumAdapter);
                            searchFooterAdapter = null;
                        } else {
                            if (recyclerView.getAdapter() != albumAdapter) {
                                recyclerView.setAdapter(albumAdapter);
                                searchFooterAdapter = null;
                            }
                        }
                        
                        // 覆盖数据
                        albumAdapter.setAlbums(albums);
                        
                        // 在线时预取专辑封面，供离线显示（异步，不阻塞UI）
                        if (isNetworkAvailable) {
                            new Thread(() -> prefetchAlbumCovers(albums)).start();
                        }
                        
                        // 更新加载状态
                        currentOffset = albums.size();
                        isLoading = false;
                        progressBar.setVisibility(View.GONE);
                        
                        // 检查是否还有更多数据
                        hasMoreData = albums.size() >= PAGE_SIZE;
                        
                        // 隐藏骨架/空/错
                        if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                        if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
                        if (errorContainer != null) errorContainer.setVisibility(View.GONE);
                    } else {
                        Toast.makeText(MainActivity.this, "没有专辑或加载失败", Toast.LENGTH_SHORT).show();
                        showEmpty("暂无专辑");
                        isLoading = false;
                        progressBar.setVisibility(View.GONE);
                    }
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            } catch (Exception e) {
                Log.e(TAG, "加载专辑失败", e);
                
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "加载专辑失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    showError("加载失败，请检查网络后重试");
                    isLoading = false;
                    progressBar.setVisibility(View.GONE);
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            }
        }).start();
    }
    
    // 在后台预加载更多数据，不阻塞UI
    private void loadMoreAlbumsInBackground() {
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 稍微延迟，让UI先响应
                SubsonicResponse<List<Album>> response = NavidromeApi.getInstance(MainActivity.this)
                        .getAlbumList("newest", PAGE_SIZE, currentOffset);
                runOnUiThread(() -> {
                    if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                        albumAdapter.addAlbums(response.getData());
                        currentOffset += response.getData().size();
                        hasMoreData = response.getData().size() >= PAGE_SIZE;
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    // 滚动时加载更多专辑
    private void loadMoreAlbums() {
        if (isLoading || !hasMoreData) return;
        
        // 显示加载中
        progressBar.setVisibility(View.VISIBLE);
        isLoading = true;
        
        // 使用Repository加载更多专辑
        new Thread(() -> {
            try {
                final List<Album> moreAlbums = musicRepository.getAlbums("newest", PAGE_SIZE, currentOffset);
                
                runOnUiThread(() -> {
                    if (moreAlbums != null && !moreAlbums.isEmpty()) {
                        // 添加到现有专辑列表
                        albumAdapter.addAlbums(moreAlbums);
                        if (isNetworkAvailable) {
                            new Thread(() -> prefetchAlbumCovers(moreAlbums)).start();
                        }
                        
                        // 更新加载状态
                        currentOffset += moreAlbums.size();
                        hasMoreData = moreAlbums.size() >= PAGE_SIZE;
                    } else {
                        // 没有更多数据
                        hasMoreData = false;
                    }
                    
                    // 重置加载状态
                    isLoading = false;
                    progressBar.setVisibility(View.GONE);
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            } catch (Exception e) {
                Log.e(TAG, "加载更多专辑失败", e);
                
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "加载更多专辑失败", Toast.LENGTH_SHORT).show();
                    isLoading = false;
                    progressBar.setVisibility(View.GONE);
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            }
        }).start();
    }

    private void loadAlbumSongs(String albumId) {
        try { updateLocateButtonVisibility(null); } catch (Exception ignore) {}

        // 显示加载中
        progressBar.setVisibility(View.VISIBLE);
        
        // 使用Repository加载专辑歌曲
        new Thread(() -> {
            try {
                final List<Song> songs = musicRepository.getAlbumSongs(albumId);
                
                runOnUiThread(() -> {
                    if (songs != null && !songs.isEmpty()) {
                        // 记录当前专辑信息
                        currentAlbumId = albumId;
                        currentAlbumTitle = songs.get(0).getAlbum();
                        
                        // 更新UI
                        currentView = "album_songs";  // 使用特定标识，区分普通歌曲列表
                        String albumTitle = currentAlbumTitle;
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setTitle(albumTitle);
                        }
                        
                        // 进入返回箭头模式并禁用抽屉（并设置滚动标题）
                        enterAlbumDetailNavigationMode(albumTitle);
                        
                        // 设置适配器
                        if (songAdapter == null) {
                            songAdapter = new SongAdapter(MainActivity.this, this);
                        }
                        
                        // 显式设置RecyclerView的适配器为歌曲适配器
                        recyclerView.setAdapter(songAdapter);
                        try { songAdapter.setHighlightKeyword(null); } catch (Exception ignore) {}
                        
                        // 专辑内歌曲列表不显示封面
                        songAdapter.setShowCoverArt(false);
                        try {
                            String sid = null;
                            if (bound && playerService != null && playerService.getCurrentSong() != null) sid = playerService.getCurrentSong().getId();
                            if (sid == null) sid = getSharedPreferences("player_prefs", MODE_PRIVATE).getString("last_song_id", null);
                            songAdapter.setCurrentPlaying(sid, bound && playerService != null && playerService.isPlaying());
                            updateLocateButtonVisibility(sid);
                        } catch (Exception ignore) {}
                        
                        // 选择模式长按入口
                        songAdapter.setOnItemLongClickListener(pos -> {
                            Song s = songAdapter.getSongItemAt(pos).getSong();
                            selectionMode = true;
                            selectedSongIds.clear();
                            selectedSongIds.add(s.getId());
                            songAdapter.setSelectionMode(true);
                            // 立即选中长按的首项
                            songAdapter.toggleSelect(s.getId());
                            songAdapter.setOnSelectionChangedListener(count -> {
                                selectedSongIds.clear();
                                selectedSongIds.addAll(songAdapter.getSelectedIdsInOrder());
                                updateSelectionTitle();
                                invalidateOptionsMenu();
                            });
                            updateSelectionTitle();
                            invalidateOptionsMenu();
                            updateNavigationForSelectionMode();
                        });
                        
                        // 更新数据
                        songAdapter.processAndSubmitList(songs);
                        
                        // 通知系统更新菜单
                        invalidateOptionsMenu();
                        
                        // 记录日志
                        Log.d(TAG, "专辑歌曲加载成功: " + songs.size() + " 首歌曲, 专辑ID: " + albumId);
                    } else {
                        Toast.makeText(MainActivity.this, "没有找到歌曲或加载失败", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "专辑歌曲加载失败: 没有找到歌曲, 专辑ID: " + albumId);
                        // 尝试API加载...
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "加载专辑失败", e);
                
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "加载专辑失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    showError("加载失败，请检查网络后重试");
                    isLoading = false;
                    progressBar.setVisibility(View.GONE);
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            }
        }).start();
    }

    private void showAboutDialog() {
        String versionName = "unknown";
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            if (pi != null && pi.versionName != null) versionName = pi.versionName;
        } catch (Exception ignore) {}
        new AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage("版本：v" + versionName + "\n软件作者 九秒冬眠 b站uid：515083950 ")
            .setPositiveButton("确定", null)
            .show();
    }

    private void checkConfiguration() {
        SharedPreferences prefs = getSharedPreferences("navidrome_settings", MODE_PRIVATE);
        String serverUrl = prefs.getString("server_url", "");
        if (serverUrl.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            // 恢复上次视图或默认专辑
            restoreLastViewOrDefault();
        }
    }

    private void saveLastView(String view) {
        saveLastView(view, null, null, -1L);
    }
    private void saveLastView(String view, String albumId, String albumTitle) {
        saveLastView(view, albumId, albumTitle, -1L);
    }
    private void saveLastView(String view, long playlistLocalId) {
        saveLastView(view, null, null, playlistLocalId);
    }
    private void saveLastView(String view, String albumId, String albumTitle, long playlistLocalId) {
        try {
            SharedPreferences sp = getSharedPreferences("ui_prefs", MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            e.putString("last_view", view);
            if (albumId != null) e.putString("last_album_id", albumId); else e.remove("last_album_id");
            if (albumTitle != null) e.putString("last_album_title", albumTitle); else e.remove("last_album_title");
            if (playlistLocalId > 0) e.putLong("last_playlist_local_id", playlistLocalId); else e.remove("last_playlist_local_id");
            // 保存当前滚动位置
            try {
                if (recyclerView != null && recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                    androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                    int pos = lm.findFirstVisibleItemPosition();
                    View v = lm.findViewByPosition(pos);
                    int offset = v != null ? v.getTop() : 0;
                    e.putInt("last_scroll_pos", Math.max(0, pos));
                    e.putInt("last_scroll_offset", offset);
                }
            } catch (Exception ignore) {}
            e.apply();
        } catch (Exception ignore) {}
    }

    private void resetUiForNewView() {
        try {
            if (recyclerView != null) {
                recyclerView.setAdapter(null);
            }
            // 清空搜索高亮，避免从搜索页遗留至其他页面
            try { if (songAdapter != null) songAdapter.setHighlightKeyword(null); } catch (Exception ignore) {}
            if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
            if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
            if (errorContainer != null) errorContainer.setVisibility(View.GONE);
            if (progressBar != null) progressBar.setVisibility(View.GONE);
        } catch (Exception ignore) {}
    }

    private void restoreLastViewOrDefault() {
        try {
            SharedPreferences sp = getSharedPreferences("ui_prefs", MODE_PRIVATE);
            String v = sp.getString("last_view", null);
            int pos = sp.getInt("last_scroll_pos", 0);
            int off = sp.getInt("last_scroll_offset", 0);
            if (v == null) { resetUiForNewView(); loadAlbums(); return; }
            switch (v) {
                case "songs":
                    resetUiForNewView();
                    loadAllSongs();
                    postRestoreScroll(pos, off);
                    break;
                case "playlists":
                    resetUiForNewView();
                    loadPlaylists();
                    postRestoreScroll(pos, off);
                    break;
                case "album_songs":
                    String aid = sp.getString("last_album_id", null);
                    if (aid != null && !aid.isEmpty()) {
                        resetUiForNewView();
                        loadAlbumSongs(aid);
                        postRestoreScroll(pos, off);
                    } else { resetUiForNewView(); loadAlbums(); }
                    break;
                case "playlist_detail":
                    long pid = sp.getLong("last_playlist_local_id", -1L);
                    if (pid > 0) {
                        resetUiForNewView();
                        com.watch.limusic.database.PlaylistEntity pe = com.watch.limusic.database.MusicDatabase.getInstance(this).playlistDao().getByLocalId(pid);
                        String name = pe != null ? pe.getName() : "";
                        openPlaylistDetail(pid, name);
                        postRestoreScroll(pos, off);
                    } else { resetUiForNewView(); loadPlaylists(); }
                    break;
                case "artists":
                    resetUiForNewView();
                    loadArtists();
                    postRestoreScroll(pos, off);
                    break;
                case "search":
                    resetUiForNewView();
                    loadSearchEmbedded();
                    postRestoreScroll(pos, off);
                    break;
                case "albums":
                default:
                    resetUiForNewView();
                    loadAlbums();
                    postRestoreScroll(pos, off);
            }
        } catch (Exception e) {
            resetUiForNewView();
            loadAlbums();
        }
    }

    private void postRestoreScroll(int position, int offset) {
        try {
            if (recyclerView != null) recyclerView.post(() -> {
                try {
                    if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                        androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                        lm.scrollToPositionWithOffset(Math.max(0, position), offset);
                    } else {
                        recyclerView.scrollToPosition(Math.max(0, position));
                    }
                } catch (Exception ignore) {}
            });
        } catch (Exception ignore) {}
    }

    private void loadAllSongs() {
        try { if (emptyContainer != null) emptyContainer.setVisibility(View.GONE); if (errorContainer != null) errorContainer.setVisibility(View.GONE); } catch (Exception ignore) {}
        // 优先本地：立即读取总数与字母偏移并挂载范围适配器，秒出列表
        new Thread(() -> {
            try {
                int total = musicRepository.getSongCount();
                java.util.Map<String, Integer> letterOffsets = musicRepository.getLetterOffsetMap();
                runOnUiThread(() -> {
                    // 初始化/复用按需加载适配器
                    rangeAdapter = new com.watch.limusic.adapter.AllSongsRangeAdapter(this, musicRepository, this);
                    rangeAdapter.setOnDownloadClickListener(this);
                    rangeAdapter.setShowCoverArt(false);
                    rangeAdapter.setShowDownloadStatus(true);
                    rangeAdapter.clearCache();
                    rangeAdapter.setTotalCount(total);
                    rangeAdapter.setLetterOffsetMap(letterOffsets);
                    
                    // RecyclerView 性能优化（搜索页同款配置）
                    try {
                        if (recyclerView != null) {
                            recyclerView.setHasFixedSize(true);
                            recyclerView.setItemAnimator(null);
                            RecyclerView.RecycledViewPool pool = recyclerView.getRecycledViewPool();
                            if (pool != null) pool.setMaxRecycledViews(0, 24);
                        }
                    } catch (Exception ignore) {}
                    
                    recyclerView.setAdapter(rangeAdapter);
                    try {
                        String sid = null;
                        if (bound && playerService != null && playerService.getCurrentSong() != null) sid = playerService.getCurrentSong().getId();
                        if (sid == null) sid = getSharedPreferences("player_prefs", MODE_PRIVATE).getString("last_song_id", null);
                        rangeAdapter.setCurrentPlaying(sid, bound && playerService != null && playerService.isPlaying());
                        updateLocateButtonVisibility(sid);
                    } catch (Exception ignore) {}

                    // 选择模式长按入口
                    rangeAdapter.setOnItemLongClickListener(pos -> {
                        String id = rangeAdapter.getSongIdAt(pos);
                        if (id == null) return;
                        selectionMode = true;
                        selectedSongIds.clear();
                        selectedSongIds.add(id);
                        rangeAdapter.setSelectionMode(true);
                        rangeAdapter.toggleSelect(id);
                        rangeAdapter.setOnSelectionChangedListener(count -> {
                            selectedSongIds.clear();
                            selectedSongIds.addAll(rangeAdapter.getSelectedIdsInOrder());
                            updateSelectionTitle();
                            invalidateOptionsMenu();
                        });
                        updateSelectionTitle();
                        invalidateOptionsMenu();
                        updateNavigationForSelectionMode();
                    });

                    if (getSupportActionBar() != null) { getSupportActionBar().setTitle(R.string.all_songs); }
                    currentView = "songs";
                    enterRootNavigationMode();
                    invalidateOptionsMenu();
                    saveLastView("songs");

                    // 首屏预取当前页
                    rangeAdapter.prefetch(0);
                });
            } catch (Exception e) {
                Log.e(TAG, "初始化所有歌曲适配器失败", e);
                runOnUiThread(() -> Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show());
            } finally {
                runOnUiThread(this::ensureProgressBarVisible);
            }
        }).start();

        // 后台快速刷新：在线且满足条件时，使用短超时拉取并入库，由 DB_SONGS_UPDATED 驱动 UI 差量刷新
        new Thread(() -> {
            try {
                if (!isNetworkAvailable) return;
                try { com.watch.limusic.api.NavidromeApi.getInstance(this).reloadCredentials(); } catch (Exception ignore) {}
                // 空库场景：强制刷新一次（不受 TTL 限制）
                int totalNow = 0;
                try { totalNow = musicRepository.getSongCount(); } catch (Exception ignore) {}
                if (totalNow == 0) {
                    if (!isApiConfiguredSafe()) return;
                    java.util.List<com.watch.limusic.model.Song> online;
                    try {
                        online = com.watch.limusic.api.NavidromeApi.getInstance(this).getAllSongsQuick();
                    } catch (Exception quickEx) {
                        try { online = com.watch.limusic.api.NavidromeApi.getInstance(this).getAllSongs(); }
                        catch (Exception e) { online = null; }
                    }
                    if (online != null && !online.isEmpty()) {
                        musicRepository.saveSongsToDatabase(online);
                    }
                    return;
                }

                // 简单TTL：15分钟内只刷新一次
                SharedPreferences sp = getSharedPreferences("ui_prefs", MODE_PRIVATE);
                long last = sp.getLong("last_songs_refresh_ts", 0L);
                long now = System.currentTimeMillis();
                if (now - last < 15 * 60 * 1000L) return;

                if (!isApiConfiguredSafe()) return;
                java.util.List<com.watch.limusic.model.Song> online;
                try {
                    online = com.watch.limusic.api.NavidromeApi.getInstance(this).getAllSongsQuick();
                } catch (Exception quickEx) {
                    // 快速通道失败，降级一次常规通道（不抛出以免打断UI）
                    try { online = com.watch.limusic.api.NavidromeApi.getInstance(this).getAllSongs(); }
                    catch (Exception e) { online = null; }
                }
                if (online != null && !online.isEmpty()) {
                    musicRepository.saveSongsToDatabase(online);
                    sp.edit().putLong("last_songs_refresh_ts", now).apply();
                }
            } catch (Exception ignore) {}
        }).start();
    }

    private void loadArtists() {
        enterRootNavigationMode();
        try { if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.artists); } catch (Exception ignore) {}
        if (skeletonContainer != null) skeletonContainer.setVisibility(View.VISIBLE);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                java.util.List<com.watch.limusic.model.ArtistItem> list = musicRepository.getArtists();
                runOnUiThread(() -> {
                    if (artistAdapter == null) {
                        artistAdapter = new com.watch.limusic.adapter.ArtistAdapter(this);
                        artistAdapter.setOnArtistClickListener((artist, position) -> {
                            // 进入详情前记录当前位置与偏移，返回时精确恢复
                            recordArtistsScrollPosition();
                            openArtistDetail(artist.getName());
                        });
                    }
                    recyclerView.setAdapter(artistAdapter);
                    artistAdapter.setData(list);
                    currentView = "artists";
                    invalidateOptionsMenu();
                    saveLastView("artists");
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show());
            } finally {
                runOnUiThread(() -> {
                    if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                    ensureProgressBarVisible();
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (isFullPlayerVisible) {
            closeFullPlayer();
            return;
        }
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        // 选择模式优先：系统返回退出选择模式
        if (selectionMode) {
            exitSelectionMode();
            return;
        }
        
        if ("album_songs".equals(currentView)) {
            // 二级界面：返回到专辑选择界面
            navigateBackToAlbums();
            return;
        }
        if ("artist_songs".equals(currentView)) {
            // 二级界面：返回到艺术家列表
            navigateBackToArtists();
            return;
        }
        if ("playlist_detail".equals(currentView)) {
            // 二级界面：返回到歌单列表
            navigateBackToPlaylists();
            return;
        }
        if ("search".equals(currentView)) {
            // 搜索页：系统返回时确保恢复Toolbar并保持搜索导航模式
            applySearchToolbar();
            enterSearchNavigationMode();
            return;
        }
        
        if (isFirstLevelView(currentView)) {
            long now = System.currentTimeMillis();
            if (now - lastBackPressedTime < BACK_EXIT_INTERVAL) {
                // 二次返回：退出应用
                super.onBackPressed();
            } else {
                lastBackPressedTime = now;
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 取消绑定服务
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        
        // 取消注册缓存状态接收器
        try {
            unregisterReceiver(cacheStatusReceiver);
        } catch (IllegalArgumentException e) {
            // 接收器可能未注册，忽略
        }
        
        // 取消注册网络状态接收器
        try {
            unregisterReceiver(networkStatusReceiver);
        } catch (IllegalArgumentException e) {
            // 接收器可能未注册，忽略
        }
        // 取消注册下载相关的本地广播
        try {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            lbm.unregisterReceiver(downloadReceiver);
        } catch (Exception ignore) {}

        // 取消注册歌单相关广播
        try { unregisterReceiver(playlistsUpdatedReceiver); } catch (Exception ignore) {}
        try { unregisterReceiver(playlistChangedReceiver); } catch (Exception ignore) {}
        try {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            lbm.unregisterReceiver(downloadsUiReceiver);
        } catch (Exception ignore) {}
        try {
            downloadsRefreshExecutor.shutdownNow();
        } catch (Exception ignore) {}
    }

    // 确保播放进度条可见的方法
    private void ensureProgressBarVisible() {
        if (progressBar != null) {
            // 播放进度条应保持可见，避免被列表切换逻辑误隐藏
            progressBar.setVisibility(View.VISIBLE);
            if (seekBar != null) seekBar.setVisibility(View.VISIBLE);
            progressBar.invalidate();
        }
    }

    // 更新时间显示
    private void updateTimeDisplay(long position, long duration) {
        // 确保时间值有效
        position = Math.max(0, position);
        duration = Math.max(0, duration);
        
        // 如果持续时间异常大，则设为0
        if (duration > 3600000 * 10) { // 超过10小时，可能是异常值
            duration = 0;
        }
        
        String positionStr = formatTime(position);
        String durationStr = formatTime(duration);
        timeDisplay.setText(positionStr + " / " + durationStr);
    }
    
    // 格式化时间显示
    private String formatTime(long timeMs) {
        long seconds = timeMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    /**
     * 显示字母索引对话框
     */
    private void showLetterIndexDialog() {
        if (recyclerView.getAdapter() == null || recyclerView.getAdapter().getItemCount() == 0) {
            Toast.makeText(this, R.string.error_no_songs, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(recyclerView.getAdapter() instanceof com.watch.limusic.adapter.AllSongsRangeAdapter)) {
            RecyclerView.Adapter<?> ad = recyclerView.getAdapter();
            if (ad instanceof com.watch.limusic.adapter.ArtistAdapter) {
                com.watch.limusic.adapter.ArtistAdapter aa = (com.watch.limusic.adapter.ArtistAdapter) ad;
                java.util.List<String> letters = aa.getAvailableIndexLetters();
                if (letters == null || letters.isEmpty()) return;
                LetterIndexDialog dialog = new LetterIndexDialog(this, letters);
                dialog.setOnLetterSelectedListener(letter -> {
                    int position = aa.getPositionForLetter(letter);
                    if (position >= 0 && position < aa.getItemCount()) {
                        if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                            androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                            lm.scrollToPositionWithOffset(position, 0);
                        } else {
                            recyclerView.scrollToPosition(position);
                        }
                    }
                });
                dialog.show();
                return;
            }
            // 兼容专辑内歌曲时的旧路径
            if (songAdapter == null || songAdapter.getItemCount() == 0) return;
            List<String> availableLetters = songAdapter.getAvailableIndexLetters();
            if (availableLetters.isEmpty()) return;
            LetterIndexDialog dialog = new LetterIndexDialog(this, availableLetters);
            dialog.setOnLetterSelectedListener(letter -> {
                int position = songAdapter.getPositionForLetter(letter);
                if (position >= 0 && position < songAdapter.getItemCount()) {
                    recyclerView.scrollToPosition(position);
                }
            });
            dialog.show();
            return;
        }
        com.watch.limusic.adapter.AllSongsRangeAdapter rangeAdapter = (com.watch.limusic.adapter.AllSongsRangeAdapter) recyclerView.getAdapter();
        List<String> letters = rangeAdapter.getAvailableIndexLetters();
        if (letters == null || letters.isEmpty()) return;
        LetterIndexDialog dialog = new LetterIndexDialog(this, letters);
        dialog.setOnLetterSelectedListener(letter -> {
            // 记录待跳转字母，以便数据库刚刷新时纠正
            pendingJumpLetter = letter;
            int position = rangeAdapter.getPositionForLetter(letter);
            if (position >= 0 && position < rangeAdapter.getItemCount()) {
                // 使用带偏移的滚动，定位在分组首项顶端
                if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                    androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                    lm.scrollToPositionWithOffset(position, 0);
                } else {
                    recyclerView.scrollToPosition(position);
                }
                // 预取该页及相邻页，避免仅显示序号
                rangeAdapter.prefetchAround(position);
            }
        });
        dialog.show();
    }

    // 增强歌曲标题滚动效果的专用方法
    private void setupTitleScrolling() {
        // 开启无限滚动
        songTitle.setSelected(true);
        songTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        songTitle.setSingleLine(true);
        songTitle.setMarqueeRepeatLimit(-1); // 无限循环滚动
        songTitle.setHorizontalFadingEdgeEnabled(true); // 启用淡入淡出边缘效果

        // 增加滚动的稳定性
        songTitle.setFreezesText(true);
    }

    // 实现下载监听器接口
    @Override
    public void onDownloadClick(Song song) {
        Log.d(TAG, "开始下载歌曲: " + song.getTitle());
        downloadManager.downloadSong(song);
        Toast.makeText(this, "开始下载: " + song.getTitle(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDeleteDownloadClick(Song song) {
        new AlertDialog.Builder(this)
                .setTitle("删除下载")
                .setMessage("确定要删除已下载的歌曲 \"" + song.getTitle() + "\" 吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    boolean deleted = downloadManager.deleteDownload(song.getId());
                    if (deleted) {
                        Toast.makeText(this, "已删除: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                        // 更新UI显示（根据当前适配器类型刷新）
                        RecyclerView.Adapter<?> adapter = recyclerView != null ? recyclerView.getAdapter() : null;
                        if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                            ((com.watch.limusic.adapter.AllSongsRangeAdapter) adapter).updateSongDownloadStatus(song.getId());
                        } else if (songAdapter != null) {
                            songAdapter.updateSongDownloadStatus(song.getId());
                        }
                        // 广播缓存状态已变为未缓存
                        Intent cacheIntent = new Intent("com.watch.limusic.CACHE_STATUS_CHANGED");
                        cacheIntent.putExtra("songId", song.getId());
                        cacheIntent.putExtra("isCached", false);
                        sendBroadcast(cacheIntent);
                    } else {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 执行下载系统迁移
     */
    private void performDownloadSystemMigration() {
        new Thread(() -> {
            try {
                DownloadSystemMigration migration = new DownloadSystemMigration(this);

                if (migration.needsMigration()) {
                    Log.i(TAG, "检测到需要执行下载系统迁移");

                    DownloadSystemMigration.MigrationResult result = migration.performMigration();

                    runOnUiThread(() -> {
                        if (result.overallSuccess) {
                            Log.i(TAG, "下载系统迁移成功完成");
                            // 可以选择显示成功消息给用户
                        } else {
                            Log.w(TAG, "下载系统迁移失败: " + result);
                            // 可以选择显示警告消息给用户
                        }
                    });
                } else {
                    Log.d(TAG, "下载系统迁移已完成，无需重复执行");
                }

                // 检查兼容性状态
                DownloadSystemMigration.CompatibilityStatus status = migration.getCompatibilityStatus();
                Log.d(TAG, "下载系统兼容性状态: " + status);

            } catch (Exception e) {
                Log.e(TAG, "执行下载系统迁移时发生错误", e);
            }
        }).start();
    }

    // 预取并保存专辑封面
    private void prefetchAlbumCovers(List<Album> albums) {
        try {
            LocalFileDetector detector = new LocalFileDetector(this);
            for (Album album : albums) {
                String albumId = album.getId();
                if (albumId == null || albumId.isEmpty()) continue;
                // 已存在跳过
                if (detector.getDownloadedAlbumCoverPath(albumId) != null) continue;
                try {
                    // 复用 DownloadManager 的封面下载逻辑，通过HTTP保存
                    String coverUrl = NavidromeApi.getInstance(this).getCoverArtUrl(
                            album.getCoverArt() != null && !album.getCoverArt().isEmpty() ? album.getCoverArt() : albumId
                    );
                    if (coverUrl == null || coverUrl.isEmpty()) continue;
                    java.net.URL url = new java.net.URL(coverUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    conn.connect();
                    if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
                        conn.disconnect();
                        continue;
                    }
                    java.io.File coversDir = new java.io.File(getExternalFilesDir(null), "downloads/covers");
                    if (!coversDir.exists()) coversDir.mkdirs();
                    java.io.File outFile = new java.io.File(coversDir, albumId + ".jpg");
                    try (java.io.InputStream in = conn.getInputStream();
                         java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignoreOuter) {}
    }

    private void showSkeleton() {
        if (skeletonContainer != null) skeletonContainer.setVisibility(View.VISIBLE);
        if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
        if (errorContainer != null) errorContainer.setVisibility(View.GONE);
    }

    private void showEmpty(String message) {
        if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
        if (errorContainer != null) errorContainer.setVisibility(View.GONE);
        if (emptyContainer != null) emptyContainer.setVisibility(View.VISIBLE);
        if (emptyMessageView != null && message != null) emptyMessageView.setText(message);
    }

    private void showError(String message) {
        if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
        if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
        if (errorContainer != null) errorContainer.setVisibility(View.VISIBLE);
        if (errorMessageView != null && message != null) errorMessageView.setText(message);
    }

    // 辅助：是否为一级菜单视图
    private boolean isFirstLevelView(String view) {
        return "albums".equals(view) || "songs".equals(view) || "artists".equals(view) || "playlists".equals(view) || "search".equals(view);
    }
    
    // 切换为根视图（汉堡菜单）导航模式
    private void enterRootNavigationMode() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setHomeButtonEnabled(false);
            // 清除自定义标题视图，恢复Toolbar自身标题
            getSupportActionBar().setDisplayShowCustomEnabled(false);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        // 清除可能设置的返回图标点击（避免整栏可点）
        toolbar.setOnClickListener(null);
        // 让 DrawerToggle 接管导航图标
        if (drawerToggle != null) {
            // 先清空自定义图标，防止残留
            toolbar.setNavigationIcon(null);
            drawerToggle.setDrawerIndicatorEnabled(true);
            drawerToggle.syncState();
        }
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
    }
    
    // 切换为专辑详情（返回箭头）导航模式，并禁用抽屉
    private void enterAlbumDetailNavigationMode(CharSequence titleText) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
            // 使用自定义标题视图以实现无限滚动
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayShowCustomEnabled(true);
            android.view.View custom = getLayoutInflater().inflate(R.layout.toolbar_title_marquee, null);
            android.widget.TextView tv = custom.findViewById(R.id.toolbar_title);
            CharSequence t = titleText;
            if (t == null || t.length() == 0) {
                if ("album_songs".equals(currentView) && currentAlbumTitle != null) t = currentAlbumTitle;
                else if ("playlist_detail".equals(currentView) && currentPlaylistTitle != null) t = currentPlaylistTitle;
            }
            tv.setText(t != null ? t : "");
            try { tv.setSelected(true); } catch (Exception ignore) {}
            androidx.appcompat.app.ActionBar.LayoutParams lp = new androidx.appcompat.app.ActionBar.LayoutParams(
                androidx.appcompat.app.ActionBar.LayoutParams.WRAP_CONTENT,
                androidx.appcompat.app.ActionBar.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            custom.setPadding(0, 0, 0, 0);
            getSupportActionBar().setCustomView(custom, lp);
        }
        if (drawerToggle != null) {
            drawerToggle.setDrawerIndicatorEnabled(false);
            drawerToggle.syncState();
        }
        // 使用白色返回箭头图标
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white);
        toolbar.setNavigationOnClickListener(v -> navigateBackToAlbums());
        // 禁用抽屉
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
    }
    
    // 从专辑详情返回到专辑选择界面（不刷新数据，直接切换适配器）
    private void navigateBackToAlbums() {
        // 如果适配器未初始化或没有数据，走加载流程
        if (albumAdapter == null || albumAdapter.getItemCount() == 0) {
            resetUiForNewView();
            loadAlbums();
            return;
        }
        currentView = "albums";
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("专辑");
        }
        if (recyclerView.getAdapter() != albumAdapter) {
            recyclerView.setAdapter(albumAdapter);
            searchFooterAdapter = null;
        }
        // 恢复进入专辑前的滚动位置
        restoreAlbumsScrollPosition();
        // 切换回汉堡菜单模式
        enterRootNavigationMode();
        // 更新菜单
        invalidateOptionsMenu();
    }

    private void navigateBackToPlaylists() {
        currentView = "playlists";
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.playlist);
        }
        // 直接刷新并切换适配器
        loadPlaylists();
        enterRootNavigationMode();
        invalidateOptionsMenu();
    }

    private void loadPlaylists() {
        // resetUiForNewView(); // 调用方统一清理
        enterRootNavigationMode();
        showSkeleton();
        progressBar.setVisibility(View.VISIBLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.playlist);
        }
        // 修正重试按钮行为
        try {
            if (retryEmptyButton != null) retryEmptyButton.setOnClickListener(v -> { showSkeleton(); loadPlaylists(); });
            if (retryErrorButton != null) retryErrorButton.setOnClickListener(v -> { showSkeleton(); loadPlaylists(); });
        } catch (Exception ignore) {}

        playlistAdapter = new com.watch.limusic.adapter.PlaylistAdapter(this, new com.watch.limusic.adapter.PlaylistAdapter.OnPlaylistListener() {
            @Override public void onClick(com.watch.limusic.database.PlaylistEntity playlist) {
                long pid = playlist.getLocalId();
                if (pid <= 0) {
                    try {
                        String sid = playlist.getServerId();
                        if (sid != null && !sid.isEmpty()) {
                            pid = playlistRepository.ensureLocalFromRemoteHeader(sid, playlist.getName(), playlist.isPublic(), playlist.getSongCount(), playlist.getChangedAt());
                            playlist.setLocalId(pid);
                        }
                    } catch (Exception ignore) {}
                }
                if (pid > 0) {
                    openPlaylistDetail(pid, playlist.getName());
                } else {
                    Toast.makeText(MainActivity.this, "歌单数据未就绪，请稍后", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onLongClick(View anchor, com.watch.limusic.database.PlaylistEntity playlist) {
                String[] items = new String[]{"删除", playlist.isPublic() ? "设为私有" : "设为公开", "重命名", "手动同步"};
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(playlist.getName())
                        .setItems(items, (dialog, which) -> {
                            if (which == 0) {
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("确认删除")
                                        .setMessage("确认删除歌单'" + playlist.getName() + "'吗？")
                                        .setPositiveButton("删除", (d, w) -> {
                                            long pid = playlist.getLocalId();
                                            if (pid <= 0) {
                                                try {
                                                    String sid = playlist.getServerId();
                                                    if (sid != null && !sid.isEmpty()) {
                                                        pid = playlistRepository.ensureLocalFromRemoteHeader(sid, playlist.getName(), playlist.isPublic(), playlist.getSongCount(), playlist.getChangedAt());
                                                        playlist.setLocalId(pid);
                                                    }
                                                } catch (Exception ignore) {}
                                            }
                                            if (pid > 0) { playlistRepository.delete(pid); }
                                            loadPlaylists();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                            } else if (which == 1) {
                                long pid = playlist.getLocalId();
                                if (pid <= 0) {
                                    try {
                                        String sid = playlist.getServerId();
                                        if (sid != null && !sid.isEmpty()) {
                                            pid = playlistRepository.ensureLocalFromRemoteHeader(sid, playlist.getName(), playlist.isPublic(), playlist.getSongCount(), playlist.getChangedAt());
                                            playlist.setLocalId(pid);
                                        }
                                    } catch (Exception ignore) {}
                                }
                                if (pid > 0) {
                                    boolean target = !playlist.isPublic();
                                    playlistRepository.setPublic(pid, target);
                                    playlist.setPublic(target);
                                }
                                loadPlaylists();
                            } else if (which == 2) {
                                final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                                input.setText(playlist.getName());
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("重命名")
                                        .setView(input)
                                        .setPositiveButton("确定", (d, w) -> {
                                            try { playlistRepository.rename(playlist.getLocalId(), input.getText().toString()); loadPlaylists(); }
                                            catch (Exception e) { Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                            } else if (which == 3) {
                                long pid = playlist.getLocalId();
                                if (pid <= 0) {
                                    try {
                                        String sid = playlist.getServerId();
                                        if (sid != null && !sid.isEmpty()) {
                                            pid = playlistRepository.ensureLocalFromRemoteHeader(sid, playlist.getName(), playlist.isPublic(), playlist.getSongCount(), playlist.getChangedAt());
                                            playlist.setLocalId(pid);
                                        }
                                    } catch (Exception ignore) {}
                                }
                                if (pid > 0) {
                                    Toast.makeText(MainActivity.this, "正在同步...", Toast.LENGTH_SHORT).show();
                                    playlistRepository.manualBindAndSync(pid, (ok, msg) -> runOnUiThread(() -> {
                                        Toast.makeText(MainActivity.this, msg != null ? msg : (ok ? "同步完成" : "同步失败"), Toast.LENGTH_SHORT).show();
                                        loadPlaylists();
                                    }));
                                }
                            }
                        }).show();
            }
        });
        recyclerView.setAdapter(playlistAdapter);

        // 本地优先：先秒出本地歌单
        new Thread(() -> {
            try {
                java.util.List<com.watch.limusic.database.PlaylistEntity> list = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().getAll();
                runOnUiThread(() -> {
                    if (list != null && !list.isEmpty()) {
                        playlistAdapter.submitList(list);
                        try { progressBar.setVisibility(View.GONE); } catch (Exception ignore) {}
                        try { if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE); } catch (Exception ignore) {}
                    }
                });
            } catch (Exception ignore) {}
        }).start();

        new Thread(() -> {
            boolean shownFromNetwork = false;
            try {
                try {
                    // 直接从网络拉取并先行展示（不再依赖 isNetworkAvailable 变量）
                    com.watch.limusic.api.NavidromeApi.PlaylistsEnvelope env = com.watch.limusic.api.NavidromeApi.getInstance(MainActivity.this).getPlaylists();
                    com.watch.limusic.api.NavidromeApi.PlaylistsResponse r = env != null ? env.getResponse() : null;
                    java.util.List<com.watch.limusic.api.NavidromeApi.Playlist> remote = r != null && r.getPlaylists() != null ? r.getPlaylists().getList() : java.util.Collections.emptyList();
                    if (remote != null && !remote.isEmpty()) {
                        java.util.List<com.watch.limusic.database.PlaylistEntity> uiList = new java.util.ArrayList<>();
                        java.util.Map<String, String> coverMap = new java.util.HashMap<>();
                        for (com.watch.limusic.api.NavidromeApi.Playlist rp : remote) {
                            // 先用本地信息覆盖（重命名/公开状态即时生效）
                            com.watch.limusic.database.PlaylistEntity local = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().getByServerId(rp.getId());
                            com.watch.limusic.database.PlaylistEntity pe;
                            if (local != null) {
                                // 即使本地曾标记软删除，也优先按远端可见；不跳过
                                pe = local;
                                pe.setSongCount(rp.getSongCount());
                                pe.setChangedAt(rp.getChanged());
                                // 当服务端没有 owner 或为空时保留本地 owner
                                String owner = rp.getOwner();
                                if (owner != null && !owner.isEmpty()) pe.setOwner(owner);
                            } else {
                                pe = new com.watch.limusic.database.PlaylistEntity(rp.getName(), rp.isPublic());
                                pe.setServerId(rp.getId());
                                pe.setSongCount(rp.getSongCount());
                                pe.setChangedAt(rp.getChanged());
                                pe.setOwner(rp.getOwner());
                            }
                            uiList.add(pe);
                            // 收集封面 id
                            if (rp.getCoverArt() != null && !rp.getCoverArt().isEmpty()) {
                                coverMap.put(rp.getId(), rp.getCoverArt());
                            }
                        }
                        final java.util.Map<String, String> finalCoverMap = coverMap;
                        shownFromNetwork = true;
                        runOnUiThread(() -> {
                            // 去重并按最近变更排序（serverId优先）
                            java.util.LinkedHashMap<String, com.watch.limusic.database.PlaylistEntity> dedup = new java.util.LinkedHashMap<>();
                            java.util.Collections.sort(uiList, (a,b) -> Long.compare(b.getChangedAt(), a.getChangedAt()));
                            try {
                                java.util.List<com.watch.limusic.database.PlaylistEntity> localsOnly = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().getLocalOnlyActive();
                                if (localsOnly != null && !localsOnly.isEmpty()) uiList.addAll(localsOnly);
                            } catch (Exception ignore) {}
                            for (com.watch.limusic.database.PlaylistEntity pe : uiList) {
                                String key = pe.getName();
                                com.watch.limusic.database.PlaylistEntity existing = dedup.get(key);
                                if (existing == null) dedup.put(key, pe);
                                else {
                                    boolean existingBound = existing.getServerId() != null && !existing.getServerId().isEmpty();
                                    boolean currentBound = pe.getServerId() != null && !pe.getServerId().isEmpty();
                                    if (!existingBound && currentBound) dedup.put(key, pe);
                                }
                            }
                            java.util.ArrayList<com.watch.limusic.database.PlaylistEntity> viewList2 = new java.util.ArrayList<>(dedup.values());
                            playlistAdapter.submitList(viewList2);
                            playlistAdapter.setCoverArtMap(finalCoverMap);
                            progressBar.setVisibility(View.GONE);
                            if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                            if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
                            if (errorContainer != null) errorContainer.setVisibility(View.GONE);
                            ensureProgressBarVisible();
                        });
                        // 先持久化远端头部，再进行本地对账，确保切回仍可见
                        try { playlistRepository.syncPlaylistsHeader(); } catch (Exception ignore) {}
                    }
                } catch (Exception netEx) {
                    // ignore, fallback to DB below
                }

                // 同步一次本地头部与服务端（即使服务端为空也要对账清理）
                try { playlistRepository.syncPlaylistsHeader(); } catch (Exception ignore) {}
                // 自动绑定并同步本地未绑定歌单
                try { playlistRepository.autoBindAndSyncPending(); } catch (Exception ignore) {}
                // 二次清理本地孤儿（此处保留清理，但已不删除待绑定项）
                try { com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().deleteOrphanLocalPlaylists(); } catch (Exception ignore) {}

                // DB 回退/刷新
                List<com.watch.limusic.database.PlaylistEntity> list = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().getAll();
                final boolean fromNet = shownFromNetwork;
                final java.util.List<com.watch.limusic.database.PlaylistEntity> listForUi = (list != null) ? new java.util.ArrayList<>(list) : new java.util.ArrayList<>();
                runOnUiThread(() -> {
                    if (!fromNet) {
                        // 合并本地-only 活跃歌单，并按名称去重（serverId优先，否则保留最新）
                        try {
                            java.util.List<com.watch.limusic.database.PlaylistEntity> localsOnly = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().getLocalOnlyActive();
                            if (localsOnly != null && !localsOnly.isEmpty()) listForUi.addAll(localsOnly);
                        } catch (Exception ignore) {}
                        java.util.Collections.sort(listForUi, (a,b) -> Long.compare(b.getChangedAt(), a.getChangedAt()));
                        java.util.LinkedHashMap<String, com.watch.limusic.database.PlaylistEntity> dedup = new java.util.LinkedHashMap<>();
                        for (com.watch.limusic.database.PlaylistEntity pe : listForUi) {
                            String key = pe.getName();
                            com.watch.limusic.database.PlaylistEntity existing = dedup.get(key);
                            if (existing == null) dedup.put(key, pe);
                            else {
                                boolean existingBound = existing.getServerId() != null && !existing.getServerId().isEmpty();
                                boolean currentBound = pe.getServerId() != null && !pe.getServerId().isEmpty();
                                if (!existingBound && currentBound) dedup.put(key, pe);
                            }
                        }
                        java.util.ArrayList<com.watch.limusic.database.PlaylistEntity> viewList = new java.util.ArrayList<>(dedup.values());
                        playlistAdapter.submitList(viewList);
                    }
                    progressBar.setVisibility(View.GONE);
                    if ((listForUi == null || listForUi.isEmpty()) && !fromNet) {
                        showEmpty("暂无歌单");
                    } else {
                        if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                        if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
                        if (errorContainer != null) errorContainer.setVisibility(View.GONE);
                    }
                    ensureProgressBarVisible();
                });
            } catch (Exception e) {
                Log.e(TAG, "加载歌单失败", e);
                runOnUiThread(() -> { progressBar.setVisibility(View.GONE); showError("加载歌单失败"); ensureProgressBarVisible(); });
            }
        }).start();
    }

    private void openPlaylistDetail(long playlistLocalId, String name) {
        // 先统一清理当前视图，避免短暂显示上一个列表内容
        resetUiForNewView();
        currentView = "playlist_detail";
        currentPlaylistTitle = name;
        enterAlbumDetailNavigationMode(name);
        // 设置返回键逻辑为返回歌单列表
        toolbar.setNavigationOnClickListener(v -> navigateBackToPlaylists());
        if (songAdapter == null) songAdapter = new SongAdapter(this, this);
        // 进入前隐藏列表，禁用动画，避免骨架与列表交叠与重绘重叠
        try {
            if (recyclerView != null) {
                recyclerView.setVisibility(View.INVISIBLE);
                recyclerView.setItemAnimator(null);
            }
        } catch (Exception ignore) {}
        recyclerView.setAdapter(songAdapter);
        try { songAdapter.setHighlightKeyword(null); } catch (Exception ignore) {}
        songAdapter.setShowCoverArt(false);
        songAdapter.setPlaylistDetail(true);
        try {
            String sid = null;
            if (bound && playerService != null && playerService.getCurrentSong() != null) sid = playerService.getCurrentSong().getId();
            if (sid == null) sid = getSharedPreferences("player_prefs", MODE_PRIVATE).getString("last_song_id", null);
            songAdapter.setCurrentPlaying(sid, bound && playerService != null && playerService.isPlaying());
            updateLocateButtonVisibility(sid);
        } catch (Exception ignore) {}
        // 进入歌单详情时退出选择模式，避免旧状态干扰
        selectionMode = false;
        if (songAdapter != null) songAdapter.setSelectionMode(false);
        // 首屏展示骨架，避免先闪旧数据
        showSkeleton();
        // 为歌单详情配置长按进入选择模式
        songAdapter.setOnItemLongClickListener(pos -> {
            com.watch.limusic.model.Song s = songAdapter.getSongItemAt(pos).getSong();
            selectionMode = true;
            selectedSongIds.clear();
            selectedSongIds.add(s.getId());
            songAdapter.setSelectionMode(true);
            songAdapter.toggleSelect(s.getId());
            songAdapter.setOnSelectionChangedListener(count -> {
                selectedSongIds.clear();
                selectedSongIds.addAll(songAdapter.getSelectedIdsInOrder());
                updateSelectionTitle();
                invalidateOptionsMenu();
            });
            updateSelectionTitle();
            invalidateOptionsMenu();
            updateNavigationForSelectionMode();
        });
        // 更新菜单，隐藏"添加到歌单"按钮
        invalidateOptionsMenu();
        // 本地先显
        currentPlaylistLocalId = playlistLocalId;
        final long tokenPid = playlistLocalId;
        new Thread(() -> {
            List<Song> songs = playlistRepository.getSongsInPlaylist(playlistLocalId, 500, 0);
            runOnUiThread(() -> {
                if ("playlist_detail".equals(currentView) && currentPlaylistLocalId == tokenPid) {
                    songAdapter.processAndSubmitListKeepOrder(songs);
                    // 数据到达，收起骨架并显示列表
                    if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                    if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
                    if (errorContainer != null) errorContainer.setVisibility(View.GONE);
                    try { if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE); } catch (Exception ignore) {}
                }
            });
        }).start();
        // 轻量校验并可能刷新
        new Thread(() -> {
            boolean refreshed = playlistRepository.validateAndMaybeRefreshFromServer(playlistLocalId);
            if (refreshed) {
                List<Song> songs2 = playlistRepository.getSongsInPlaylist(playlistLocalId, 500, 0);
                runOnUiThread(() -> {
                    if ("playlist_detail".equals(currentView) && currentPlaylistLocalId == tokenPid) {
                        songAdapter.processAndSubmitListKeepOrder(songs2);
                        // 刷新后确保骨架关闭且列表可见
                        if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                        try { if (recyclerView != null && recyclerView.getVisibility() != View.VISIBLE) recyclerView.setVisibility(View.VISIBLE); } catch (Exception ignore) {}
                    }
                });
            }
        }).start();
        // 确保进度条在详情页首屏可见，并同步一次播放器UI
        ensureProgressBarVisible();
        if (bound && playerService != null) {
            updatePlaybackState();
        }
    }

    private void updateSelectionTitle() {
        if (getSupportActionBar() == null) return;
        android.view.View v = getSupportActionBar().getCustomView();
        android.widget.TextView tv = v != null ? (android.widget.TextView) v.findViewById(R.id.toolbar_title) : null;
        if (selectionMode) {
            String txt = "已选择 " + selectedSongIds.size() + " 首";
            // 搜索页：切换为纯标题模式（隐藏搜索框），显示选择数量
            if ("search".equals(currentView)) {
                try {
                    if (getSupportActionBar() != null) {
                        getSupportActionBar().setDisplayShowTitleEnabled(true);
                        getSupportActionBar().setDisplayShowCustomEnabled(false);
                    }
                } catch (Exception ignore) {}
            }
            if (tv != null) tv.setText(txt);
            getSupportActionBar().setTitle(txt);
            if ("playlist_detail".equals(currentView)) enableDragHandleInPlaylistDetail(true);
            // 搜索页禁用输入
            if (searchInput != null) { searchInput.setEnabled(false); searchInput.setFocusable(false); searchInput.setFocusableInTouchMode(false); }
            // 搜索页多选：强制刷新菜单以显示右上角"添加到歌单"
            invalidateOptionsMenu();
        } else {
            if ("album_songs".equals(currentView)) {
                CharSequence t = currentAlbumTitle != null ? currentAlbumTitle : "";
                if (tv != null) tv.setText(t);
                getSupportActionBar().setTitle(t);
            } else if ("playlist_detail".equals(currentView)) {
                CharSequence t = currentPlaylistTitle != null ? currentPlaylistTitle : "";
                if (tv != null) tv.setText(t);
                getSupportActionBar().setTitle(t);
                enableDragHandleInPlaylistDetail(false);
            } else if ("songs".equals(currentView)) {
                if (tv != null) tv.setText(getString(R.string.all_songs));
                getSupportActionBar().setTitle(R.string.all_songs);
            } else if ("albums".equals(currentView)) {
                if (tv != null) tv.setText("专辑");
                getSupportActionBar().setTitle("专辑");
            } else if ("artist_songs".equals(currentView)) {
                CharSequence t = currentArtistName != null ? currentArtistName : getString(R.string.artists);
                if (tv != null) tv.setText(t);
                getSupportActionBar().setTitle(t);
            } else if ("search".equals(currentView)) {
                // 退出选择模式：还原搜索框（统一函数）
                applySearchToolbar();
                // 强制刷新菜单，隐藏添加入口
                invalidateOptionsMenu();
            }
        }
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedSongIds.clear();
        RecyclerView.Adapter<?> ad = recyclerView.getAdapter();
        if (ad instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
            ((com.watch.limusic.adapter.AllSongsRangeAdapter) ad).setSelectionMode(false);
        } else if (songAdapter != null) {
            songAdapter.setSelectionMode(false);
        }
        // 下载管理页：关闭已下载适配器的选择模式并恢复标题
        if ("downloads".equals(currentView)) {
            if (downloadedSongAdapter != null) downloadedSongAdapter.setSelectionMode(false);
            try { if (headerCompleted != null) headerCompleted.setSelectAllVisible(false, null); } catch (Exception ignore) {}
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.download_management);
        } else if ("songs".equals(currentView)) {
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.all_songs);
        } else if ("album_songs".equals(currentView)) {
            if (getSupportActionBar() != null && currentAlbumTitle != null) getSupportActionBar().setTitle(currentAlbumTitle);
        } else if ("artist_songs".equals(currentView)) {
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(currentArtistName != null ? currentArtistName : getString(R.string.artists));
        } else if ("search".equals(currentView)) {
            // 搜索页：强制恢复搜索框（统一方法）
            applySearchToolbar();
        }
        invalidateOptionsMenu();
        // 恢复刷新
        if (swipeRefresh != null) swipeRefresh.setEnabled(true);
        restoreNavigationForView();
    }

    private void performAddToPlaylist(long targetPlaylistLocalId) {
        if (targetPlaylistLocalId <= 0) return;
        // 当前在该歌单详情，拒绝
        if ("playlist_detail".equals(currentView) && currentPlaylistLocalId == targetPlaylistLocalId) {
            Toast.makeText(this, "当前已在该歌单，无法重复添加", Toast.LENGTH_SHORT).show();
            return;
        }
        // 收集选择顺序
        java.util.List<String> ordered = new java.util.ArrayList<>(selectedSongIds);
        if (ordered.isEmpty()) {
            Toast.makeText(this, "未选择歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "已保存到本地，正在同步…", Toast.LENGTH_SHORT).show();
        playlistRepository.addSongsAtHeadFiltered(targetPlaylistLocalId, ordered, (skippedTitles, serverOk) -> {
            runOnUiThread(() -> {
                StringBuilder tip = new StringBuilder();
                if (skippedTitles != null && !skippedTitles.isEmpty()) {
                    String joined = android.text.TextUtils.join("、", skippedTitles);
                    tip.append("跳过已存在：").append(joined);
                }
                tip.append(tip.length()>0?"\n":"").append(serverOk?"歌单保存成功":"本地保存成功，服务器同步失败");
                showQueuedTip(tip.toString());
                // 若当前打开的是目标歌单详情，刷新之（不自动跳转）
                if ("playlist_detail".equals(currentView) && currentPlaylistLocalId == targetPlaylistLocalId) {
                    java.util.List<com.watch.limusic.model.Song> s2 = playlistRepository.getSongsInPlaylist(targetPlaylistLocalId, 500, 0);
                    if (songAdapter != null) songAdapter.processAndSubmitListKeepOrder(s2);
                }
                // 退出选择模式
                exitSelectionMode();
            });
        });
    }

    private void updateNavigationForSelectionMode() {
        if (!selectionMode) return;
        if (getSupportActionBar() != null) {
            // 左上角行为：点击退出选择模式
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white);
            toolbar.setNavigationOnClickListener(v -> exitSelectionMode());
        }
        if (swipeRefresh != null) swipeRefresh.setEnabled(false);
    }

    private void restoreNavigationForView() {
        if ("albums".equals(currentView) || "songs".equals(currentView) || "artists".equals(currentView) || "playlists".equals(currentView) || "downloads".equals(currentView)) { hideBottomHint();
            enterRootNavigationMode();
        } else if ("search".equals(currentView)) { hideBottomHint();
            enterSearchNavigationMode();
        } else if ("album_songs".equals(currentView)) {
            // 专辑详情保留原有返回行为
            enterAlbumDetailNavigationMode(currentAlbumTitle);
        } else if ("artist_songs".equals(currentView)) {
            enterArtistDetailNavigationMode(currentArtistName);
        } else if ("playlist_detail".equals(currentView)) {
            enterAlbumDetailNavigationMode(currentPlaylistTitle);
            toolbar.setNavigationOnClickListener(v -> navigateBackToPlaylists());
        }
    }

    private void enableDragHandleInPlaylistDetail(boolean enable) {
        if (songAdapter != null) songAdapter.setShowDragHandle(enable, vh -> {
            if (itemTouchHelper != null) itemTouchHelper.startDrag(vh);
        });
        if (enable && itemTouchHelper == null) {
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback cb = new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(androidx.recyclerview.widget.ItemTouchHelper.UP | androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
                private Integer dragStart = null;
                private Integer lastTo = null;
                @Override public boolean isLongPressDragEnabled() { return false; }
                @Override public boolean onMove(androidx.recyclerview.widget.RecyclerView rv, androidx.recyclerview.widget.RecyclerView.ViewHolder vh, androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
                    int from = vh.getBindingAdapterPosition();
                    int to = target.getBindingAdapterPosition();
                    if (dragStart == null) dragStart = from;
                    lastTo = to;
                    if (songAdapter != null) songAdapter.moveItem(from, to); // 仅视觉移动
                    return true;
                }
                @Override public void clearView(androidx.recyclerview.widget.RecyclerView rv, androidx.recyclerview.widget.RecyclerView.ViewHolder vh) {
                    super.clearView(rv, vh);
                    if (songAdapter != null) songAdapter.commitOrderSnapshot();
                    if (dragStart != null && lastTo != null && !dragStart.equals(lastTo)) {
                        final int fromFinal = dragStart;
                        final int toFinal = lastTo;
                        // 开启抑制窗口，避免刚提交重排引发的广播刷新导致跳动
                        suppressPlaylistLocalId = currentPlaylistLocalId;
                        suppressPlaylistChangedUntilMs = System.currentTimeMillis() + 1500L;
                        // 停止任何惯性滚动，不做自动对齐
                        try { rv.stopScroll(); } catch (Exception ignore) {}
                        new Thread(() -> {
                            playlistRepository.reorder(currentPlaylistLocalId, fromFinal, toFinal);
                        }).start();
                    }
                    dragStart = null;
                    lastTo = null;
                }
                @Override public void onSwiped(androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder, int direction) {}
            };
            itemTouchHelper = new androidx.recyclerview.widget.ItemTouchHelper(cb);
            itemTouchHelper.attachToRecyclerView(recyclerView);
        }
        if (!enable && itemTouchHelper != null) {
            itemTouchHelper.attachToRecyclerView(null);
            itemTouchHelper = null;
        }
    }

    private void restorePlayerUiFromPrefs() {
        try {
            SharedPreferences prefs = getSharedPreferences("player_prefs", MODE_PRIVATE);
            String songId = prefs.getString("last_song_id", null);
            String title = prefs.getString("last_title", null);
            String artist = prefs.getString("last_artist", null);
            String albumId = prefs.getString("last_album_id", null);
            int savedMode = prefs.getInt("last_playback_mode", PlayerService.PLAYBACK_MODE_REPEAT_ALL);
            long position = prefs.getLong("last_position", 0L);

            if (songId == null || songId.isEmpty()) return; // 没有历史状态

            // 标题/艺人
            if (songTitle != null) songTitle.setText(title != null ? title : "");
            if (songArtist != null) songArtist.setText(artist != null ? artist : "");

            // 封面：优先本地，不做网络请求以降低启动成本
            if (albumArt != null) {
                boolean loaded = false;
                if (albumId != null && !albumId.isEmpty()) {
                    String localCover = new LocalFileDetector(this).getDownloadedAlbumCoverPath(albumId);
                    if (localCover != null) {
                        Glide.with(this)
                                .load("file://" + localCover)
                                .apply(new com.bumptech.glide.request.RequestOptions()
                                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                                        .disallowHardwareConfig()
                                        .dontAnimate()
                                        .override(150, 150)
                                        .placeholder(R.drawable.default_album_art)
                                        .error(R.drawable.default_album_art))
                                .into(albumArt);
                        loaded = true;
                    }
                }
                if (!loaded) albumArt.setImageResource(R.drawable.default_album_art);
            }

            // 播放模式按钮图标
            if (repeatModeButton != null) {
                int iconRes;
                switch (savedMode) {
                    case PlayerService.PLAYBACK_MODE_REPEAT_ONE:
                        iconRes = R.drawable.ic_repeat_one; break;
                    case PlayerService.PLAYBACK_MODE_SHUFFLE:
                        iconRes = R.drawable.ic_shuffle; break;
                    case PlayerService.PLAYBACK_MODE_REPEAT_ALL:
                    default:
                        iconRes = R.drawable.ic_repeat; break;
                }
                repeatModeButton.setImageResource(iconRes);
            }

            // 进度条与时间文本（尽力估计 duration）
            int durationMs = 0;
            try {
                com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getSongById(songId);
                if (se != null) durationMs = Math.max(0, se.getDuration() * 1000); // DB 为秒，UI 用毫秒
            } catch (Exception ignore) {}

            if (seekBar != null) {
                if (durationMs > 0) seekBar.setMax(durationMs); else seekBar.setMax(0);
                seekBar.setProgress((int) Math.max(0, Math.min(position, durationMs > 0 ? durationMs : Integer.MAX_VALUE)));
            }
            if (progressBar != null) {
                if (durationMs > 0) progressBar.setMax(durationMs);
                progressBar.setProgress((int) Math.max(0, Math.min(position, durationMs > 0 ? durationMs : Integer.MAX_VALUE)));
                progressBar.setVisibility(View.VISIBLE);
                progressBar.invalidate();
            }
            // 更新时间显示文本
            try { updateTimeDisplay(position, durationMs); } catch (Exception ignore) {}
        } catch (Exception ignore) {}
    }

	@Override
	protected void onNewIntent(android.content.Intent intent) {
		super.onNewIntent(intent);
		if (intent == null) return;
		try {
			long reqPid = intent.getLongExtra("open_playlist_local_id", -1L);
			if (reqPid > 0) {
				com.watch.limusic.database.PlaylistEntity pe = com.watch.limusic.database.MusicDatabase.getInstance(this).playlistDao().getByLocalId(reqPid);
				String name = pe != null ? pe.getName() : "";
				openPlaylistDetail(reqPid, name);
			}
		} catch (Exception ignore) {}
    }

    // 当返回前台且处于播放状态时，自动绑定服务以恢复UI心跳更新
    private void maybeBindIfPlaying() {
        try {
            if (bound) return;
            android.content.SharedPreferences prefs = getSharedPreferences("player_prefs", MODE_PRIVATE);
            String lastSongId = prefs.getString("last_song_id", null);
            // 放宽条件：只要存在上一首记录，就绑定以恢复心跳
            if (lastSongId != null && !lastSongId.isEmpty()) {
                bindService();
            }
        } catch (Exception ignore) {}
    }

    // ===== 服务器签名自检与更新 =====
    private String buildCurrentServerSignature() {
        try {
            SharedPreferences prefs = getSharedPreferences("navidrome_settings", MODE_PRIVATE);
            String url = prefs.getString("server_url", "");
            String port = prefs.getString("server_port", "4533");
            String username = prefs.getString("username", "");
            if (url == null) url = "";
            if (port == null || port.isEmpty()) port = "4533";
            if (username == null) username = "";
            if (url.isEmpty()) return "";
            return url + "|" + port + "|" + username;
        } catch (Exception e) {
            return "";
        }
    }

    private void checkAndHandleServerSignatureChange() {
        String current = buildCurrentServerSignature();
        if (current == null || current.isEmpty()) return;
        SharedPreferences sp = getSharedPreferences("ui_prefs", MODE_PRIVATE);
        String last = sp.getString("last_server_signature", "");
        if (!current.equals(last)) {
            // 先写入，以避免重复触发
            sp.edit().putString("last_server_signature", current).apply();
            try { musicRepository.purgeAllOnServerSwitch(); } catch (Exception ignore) {}
            try {
                resetUiForNewView();
                loadAllSongs();
                Toast.makeText(this, "已切换至新服务器，列表已刷新", Toast.LENGTH_SHORT).show();
            } catch (Exception ignore) {}
        }
    }

    // 打开全屏播放器覆盖层
    private void openFullPlayer() {
        if (isFullPlayerVisible) return;
        if (fullPlayerOverlay == null) {
            try {
                if (fullPlayerStub != null) {
                    fullPlayerOverlay = fullPlayerStub.inflate();
                } else {
                    fullPlayerOverlay = findViewById(R.id.full_player_overlay);
                }
            } catch (Exception e) {
                fullPlayerOverlay = findViewById(R.id.full_player_overlay);
            }
            // 首次inflate后，初始化控件与事件
            initFullPlayerViews(fullPlayerOverlay);
            // 初始化歌词控制器与页指示器
            try {
                dotMain = fullPlayerOverlay.findViewById(R.id.dot_main);
                dotLyrics = fullPlayerOverlay.findViewById(R.id.dot_lyrics);
                updatePageIndicator();
                // 页指示器点击切换
                if (dotMain != null) dotMain.setOnClickListener(v -> switchFullPage(0));
                if (dotLyrics != null) dotLyrics.setOnClickListener(v -> switchFullPage(1));
                // 在歌曲信息区域支持左右滑切页，避免干扰底部控件
                View infoArea = fullPlayerOverlay.findViewById(R.id.full_info_container);
                if (infoArea != null) {
                    infoArea.setOnTouchListener(new com.watch.limusic.util.SwipeGestureListener(this) {
                        @Override public void onSwipeRight() { switchFullPage(0); }
                        @Override public void onSwipeLeft() { if (!isLowPowerEnabled()) switchFullPage(1); }
                        @Override public void onLongPress() {}
                    });
                }
                View indicator = fullPlayerOverlay.findViewById(R.id.page_indicator);
                if (indicator != null) {
                    indicator.setOnTouchListener(new com.watch.limusic.util.SwipeGestureListener(this) {
                        @Override public void onSwipeRight() { switchFullPage(0); }
                        @Override public void onSwipeLeft() { if (!isLowPowerEnabled()) switchFullPage(1); }
                        @Override public void onLongPress() {}
                    });
                }
                // 不预加载歌词页，只有在用户首次横向切换到歌词页时才加载

            } catch (Exception ignore) {}
        }
        if (fullPlayerOverlay == null) return;
        // 确保置顶并拦截触摸
        try { fullPlayerOverlay.bringToFront(); } catch (Exception ignore) {}
        try { fullPlayerOverlay.setClickable(true); } catch (Exception ignore) {}
        try { fullPlayerOverlay.setFocusable(true); } catch (Exception ignore) {}
        // 每次打开都按当前设置与曲目应用一次背景
        try { applyFullPlayerBackground(); } catch (Exception ignore) {}
        // 打开时默认进入主控页
        currentFullPage = 0;
        applyFullPageVisibility();
        // 确保页指示器同步到主控页
        try { updatePageIndicator(); } catch (Exception ignore) {}
        fullPlayerOverlay.setVisibility(View.VISIBLE);
        fullPlayerOverlay.post(() -> {
            fullPlayerOverlay.setTranslationY(fullPlayerOverlay.getHeight());
            fullPlayerOverlay.animate()
                    .translationY(0)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withStartAction(() -> {
                        isFullPlayerVisible = true;
                        // 打开时同步一次UI
                        trySyncFullPlayerUi();
                    })
                    .start();
        });
        View collapseLeft = fullPlayerOverlay.findViewById(R.id.btn_collapse_left);
        if (collapseLeft != null) collapseLeft.setOnClickListener(v -> closeFullPlayer());
        View returnPlayer = fullPlayerOverlay.findViewById(R.id.btn_return_player);
        if (returnPlayer != null) returnPlayer.setOnClickListener(v -> animateReturnToMain());
        // 扩大滑动判定区域（除音量条外）
        try {
            View volume = findViewByIdName(fullPlayerOverlay, "volume_seek");
            View seekArea = findViewByIdName(fullPlayerOverlay, "full_seek_container");
            horizontalTouchSlopPx = dpToPx(8);
            fullPlayerOverlay.setOnTouchListener((v, ev) -> {
                if (isLowPowerEnabled()) return false;
                int action = ev.getActionMasked();
                switch (action) {
                    case android.view.MotionEvent.ACTION_DOWN: {
                        dragStartX = ev.getX();
                        dragStartY = ev.getY();
                        lastDragDx = 0f;
                        isDraggingPages = false;
                        return false; }
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = ev.getX() - dragStartX;
                        float dy = ev.getY() - dragStartY;
                        if (!isDraggingPages) {
                            // 排除进度条与音量条区域
                            if (isPointInsideView(ev, volume) || isPointInsideView(ev, seekArea)) return false;
                            if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > horizontalTouchSlopPx) {
                                // 禁止从歌词页通过右滑返回
                                if (currentFullPage == 1 && dx > 0) return false;
                                isDraggingPages = true;
                                // 首次向左拖动时，确保歌词已inflate可见
                                if (dx < 0) ensureLyricsController();
                            } else {
                                return false;
                            }
                        }
                        lastDragDx = dx;
                        applyDragOffset(dx);
                        return true; }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        if (isDraggingPages) {
                            settleDrag(lastDragDx);
                            isDraggingPages = false;
                            return true;
                        }
                        return false; }
                }
                return false;
            });
            if (volume != null) { volume.setOnTouchListener((vv,e) -> false); }
        } catch (Exception ignore) {}
    }

    // 关闭全屏播放器覆盖层
    private void closeFullPlayer() {
        if (!isFullPlayerVisible || fullPlayerOverlay == null) return;
        stopSeamlessTitleScroll();
        fullPlayerOverlay.animate()
                .translationY(fullPlayerOverlay.getHeight())
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    fullPlayerOverlay.setVisibility(View.GONE);
                    isFullPlayerVisible = false;
                })
                .start();
    }

    private void initFullPlayerViews(View root) {
        if (root == null) return;
        btnPrevFull = root.findViewById(R.id.btn_prev_full);
        btnPlayPauseFull = root.findViewById(R.id.btn_play_pause_full);
        btnNextFull = root.findViewById(R.id.btn_next_full);
        btnPlayModeFull = root.findViewById(R.id.btn_play_mode_full);
        fullSeekBar = root.findViewById(R.id.full_seekbar);
        fullProgress = root.findViewById(R.id.full_progress);
        fullSongTitle = root.findViewById(R.id.full_song_title);
        fullSongArtist = root.findViewById(R.id.full_song_artist);
        fullAudioBadge = root.findViewById(R.id.full_audio_badge);
        // 标题采用与迷你播放器一致的跑马灯
        try { if (fullSongTitle != null) { fullSongTitle.setSelected(true); } } catch (Exception ignore) {}
        		btnVolumeToggle = root.findViewById(R.id.btn_volume_toggle);
		volumeOverlay = root.findViewById(R.id.volume_overlay);
        volumeSeek = root.findViewById(R.id.volume_seek);
        sleepOverlay = root.findViewById(R.id.sleep_overlay);
        sleepSeek = root.findViewById(R.id.sleep_seek);
        sleepAfterCurrentCheck = root.findViewById(R.id.sleep_after_current);
        fullBgImage = root.findViewById(R.id.bg_album_blur);
        // 应用一次背景
        applyFullPlayerBackground();
        // 音量接入与按钮切换
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null && volumeSeek != null) {
                int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                volumeSeek.setMax(maxVol);
                volumeSeek.setProgress(curVol);
                volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser) return;
                        try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0); } catch (Exception ignore) {}
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }
        } catch (Exception ignore) {}
        		if (btnVolumeToggle != null) {
			btnVolumeToggle.setOnClickListener(v -> toggleVolumeOverlay());
		}
		if (volumeOverlay != null) {
			volumeOverlay.setOnClickListener(v -> toggleVolumeOverlay());
		}
        if (btnPrevFull != null) {
            btnPrevFull.setOnClickListener(v -> {
                if (bound && playerService != null) {
                    playerService.previous();
                }
            });
        }
        if (btnPlayPauseFull != null) {
            btnPlayPauseFull.setOnClickListener(v -> {
                if (!bound) {
                    pendingStartPlayOnBind = !isPlayingSafely();
                    bindService();
                    return;
                }
                if (playerService.isPlaying()) {
                    playerService.pause();
                    btnPlayPauseFull.setImageResource(R.drawable.ic_play_rounded);
                } else {
                    playerService.play();
                    btnPlayPauseFull.setImageResource(R.drawable.ic_pause_rounded);
                }
            });
        }
        if (btnNextFull != null) {
            btnNextFull.setOnClickListener(v -> {
                if (bound && playerService != null) {
                    playerService.next();
                }
            });
        }
        if (btnPlayModeFull != null) {
            btnPlayModeFull.setOnClickListener(v -> {
                if (bound && playerService != null) {
                    playerService.togglePlaybackMode();
                    updateFullPlaybackModeButton();
                }
            });
        }
        if (fullSeekBar != null) {
            fullSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        if (fullProgress != null) fullProgress.setProgress(progress);
                        if (bound && playerService != null) {
                            long dur = playerService.getDuration();
                            long fb = 0L;
                            if (dur <= 0) {
                                try {
                                    SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                                    String sid = sp.getString("last_song_id", null);
                                    if (sid != null) {
                                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).songDao().getSongById(sid);
                                        if (se != null && se.getDuration() > 0) fb = (long) se.getDuration() * 1000L;
                                    }
                                } catch (Exception ignore) {}
                            }
                            long useDur = dur > 0 ? dur : fb;
                            if (fullSongArtist != null) fullSongArtist.setText(formatTime(progress) + " / " + (useDur > 0 ? formatTime(useDur) : "--:--"));
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { userIsSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    userIsSeeking = false;
                    if (bound && playerService != null) {
                        int progress = seekBar.getProgress();
                        if (fullSeekBar != null && fullSeekBar.getMax() > 0) {
                            if (lastIsSeekable && !lastDurationUnset) {
                                playerService.seekTo(progress);
                                if (fullProgress != null) fullProgress.setProgress(progress);
                            } else {
                                pendingSeekMs = progress;
                                Toast.makeText(MainActivity.this, "当前曲目暂不支持拖动，已记录目标位置", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            // 时长未定：记录一次待执行 seek（绑定到当前曲目）
                            pendingSeekMs = progress;
                            pendingSeekSongId = (playerService != null && playerService.getCurrentSong() != null) ? playerService.getCurrentSong().getId() : null;
                            Toast.makeText(MainActivity.this, "正在获取时长，稍后将跳转", Toast.LENGTH_SHORT).show();
                        }
                        suppressProgressFromBroadcast = true;
                        suppressUntilMs = System.currentTimeMillis() + 600L;
                    }
                    if (fullSongArtist != null && bound && playerService != null) {
                        fullSongArtist.setText(playerService.getCurrentArtist());
                    }
                }
            });
        }
        btnMore = root.findViewById(R.id.btn_more);
        if (btnMore != null) {
            btnMore.setOnClickListener(v -> toggleSleepOverlay());
        }
        if (sleepOverlay != null) {
            sleepOverlay.setOnClickListener(v -> toggleSleepOverlay());
            // 阻止点击卡片内部冒泡关闭
            View card = sleepOverlay.findViewById(R.id.sleep_card);
            if (card != null) { card.setOnClickListener(v -> {}); }
        }
        if (sleepSeek != null) {
            // 0 表示关闭，1-60 表示分钟
            sleepSeek.setMax(60);
            sleepSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    TextView tv = root.findViewById(R.id.sleep_value);
                    if (tv != null) {
                        if (progress <= 0) {
                            tv.setText(getString(R.string.sleep_timer_value_off));
                        } else {
                            tv.setText(getString(R.string.sleep_timer_value_minutes, progress));
                        }
                    }
                    if (fromUser && bound && playerService != null) {
                        boolean waitFinish = sleepAfterCurrentCheck != null && sleepAfterCurrentCheck.isChecked();
                        if (progress <= 0) {
                            playerService.cancelSleepTimer();
                        } else {
                            playerService.setSleepTimerMinutes(progress, waitFinish);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
        if (sleepAfterCurrentCheck != null) {
            sleepAfterCurrentCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int minutes = sleepSeek != null ? sleepSeek.getProgress() : 0;
                if (!bound || playerService == null) return;
                if (minutes <= 0 && isChecked) {
                    // 仅勾选，不设分钟 -> 纯"播完本首"模式
                    playerService.setSleepStopAfterCurrent();
                } else if (minutes > 0) {
                    // 覆盖设置：分钟 + 播完当前
                    playerService.setSleepTimerMinutes(minutes, true);
                } else {
                    playerService.cancelSleepTimer();
                }
            });
        }
    }

    private boolean isPlayingSafely() {
        try { return bound && playerService != null && playerService.isPlaying(); } catch (Exception ignore) { return false; }
    }

    private void trySyncFullPlayerUi() {
        // 确保跑马灯与徽标所需的焦点属性
        try {
            if (fullSongTitle != null) {
                fullSongTitle.setSelected(true);
                fullSongTitle.setFocusable(true);
                fullSongTitle.setFocusableInTouchMode(true);
            }
        } catch (Exception ignore) {}

        if (!bound || playerService == null) return;
        boolean isPlaying = playerService.isPlaying();
        if (btnPlayPauseFull != null) {
            btnPlayPauseFull.setImageResource(isPlaying ? R.drawable.ic_pause_rounded : R.drawable.ic_play_rounded);
        }
        long dur = playerService.getDuration();
        int max;
        if (dur <= 0) {
            long fb = 0L;
            try {
                String sid = playerService.getCurrentSong() != null ? playerService.getCurrentSong().getId() : null;
                if (sid == null) {
                    SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
                    sid = sp.getString("last_song_id", null);
                }
                if (sid != null) {
                    com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getSongById(sid);
                    if (se != null && se.getDuration() > 0) fb = (long) se.getDuration() * 1000L;
                }
            } catch (Exception ignore) {}
            max = (int) Math.max(0, fb);
        } else {
            max = (int) Math.max(0, dur);
        }
        if (fullSeekBar != null) fullSeekBar.setMax(max);
        if (fullProgress != null) fullProgress.setMax(max);
        if (fullProgress != null) fullProgress.setVisibility(View.VISIBLE);
        int pos = (int) playerService.getCurrentPosition();
        if (!userIsSeeking) {
            if (fullSeekBar != null) fullSeekBar.setProgress(pos);
            if (fullProgress != null) fullProgress.setProgress(pos);
        }
        if (fullSongTitle != null) {
            String t = playerService.getCurrentTitle();
            fullSongTitle.setText(t != null ? t : "");
            try { fullSongTitle.setSelected(true); } catch (Exception ignore) {}
        }
        if (fullSongArtist != null) fullSongArtist.setText(playerService.getCurrentArtist());
        // 音频类型徽标（根据设置；若本地缓存为空则向服务查询）
        try {
            boolean show = getSharedPreferences("player_prefs", MODE_PRIVATE).getBoolean("show_audio_type_badge", true);
            if (fullAudioBadge != null) {
                String badge = (lastAudioType != null && !lastAudioType.isEmpty()) ? lastAudioType : (bound && playerService != null ? safeGetAudioType() : null);
                if (show && badge != null && !badge.isEmpty()) {
                    fullAudioBadge.setText(badge);
                    fullAudioBadge.setVisibility(View.VISIBLE);
                } else {
                    fullAudioBadge.setVisibility(View.GONE);
                }
            }
        } catch (Exception ignore) {}
        updateFullPlaybackModeButton();
        // 同步歌词（若在歌词页）
        if (lyricsController != null) lyricsController.onPlaybackTick();
    }

    private void updateFullPlaybackModeButton() {
        if (btnPlayModeFull == null || !bound || playerService == null) return;
        int mode = playerService.getPlaybackMode();
        int iconRes;
        switch (mode) {
            case PlayerService.PLAYBACK_MODE_REPEAT_ALL:
                iconRes = R.drawable.ic_repeat;
                break;
            case PlayerService.PLAYBACK_MODE_REPEAT_ONE:
                iconRes = R.drawable.ic_repeat_one;
                break;
            case PlayerService.PLAYBACK_MODE_SHUFFLE:
                iconRes = R.drawable.ic_shuffle;
                break;
            default:
                iconRes = R.drawable.ic_repeat_off;
                break;
        }
        btnPlayModeFull.setImageResource(iconRes);
    }

    private boolean isLowPowerEnabled() {
        try {
            SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
            return sp.getBoolean("low_power_mode_enabled", false);
        } catch (Exception e) { return false; }
    }

    private void applyFullPlayerBackground() {
        try {
            if (fullBgImage == null) return;
            SharedPreferences sp = getSharedPreferences("player_prefs", MODE_PRIVATE);
            boolean lowPower = sp.getBoolean("low_power_mode_enabled", false);
            boolean blurEnabled = sp.getBoolean("bg_blur_enabled", true);
            int intensity = sp.getInt("bg_blur_intensity", 50);
            float intensity01 = Math.max(0f, Math.min(1f, (float) intensity / 100f));
            if (lowPower) {
                // 省电模式：黑色背景
                BlurUtils.clearEffect(fullBgImage);
                fullBgImage.setImageDrawable(null);
                fullBgImage.setBackgroundColor(getResources().getColor(R.color.background));
                return;
            }
            String albumId = null;
            String key = null;
            if (playerService != null && playerService.getCurrentSong() != null) {
                albumId = playerService.getCurrentSong().getAlbumId();
                key = (albumId != null && !albumId.isEmpty()) ? albumId : playerService.getCurrentSong().getCoverArtUrl();
            }
            // 回退：未绑定服务时使用上次歌曲ID从DB取专辑
            if (key == null) {
                try {
                    String sid = sp.getString("last_song_id", null);
                    if (sid != null) {
                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getSongById(sid);
                        if (se != null) {
                            albumId = se.getAlbumId();
                            key = (albumId != null && !albumId.isEmpty()) ? albumId : se.getCoverArt();
                        }
                    }
                } catch (Exception ignore) {}
            }
            String localCover = (albumId != null && !albumId.isEmpty()) ? localFileDetector.getDownloadedAlbumCoverPath(albumId) : null;
            String coverArtUrl = (localCover != null) ? ("file://" + localCover) : (key != null ? NavidromeApi.getInstance(this).getCoverArtUrl(key) : null);
            if (coverArtUrl == null) {
                // 无可用封面，置黑背景
                BlurUtils.clearEffect(fullBgImage);
                fullBgImage.setImageDrawable(null);
                fullBgImage.setBackgroundColor(getResources().getColor(R.color.background));
                return;
            }
            if (!blurEnabled) {
                BlurUtils.clearEffect(fullBgImage);
                Glide.with(this).load(coverArtUrl).diskCacheStrategy(localCover != null ? DiskCacheStrategy.NONE : DiskCacheStrategy.AUTOMATIC).into(fullBgImage);
                return;
            }
            final String finalKey = key; final float finalIntensity01 = intensity01; com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap> target = new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                @Override public void onResourceReady(android.graphics.Bitmap resource, com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        BlurUtils.applyBlurTo(fullBgImage, resource, intensity01);
                    } else {
                        try {
                            android.graphics.Bitmap blurred = BlurUtils.getOrCreateBlur(finalKey, resource, finalIntensity01);
                            fullBgImage.setImageBitmap(blurred);
                        } catch (Exception e) {
                            fullBgImage.setImageBitmap(resource);
                        }
                    }
                }
                @Override public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {}
            };
            Glide.with(this)
                .asBitmap()
                .load(coverArtUrl)
                .diskCacheStrategy(localCover != null ? DiskCacheStrategy.NONE : DiskCacheStrategy.AUTOMATIC)
                .into(target);
        } catch (Exception ignore) {}
    }

    private void recordAlbumsScrollPosition() {
        try {
            if (recyclerView == null) return;
            if (!(recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager)) return;
            androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
            int pos = lm.findFirstVisibleItemPosition();
            android.view.View v = lm.findViewByPosition(pos);
            int offset = v != null ? v.getTop() : 0;
            albumsScrollPos = Math.max(0, pos);
            albumsScrollOffset = offset;
        } catch (Exception ignore) {}
    }

    private void restoreAlbumsScrollPosition() {
        try {
            if (recyclerView == null) return;
            if (!(recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager)) { recyclerView.scrollToPosition(Math.max(0, albumsScrollPos)); return; }
            androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
            lm.scrollToPositionWithOffset(Math.max(0, albumsScrollPos), albumsScrollOffset);
        } catch (Exception ignore) {
            try { recyclerView.scrollToPosition(Math.max(0, albumsScrollPos)); } catch (Exception ignored) {}
        }
    }

    private void ensureLyricsController() {
        if (lyricsController == null && fullPlayerOverlay != null) {
            lyricsController = new com.watch.limusic.LyricsController(this, new com.watch.limusic.LyricsController.PlayerBridge() {
                @Override public long getCurrentPosition() { return bound && playerService != null ? playerService.getCurrentPosition() : 0; }
                @Override public long getDuration() { return bound && playerService != null ? playerService.getDuration() : 0; }
                @Override public boolean isPlaying() { return bound && playerService != null && playerService.isPlaying(); }
                @Override public void seekTo(long positionMs) { if (bound && playerService != null) playerService.seekTo(positionMs); }
                @Override public String getCurrentSongId() { return bound && playerService != null && playerService.getCurrentSong() != null ? playerService.getCurrentSong().getId() : null; }
                @Override public String getCurrentArtist() { return bound && playerService != null ? playerService.getCurrentArtist() : null; }
                @Override public String getCurrentTitle() { return bound && playerService != null ? playerService.getCurrentTitle() : null; }
                @Override public int getAudioSessionId() { return bound && playerService != null ? playerService.getAudioSessionIdSafe() : 0; }
            });
            lyricsController.setUiBridge(() -> switchFullPage(0));
            lyricsController.ensureInflated(fullPlayerOverlay);
            lyricsController.loadLyricsIfNeeded();
        } else if (lyricsController != null) {
            // 已初始化则不重复触发加载，避免切页时抖动
        }
    }

    private void switchFullPage(int page) {
        if (page < 0) page = 0; if (page > 1) page = 1;
        if (currentFullPage == page) return;
        int from = currentFullPage;
        currentFullPage = page;
        View container = fullPlayerOverlay;
        // 顶部条目标权重：歌词页固定 0.5/0.5，主控页固定 1/0
        try { updateTopBarProgress(page == 1 ? 1f : 0f); } catch (Exception ignore) {}
        if (container != null) {
            float width = container.getWidth();
            float toX = (page == 1) ? -width * 0.12f : width * 0.12f;
            container.animate()
                .translationX(toX)
                .setDuration(120)
                .withEndAction(() -> {
                    container.setTranslationX(0f);
                    applyFullPageVisibility();
                    updatePageIndicator();
                })
                .start();
        } else {
            applyFullPageVisibility();
            updatePageIndicator();
        }
        if (page == 1) ensureLyricsController();
    }

    private void applyFullPageVisibility() {
        if (fullPlayerOverlay == null) return;
        View center = fullPlayerOverlay.findViewById(R.id.center_controls);
        View seek = findViewByIdName(fullPlayerOverlay, "full_seek_container");
        View bottom = fullPlayerOverlay.findViewById(R.id.bottom_row);
        View info = findViewByIdName(fullPlayerOverlay, "full_info_container");
        View lyricsContainer = fullPlayerOverlay.findViewById(R.id.lyrics_container);
        View lyricsStub = fullPlayerOverlay.findViewById(R.id.lyrics_stub);
        View returnPlayer = fullPlayerOverlay.findViewById(R.id.btn_return_player);
        if (currentFullPage == 0) {
            if (center != null) center.setVisibility(View.VISIBLE);
            if (seek != null) seek.setVisibility(View.VISIBLE);
            if (bottom != null) bottom.setVisibility(View.VISIBLE);
            if (info != null) info.setVisibility(View.VISIBLE);
            if (lyricsContainer != null) lyricsContainer.setVisibility(View.GONE);
            if (lyricsStub != null) lyricsStub.setVisibility(View.GONE);
            if (returnPlayer != null) returnPlayer.setVisibility(View.GONE);
            // 主控页可见时暂停歌词刷新，完全零开销
            if (lyricsController != null) lyricsController.pause();
        } else {
            if (center != null) center.setVisibility(View.GONE);
            if (seek != null) seek.setVisibility(View.GONE);
            if (bottom != null) bottom.setVisibility(View.GONE);
            if (info != null) info.setVisibility(View.GONE);
            // 确保歌词视图已经inflate并显示
            ensureLyricsController();
            View lc = fullPlayerOverlay.findViewById(R.id.lyrics_container);
            if (lc != null) lc.setVisibility(View.VISIBLE);
            if (returnPlayer != null) returnPlayer.setVisibility(View.VISIBLE);
            // 歌词页可见时恢复刷新
            if (lyricsController != null) lyricsController.resume();
            // 主动触发一次播放状态广播，确保可视化尽快拿到会话ID
            try { sendBroadcast(new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED")); } catch (Exception ignore) {}
        }
    }

    private void updatePageIndicator() {
        if (dotMain == null || dotLyrics == null) return;
        boolean lowPower = isLowPowerEnabled();
        dotLyrics.setVisibility(lowPower ? View.GONE : View.VISIBLE);
        if (currentFullPage == 1 && lowPower) currentFullPage = 0;
        if (currentFullPage == 0) {
            dotMain.setImageResource(R.drawable.indicator_dot_selected);
            dotLyrics.setImageResource(R.drawable.indicator_dot_unselected);
        } else {
            dotMain.setImageResource(R.drawable.indicator_dot_unselected);
            dotLyrics.setImageResource(R.drawable.indicator_dot_selected);
        }
    }

    private boolean isPointInsideView(android.view.MotionEvent ev, View target) {
        if (target == null || target.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] && x <= loc[0] + target.getWidth() && y >= loc[1] && y <= loc[1] + target.getHeight();
    }

    private void applyDragOffset(float dx) {
        if (fullPlayerOverlay == null) return;
        View center = fullPlayerOverlay.findViewById(R.id.center_controls);
        View seek = findViewByIdName(fullPlayerOverlay, "full_seek_container");
        View bottom = fullPlayerOverlay.findViewById(R.id.bottom_row);
        View info = findViewByIdName(fullPlayerOverlay, "full_info_container");
        View lyricsContainer = fullPlayerOverlay.findViewById(R.id.lyrics_container);
        int w = fullPlayerOverlay.getWidth();
        if (w <= 0) return;
        float f = Math.max(-1f, Math.min(1f, -dx / w)); // 向左为正，允许右滑到 -1
        // 确保歌词容器可见参与滑动
        if (lyricsContainer != null) lyricsContainer.setVisibility(View.VISIBLE);
        // 主控组往左推，歌词从右侧滑入（右滑回主控时降低跟随比例，留出可感知的返回动画距离）
        float factor = (f < 0f) ? 0.7f : 0.9f;
        float mainTx = -f * w * factor;
        float lyricTx = (1f - f) * w * factor;
        if (center != null) center.setTranslationX(mainTx);
        if (seek != null) seek.setTranslationX(mainTx);
        if (bottom != null) bottom.setTranslationX(mainTx);
        if (info != null) info.setTranslationX(mainTx);
        if (lyricsContainer != null) lyricsContainer.setTranslationX(lyricTx);
        // 顶部绿/蓝条宽度联动：f∈[0,1] 映射为进度
        float progress = Math.max(0f, Math.min(1f, f));
        updateTopBarProgress(progress);
    }

    // 根据切页进度调整顶部绿/蓝条的权重：0=绿满屏，1=蓝满屏
    private void updateTopBarProgress(float progress) {
        if (fullPlayerOverlay == null) return;
        View left = fullPlayerOverlay.findViewById(R.id.btn_collapse_left);
        View right = fullPlayerOverlay.findViewById(R.id.btn_return_player);
        if (left == null || right == null) return;
        try {
            android.widget.LinearLayout.LayoutParams lpL = (android.widget.LinearLayout.LayoutParams) left.getLayoutParams();
            android.widget.LinearLayout.LayoutParams lpR = (android.widget.LinearLayout.LayoutParams) right.getLayoutParams();
            float p = Math.max(0f, Math.min(1f, progress));
            float wl = 1f - 0.5f * p;
            float wr = 0.5f * p;
            // 保证宽度为0dp，由权重控制占比
            lpL.width = 0; lpR.width = 0;
            if (lpL.weight != wl || lpR.weight != wr) {
                lpL.weight = wl;
                lpR.weight = wr;
                left.setLayoutParams(lpL);
                right.setLayoutParams(lpR);
                // 始终可见，但当权重为0时宽度为0等效隐藏
                if (left.getVisibility() != View.VISIBLE) left.setVisibility(View.VISIBLE);
                if (right.getVisibility() != View.VISIBLE) right.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignore) {}
    }

    private void settleDrag(float dx) {
        if (fullPlayerOverlay == null) return;
        int w = fullPlayerOverlay.getWidth();
        if (w <= 0) return;
        float f = Math.max(-1f, Math.min(1f, -dx / w));
        boolean goLyrics = f >= 0.4f;
        View center = fullPlayerOverlay.findViewById(R.id.center_controls);
        View seek = findViewByIdName(fullPlayerOverlay, "full_seek_container");
        View bottom = fullPlayerOverlay.findViewById(R.id.bottom_row);
        View info = findViewByIdName(fullPlayerOverlay, "full_info_container");
        View lyricsContainer = fullPlayerOverlay.findViewById(R.id.lyrics_container);
        if (lyricsContainer == null) return;
        isSettling = true;
        if (goLyrics) ensureLyricsController();
        float mainTarget = goLyrics ? -w : 0f;
        float lyricTarget = goLyrics ? 0f : w * 0.25f; // 返回主控页前，保留25%路程由动画完成，避免瞬跳
        android.animation.TimeInterpolator interp = new android.view.animation.DecelerateInterpolator();
        long dur = 200L;
        // 切到主控页前，确保主控区可见参与动画
        if (!goLyrics) {
            if (center != null) center.setVisibility(View.VISIBLE);
            if (seek != null) seek.setVisibility(View.VISIBLE);
            if (bottom != null) bottom.setVisibility(View.VISIBLE);
            if (info != null) info.setVisibility(View.VISIBLE);
        }
        // 顶部条权重动画（与切页同步）
        try {
            float start = Math.max(0f, Math.min(1f, f));
            float end = goLyrics ? 1f : 0f;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(start, end);
            va.setDuration(dur);
            va.setInterpolator(interp);
            va.addUpdateListener(a -> updateTopBarProgress((Float) a.getAnimatedValue()));
            va.start();
        } catch (Exception ignore) {}
        // 暂停歌词刷新，避免动画期间列表重新定位造成抽搐
        if (lyricsController != null) lyricsController.pause();
        // 开启硬件图层以提升动画流畅度
        if (center != null) center.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (seek != null) seek.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (bottom != null) bottom.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (info != null) info.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (lyricsContainer != null) lyricsContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (center != null) center.animate().translationX(mainTarget).setDuration(dur).setInterpolator(interp).start();
        if (seek != null) seek.animate().translationX(mainTarget).setDuration(dur).setInterpolator(interp).start();
        if (bottom != null) bottom.animate().translationX(mainTarget).setDuration(dur).setInterpolator(interp).start();
        if (info != null) info.animate().translationX(mainTarget).setDuration(dur).setInterpolator(interp).start();
        if (goLyrics) {
            lyricsContainer.setVisibility(View.VISIBLE);
            lyricsContainer.animate().translationX(lyricTarget).setDuration(dur).setInterpolator(interp).withEndAction(() -> {
                // 归位并设置可见性
                if (center != null) center.setTranslationX(0f);
                if (seek != null) seek.setTranslationX(0f);
                if (bottom != null) bottom.setTranslationX(0f);
                if (info != null) info.setTranslationX(0f);
                lyricsContainer.setTranslationX(0f);
                currentFullPage = 1;
                applyFullPageVisibility();
                updatePageIndicator();
                // 关闭硬件图层，恢复歌词刷新并对齐一次
                if (center != null) center.setLayerType(View.LAYER_TYPE_NONE, null);
                if (seek != null) seek.setLayerType(View.LAYER_TYPE_NONE, null);
                if (bottom != null) bottom.setLayerType(View.LAYER_TYPE_NONE, null);
                if (info != null) info.setLayerType(View.LAYER_TYPE_NONE, null);
                if (lyricsContainer != null) lyricsContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                if (lyricsController != null) { lyricsController.resume(); }
                isSettling = false;
            }).start();
        } else {
            // 未达阈值：立即隐藏歌词容器，避免短暂闪现
            lyricsContainer.clearAnimation();
            lyricsContainer.setVisibility(View.GONE);
            lyricsContainer.setTranslationX(w);
            // 在主控动画完成时收尾
            fullPlayerOverlay.postDelayed(() -> {
                if (center != null) center.setLayerType(View.LAYER_TYPE_NONE, null);
                if (seek != null) seek.setLayerType(View.LAYER_TYPE_NONE, null);
                if (bottom != null) bottom.setLayerType(View.LAYER_TYPE_NONE, null);
                if (info != null) info.setLayerType(View.LAYER_TYPE_NONE, null);
                if (lyricsController != null) { lyricsController.resume(); }
                currentFullPage = 0;
                applyFullPageVisibility();
                updatePageIndicator();
                isSettling = false;
            }, dur);
        }
    }

    	private void toggleVolumeOverlay() {
		if (volumeOverlay == null || volumeSeek == null) return;
		boolean showing = volumeOverlay.getVisibility() == View.VISIBLE;
		if (showing) {
			volumeOverlay.setVisibility(View.GONE);
		} else {
			volumeOverlay.setVisibility(View.VISIBLE);
		}
    }

	private void animateReturnToMain() {
		if (fullPlayerOverlay == null) return;
		View center = fullPlayerOverlay.findViewById(R.id.center_controls);
		View seek = findViewByIdName(fullPlayerOverlay, "full_seek_container");
		View bottom = fullPlayerOverlay.findViewById(R.id.bottom_row);
		View info = findViewByIdName(fullPlayerOverlay, "full_info_container");
		View lyricsContainer = fullPlayerOverlay.findViewById(R.id.lyrics_container);
		if (lyricsContainer == null) return;
		int w = fullPlayerOverlay.getWidth(); if (w <= 0) w = fullPlayerOverlay.getMeasuredWidth(); if (w <= 0) w = 360;
		// 准备：确保歌词可见参与动画
		lyricsContainer.setVisibility(View.VISIBLE);
		// 暂停歌词刷新，避免动画期间抽搐
		if (lyricsController != null) lyricsController.pause();
		// 开启硬件图层提升流畅度
		if (center != null) center.setLayerType(View.LAYER_TYPE_HARDWARE, null);
		if (seek != null) seek.setLayerType(View.LAYER_TYPE_HARDWARE, null);
		if (bottom != null) bottom.setLayerType(View.LAYER_TYPE_HARDWARE, null);
		if (info != null) info.setLayerType(View.LAYER_TYPE_HARDWARE, null);
		lyricsContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
		// 初始：主控在左侧不可见位置，歌词在0
		float mainStart = -w;
		if (center != null) center.setTranslationX(mainStart);
		if (seek != null) seek.setTranslationX(mainStart);
		if (bottom != null) bottom.setTranslationX(mainStart);
		if (info != null) info.setTranslationX(mainStart);
		// 顶部条：从1 -> 0 的权重动画
		try {
			android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(1f, 0f);
			va.setDuration(220L);
			va.setInterpolator(new android.view.animation.DecelerateInterpolator());
			va.addUpdateListener(a -> updateTopBarProgress((Float) a.getAnimatedValue()));
			va.start();
		} catch (Exception ignore) {}
		// 动画：主控回到0，歌词滑到右侧 w
		android.animation.TimeInterpolator interp = new android.view.animation.DecelerateInterpolator();
		long dur = 220L;
		if (center != null) center.animate().translationX(0f).setDuration(dur).setInterpolator(interp).start();
		if (seek != null) seek.animate().translationX(0f).setDuration(dur).setInterpolator(interp).start();
		if (bottom != null) bottom.animate().translationX(0f).setDuration(dur).setInterpolator(interp).start();
		if (info != null) info.animate().translationX(0f).setDuration(dur).setInterpolator(interp).start();
		lyricsContainer.animate().translationX(w).setDuration(dur).setInterpolator(interp).withEndAction(() -> {
			// 恢复常态
			if (center != null) center.setLayerType(View.LAYER_TYPE_NONE, null);
			if (seek != null) seek.setLayerType(View.LAYER_TYPE_NONE, null);
			if (bottom != null) bottom.setLayerType(View.LAYER_TYPE_NONE, null);
			if (info != null) info.setLayerType(View.LAYER_TYPE_NONE, null);
			lyricsContainer.setLayerType(View.LAYER_TYPE_NONE, null);
			currentFullPage = 0;
			applyFullPageVisibility();
			updatePageIndicator();
		        }).start();
    }

    private void toggleSleepOverlay() {
        if (sleepOverlay == null) return;
        boolean showing = sleepOverlay.getVisibility() == View.VISIBLE;
        if (showing) {
            sleepOverlay.setVisibility(View.GONE);
        } else {
            // 打开前从服务端读取状态并回显
            try {
                if (bound && playerService != null) {
                    com.watch.limusic.service.PlayerService.SleepTimerState st = playerService.getSleepTimerState();
                    int minutes = 0;
                    if (st != null && st.remainMs > 0) {
                        minutes = (int) Math.max(0, Math.round(st.remainMs / 60000.0));
                    }
                    if (sleepSeek != null) sleepSeek.setProgress(minutes);
                    if (sleepAfterCurrentCheck != null) sleepAfterCurrentCheck.setChecked(st != null && st.waitFinishOnExpire || (st != null && st.type == com.watch.limusic.service.PlayerService.SleepType.AFTER_CURRENT));
                    TextView tv = sleepOverlay.findViewById(R.id.sleep_value);
                    if (tv != null) {
                        if (st != null && st.type == com.watch.limusic.service.PlayerService.SleepType.AFTER_CURRENT && minutes == 0) {
                            tv.setText(getString(R.string.sleep_wait_finish_current));
                        } else if (minutes <= 0) {
                            tv.setText(getString(R.string.sleep_timer_value_off));
                        } else if (minutes == 1 && st != null && st.remainMs < 60_000L) {
                            tv.setText(getString(R.string.sleep_timer_value_less_minute));
                        } else {
                            tv.setText(getString(R.string.sleep_timer_value_minutes, minutes));
                        }
                    }
                }
            } catch (Exception ignore) {}
            sleepOverlay.setVisibility(View.VISIBLE);
        }
    }

    private String safeGetAudioType() {
        try {
            if (!bound || playerService == null) return null;
            String t = playerService.getCurrentAudioType();
            if (t != null && !t.isEmpty()) {
                lastAudioType = t;
            }
            return t;
        } catch (Throwable ignore) { return null; }
    }

    private android.animation.ValueAnimator titleScrollAnimator;

    private void startSeamlessTitleScrollIfNeeded() {
        // 已改回跑马灯，不再需要自定义滚动
    }

    private void stopSeamlessTitleScroll() {
        // 已改回跑马灯，不再需要停止自定义滚动
        try {
            if (titleScrollAnimator != null) {
                titleScrollAnimator.cancel();
                titleScrollAnimator = null;
            }
        } catch (Exception ignore) {}
    }

    private String currentArtistName = null;

    private void openArtistDetail(String artistName) {
        try { updateLocateButtonVisibility(null); } catch (Exception ignore) {}
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                final java.util.List<com.watch.limusic.model.Song> songs = musicRepository.getSongsByArtist(artistName);
                runOnUiThread(() -> {
                    currentArtistName = artistName;
                    currentView = "artist_songs";
                    CharSequence titleText = artistName != null && !artistName.isEmpty() ? artistName : getString(R.string.no_artist);
                    if (getSupportActionBar() != null) getSupportActionBar().setTitle(titleText);
                    enterArtistDetailNavigationMode(titleText);

                    if (songAdapter == null) songAdapter = new com.watch.limusic.adapter.SongAdapter(MainActivity.this, this);
                    recyclerView.setAdapter(songAdapter);
                    try { songAdapter.setHighlightKeyword(null); } catch (Exception ignore) {}
                    songAdapter.setShowCoverArt(false);
                    try {
                        String sid = null;
                        if (bound && playerService != null && playerService.getCurrentSong() != null) sid = playerService.getCurrentSong().getId();
                        if (sid == null) sid = getSharedPreferences("player_prefs", MODE_PRIVATE).getString("last_song_id", null);
                        songAdapter.setCurrentPlaying(sid, bound && playerService != null && playerService.isPlaying());
                        updateLocateButtonVisibility(sid);
                    } catch (Exception ignore) {}
                    songAdapter.setOnItemLongClickListener(pos -> {
                        com.watch.limusic.model.Song s = songAdapter.getSongItemAt(pos).getSong();
                        selectionMode = true;
                        selectedSongIds.clear();
                        selectedSongIds.add(s.getId());
                        songAdapter.setSelectionMode(true);
                        songAdapter.toggleSelect(s.getId());
                        songAdapter.setOnSelectionChangedListener(count -> {
                            selectedSongIds.clear();
                            selectedSongIds.addAll(songAdapter.getSelectedIdsInOrder());
                            updateSelectionTitle();
                            invalidateOptionsMenu();
                        });
                        updateSelectionTitle();
                        invalidateOptionsMenu();
                        updateNavigationForSelectionMode();
                    });
                    songAdapter.processAndSubmitList(songs);
                    invalidateOptionsMenu();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show());
            } finally {
                runOnUiThread(this::ensureProgressBarVisible);
            }
        }).start();
    }

    private void navigateBackToArtists() {
        currentView = "artists";
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.artists);
        if (artistAdapter != null && recyclerView.getAdapter() != artistAdapter) {
            recyclerView.setAdapter(artistAdapter);
        }
        // 返回时恢复进入详情前的滚动位置
        restoreArtistsScrollPosition();
        enterRootNavigationMode();
        invalidateOptionsMenu();
        // 不强制刷新数据，保留当前列表与滚动位置
    }

    private void enterArtistDetailNavigationMode(CharSequence titleText) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayShowCustomEnabled(true);
            android.view.View custom = getLayoutInflater().inflate(R.layout.toolbar_title_marquee, null);
            android.widget.TextView tv = custom.findViewById(R.id.toolbar_title);
            tv.setText(titleText != null ? titleText : "");
            tv.setSelected(true);
            androidx.appcompat.app.ActionBar.LayoutParams lp = new androidx.appcompat.app.ActionBar.LayoutParams(
                androidx.appcompat.app.ActionBar.LayoutParams.WRAP_CONTENT,
                androidx.appcompat.app.ActionBar.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            custom.setPadding(0, 0, 0, 0);
            getSupportActionBar().setCustomView(custom, lp);
        }
        if (drawerToggle != null) {
            drawerToggle.setDrawerIndicatorEnabled(false);
            drawerToggle.syncState();
        }
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white);
        toolbar.setNavigationOnClickListener(v -> navigateBackToArtists());
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
    }

    private void loadSearchEmbedded() {
        enterRootNavigationMode();
        enterSearchNavigationMode();
        if (getSupportActionBar() != null) {
            applySearchToolbar();
        }
        currentView = "search";
        // 切换视图前确保隐藏非搜索提示
        hideBottomHint();
        searchResults.clear();
        searchQuery = "";
        searchOffset = 0;
        searchHasMore = true;
        searchLoading = false;
        if (songAdapter == null) songAdapter = new SongAdapter(this, this);
        songAdapter.setShowCoverArt(false);
        songAdapter.setShowDownloadStatus(true);
        try { songAdapter.setHighlightKeyword(null); } catch (Exception ignore) {}
        // 使用ConcatAdapter在末尾添加搜索底部提示
        androidx.recyclerview.widget.ConcatAdapter concat = new androidx.recyclerview.widget.ConcatAdapter(songAdapter, getOrInitSearchFooter());
        recyclerView.setAdapter(concat);
        // 首次滚动性能优化：禁用动画、固定大小、增加缓存池
        try {
            recyclerView.setItemAnimator(null);
            recyclerView.setHasFixedSize(true);
            androidx.recyclerview.widget.RecyclerView.RecycledViewPool pool = recyclerView.getRecycledViewPool();
            pool.setMaxRecycledViews(0, 24); // 普通行缓存
        } catch (Exception ignore) {}
        songAdapter.processAndSubmitListKeepOrder(new java.util.ArrayList<>());
        getOrInitSearchFooter().setState(com.watch.limusic.adapter.SearchFooterAdapter.State.HIDDEN);
        if (emptyContainer != null && emptyMessageView != null) {
            emptyMessageView.setText(R.string.search_empty_prompt);
            emptyContainer.setVisibility(View.VISIBLE);
        }
        // 选择模式长按入口（与其他列表一致）
        songAdapter.setOnItemLongClickListener(pos -> {
            com.watch.limusic.model.Song s = songAdapter.getSongItemAt(pos).getSong();
            selectionMode = true;
            selectedSongIds.clear();
            selectedSongIds.add(s.getId());
            songAdapter.setSelectionMode(true);
            songAdapter.toggleSelect(s.getId());
            songAdapter.setOnSelectionChangedListener(count -> {
                selectedSongIds.clear();
                selectedSongIds.addAll(songAdapter.getSelectedIdsInOrder());
                updateSelectionTitle();
                invalidateOptionsMenu();
            });
            updateSelectionTitle();
            invalidateOptionsMenu();
            updateNavigationForSelectionMode();
        });
        if (searchInput != null) {
            searchInput.setText("");
            searchInput.setHint(getString(R.string.search_hint));
            searchInput.setOnEditorActionListener((v, actionId, event) -> { startSearch(true); return true; });
            // 选择模式下禁用输入
            searchInput.setEnabled(!selectionMode);
            searchInput.setFocusable(!selectionMode);
            searchInput.setFocusableInTouchMode(!selectionMode);
            searchInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchHandler.removeCallbacks(searchDebounce);
                    searchHandler.postDelayed(searchDebounce, 250);
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                if (searchInput != null) searchInput.setText("");
                searchQuery = "";
                searchOffset = 0;
                searchHasMore = true;
                searchResults.clear();
                try { songAdapter.setHighlightKeyword(null); } catch (Exception ignore) {}
                songAdapter.processAndSubmitListKeepOrder(new java.util.ArrayList<>());
                if (emptyContainer != null && emptyMessageView != null) {
                    emptyMessageView.setText(R.string.search_empty_prompt);
                    emptyContainer.setVisibility(View.VISIBLE);
                }
            });
        }
        if (searchScrollListener != null) recyclerView.removeOnScrollListener(searchScrollListener);
        searchScrollListener = new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                if (!(rv.getLayoutManager() instanceof LinearLayoutManager)) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                int total = lm.getItemCount();
                int last = lm.findLastVisibleItemPosition();
                if (total > 0 && last >= total - 3) loadMoreSearchResults();
            }
        };
        recyclerView.addOnScrollListener(searchScrollListener);
        if (emptyContainer != null && emptyMessageView != null) {
            emptyMessageView.setText(R.string.search_empty_prompt);
            emptyContainer.setVisibility(View.VISIBLE);
            try { if (retryEmptyButton != null) retryEmptyButton.setVisibility(View.GONE); } catch (Exception ignore) {}
            try { if (retryErrorButton != null) retryErrorButton.setVisibility(View.GONE); } catch (Exception ignore) {}
        }
        ensureProgressBarVisible();
        invalidateOptionsMenu();
        saveLastView("search");
        // 初次进入提示
        Toast.makeText(this, R.string.search_hint, Toast.LENGTH_SHORT).show();
    }

    private void startSearch(boolean reset) {
        if (!"search".equals(currentView)) return;
        String q = searchInput != null && searchInput.getText() != null ? searchInput.getText().toString().trim() : "";
        if (reset) {
            searchQuery = q;
            searchOffset = 0;
            searchHasMore = true;
            searchResults.clear();
            try { songAdapter.setHighlightKeyword(q); } catch (Exception ignore) {}
            songAdapter.processAndSubmitListKeepOrder(new java.util.ArrayList<>());
            // 滚回顶部，避免首轮加载时直接跳到底部
            try {
                if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                    ((androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
                } else {
                    recyclerView.scrollToPosition(0);
                }
            } catch (Exception ignore) {}
        }
        if (q.isEmpty()) {
            if (emptyContainer != null && emptyMessageView != null) {
                emptyMessageView.setText(R.string.search_empty_prompt);
                emptyContainer.setVisibility(View.VISIBLE);
            }
            // 清空关键词时，确保隐藏任何页脚提示
            try { getOrInitSearchFooter().setState(com.watch.limusic.adapter.SearchFooterAdapter.State.HIDDEN); } catch (Exception ignore) {}
            return;
        }
        if (searchLoading) return;
        searchLoading = true;
        final int reqId = ++searchRequestId;
        // 移除"加载中"动效：首轮与分页均不显示 LOADING
        getOrInitSearchFooter().setState(com.watch.limusic.adapter.SearchFooterAdapter.State.HIDDEN);
        new Thread(() -> {
            java.util.List<com.watch.limusic.model.Song> page = musicRepository.searchSongsPaged(q, SEARCH_PAGE_SIZE, searchOffset);
            runOnUiThread(() -> {
                if (reqId != searchRequestId) { searchLoading = false; return; }
                java.util.List<com.watch.limusic.model.Song> finalPage = (page != null) ? page : new java.util.ArrayList<>();
                // footer状态：加载完成后根据是否有下一页决定
                if (finalPage.isEmpty() && searchOffset == 0) {
                    getOrInitSearchFooter().setState(com.watch.limusic.adapter.SearchFooterAdapter.State.HIDDEN);
                }
                if (reset && finalPage.isEmpty()) {
                    if (emptyContainer != null && emptyMessageView != null) {
                        emptyMessageView.setText(R.string.search_no_result);
                        emptyContainer.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
                searchResults.addAll(finalPage);
                searchOffset += finalPage.size();
                searchHasMore = finalPage.size() >= SEARCH_PAGE_SIZE;
                // 提交新结果，并在提交完成后回顶与设置 Footer（以 Diff 完成为准）
                songAdapter.processAndSubmitListKeepOrder(new java.util.ArrayList<>(searchResults), () -> {
                    try { searchLoading = false; } catch (Exception ignore) {}
                    recyclerView.post(() -> {
                        try {
                            if (reset) {
                                if (recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                                    ((androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
                                } else {
                                    recyclerView.scrollToPosition(0);
                                }
                            }
                            if (!searchHasMore && !searchResults.isEmpty()) {
                                getOrInitSearchFooter().setState(com.watch.limusic.adapter.SearchFooterAdapter.State.NOMORE);
                            } else if (searchHasMore && !searchResults.isEmpty()) {
                                getOrInitSearchFooter().setState(com.watch.limusic.adapter.SearchFooterAdapter.State.MORE);
                            } else {
                                getOrInitSearchFooter().setState(com.watch.limusic.adapter.SearchFooterAdapter.State.HIDDEN);
                            }
                        } catch (Exception ignore) {}
                    });
                });
                try {
                    String sid = null;
                    if (bound && playerService != null && playerService.getCurrentSong() != null) sid = playerService.getCurrentSong().getId();
                    if (sid == null) sid = getSharedPreferences("player_prefs", MODE_PRIVATE).getString("last_song_id", null);
                    songAdapter.setCurrentPlaying(sid, bound && playerService != null && playerService.isPlaying());
                    updateLocateButtonVisibility(sid);
                } catch (Exception ignore) {}
            });
        }).start();
    }

    private void loadMoreSearchResults() {
        if (!"search".equals(currentView)) return;
        if (!searchHasMore || searchLoading) return;
        // 移除分页加载中的动画提示
        startSearch(false);
    }

    private void showBottomHint(String text, boolean animate) {
        try {
            if (bottomHintBar == null) return;
            bottomHintBar.setText(text != null ? text : "");
            if (bottomHintBar.getVisibility() != View.VISIBLE) {
                bottomHintBar.setVisibility(View.VISIBLE);
                if (animate) {
                    bottomHintBar.setAlpha(0f);
                    bottomHintBar.animate().alpha(1f).setDuration(150).start();
                } else {
                    bottomHintBar.setAlpha(1f);
                }
            } else if (animate) {
                bottomHintBar.setAlpha(0.6f);
                bottomHintBar.animate().alpha(1f).setDuration(120).start();
            }
        } catch (Exception ignore) {}
    }

    private void hideBottomHint() {
        try {
            if (bottomHintBar == null) return;
            if (bottomHintBar.getVisibility() == View.VISIBLE) {
                bottomHintBar.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    bottomHintBar.setVisibility(View.GONE);
                    bottomHintBar.setAlpha(1f);
                }).start();
            }
        } catch (Exception ignore) {}
    }

    private void applySearchToolbar() {
        try {
            if (getSupportActionBar() == null) return;
            getSupportActionBar().setDisplayShowCustomEnabled(false);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setCustomView(null);
            getSupportActionBar().setDisplayShowCustomEnabled(true);
            android.view.View custom = getLayoutInflater().inflate(R.layout.toolbar_search_embedded, null);
            androidx.appcompat.app.ActionBar.LayoutParams lp = new androidx.appcompat.app.ActionBar.LayoutParams(
                    androidx.appcompat.app.ActionBar.LayoutParams.MATCH_PARENT,
                    androidx.appcompat.app.ActionBar.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            getSupportActionBar().setCustomView(custom, lp);
            searchInput = custom.findViewById(R.id.search_input);
            btnClearSearch = custom.findViewById(R.id.btn_clear_search);
            if (searchInput != null) {
                searchInput.setEnabled(!selectionMode);
                searchInput.setFocusable(!selectionMode);
                searchInput.setFocusableInTouchMode(!selectionMode);
                searchInput.setText(searchQuery != null ? searchQuery : "");
                searchInput.setOnEditorActionListener((viewEditor, actionId, event) -> { startSearch(true); return true; });
                searchInput.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        searchHandler.removeCallbacks(searchDebounce);
                        searchHandler.postDelayed(searchDebounce, 250);
                    }
                    @Override public void afterTextChanged(android.text.Editable s) {}
                });
            }
            if (btnClearSearch != null) {
                btnClearSearch.setOnClickListener(v -> {
                    if (searchInput != null) searchInput.setText("");
                    searchQuery = "";
                    searchOffset = 0;
                    searchHasMore = true;
                    searchResults.clear();
                    try { songAdapter.setHighlightKeyword(null); } catch (Exception ignore) {}
                    songAdapter.processAndSubmitListKeepOrder(new java.util.ArrayList<>());
                    if (emptyContainer != null && emptyMessageView != null) {
                        emptyMessageView.setText(R.string.search_empty_prompt);
                        emptyContainer.setVisibility(View.VISIBLE);
                    }
                });
            }
        } catch (Exception ignore) {}
    }

    private void enterSearchNavigationMode() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setHomeButtonEnabled(false);
            // 与根视图不同：保留自定义搜索视图，不显示标题文本
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayShowCustomEnabled(true);
        }
        toolbar.setOnClickListener(null);
        if (drawerToggle != null) {
            toolbar.setNavigationIcon(null);
            drawerToggle.setDrawerIndicatorEnabled(true);
            drawerToggle.syncState();
        }
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
    }

    private void showQueuedTip(String message) {
        if (message == null || message.isEmpty()) return;
        tipQueue.offerLast(message);
        showNextTipIfAny();
    }

    private void showNextTipIfAny() {
        if (tipShowing) return;
        String msg = tipQueue.pollFirst();
        if (msg == null) return;
        tipShowing = true;
        Toast t = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        t.show();
        // 统一时长控制：SHORT 基础上延长 800ms，保证可读
        tipHandler.removeCallbacks(tipDequeueRunnable);
        tipHandler.postDelayed(tipDequeueRunnable, 2000);
    }

    // 新增：艺术家列表滚动位置记录
    private void recordArtistsScrollPosition() {
        try {
            if (recyclerView == null) return;
            if (!(recyclerView.getAdapter() instanceof com.watch.limusic.adapter.ArtistAdapter)) return;
            if (!(recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager)) return;
            androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
            int pos = lm.findFirstVisibleItemPosition();
            android.view.View v = lm.findViewByPosition(pos);
            int offset = v != null ? v.getTop() : 0;
            artistsScrollPos = Math.max(0, pos);
            artistsScrollOffset = offset;
        } catch (Exception ignore) {}
    }

    // 新增：艺术家列表滚动位置恢复
    private void restoreArtistsScrollPosition() {
        try {
            if (recyclerView == null) return;
            if (!(recyclerView.getAdapter() instanceof com.watch.limusic.adapter.ArtistAdapter)) return;
            if (!(recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager)) { recyclerView.scrollToPosition(Math.max(0, artistsScrollPos)); return; }
            androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
            lm.scrollToPositionWithOffset(Math.max(0, artistsScrollPos), artistsScrollOffset);
        } catch (Exception ignore) {
            try { recyclerView.scrollToPosition(Math.max(0, artistsScrollPos)); } catch (Exception ignored) {}
        }
    }

    // 配置可用性安全检查（UI 侧防御性短路）
    private boolean isApiConfiguredSafe() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("navidrome_settings", MODE_PRIVATE);
            String url = prefs.getString("server_url", "");
            String port = prefs.getString("server_port", "4533");
            String username = prefs.getString("username", "");
            String password = prefs.getString("password", "");
            if (url == null || url.trim().isEmpty()) return false;
            if (username == null || username.trim().isEmpty()) return false;
            if (password == null || password.trim().isEmpty()) return false;
            try { Integer.parseInt(port == null ? "4533" : port); } catch (Exception e) { return false; }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private com.watch.limusic.adapter.SectionHeaderAdapter headerActive;
    private com.watch.limusic.adapter.SectionHeaderAdapter headerCompleted;
    private com.watch.limusic.adapter.DownloadTaskAdapter downloadTaskAdapter;
    private com.watch.limusic.adapter.DownloadedSongAdapter downloadedSongAdapter;
    private androidx.recyclerview.widget.ConcatAdapter downloadsConcatAdapter;
    private boolean downloadsHeaderActiveExpanded = true;
    private boolean downloadsHeaderCompletedExpanded = true;
    private final java.util.ArrayList<DownloadInfo> activeTaskSnapshot = new java.util.ArrayList<>();
    // 下载页“全选”按钮点击逻辑（仅多选模式下可见）
    private android.view.View.OnClickListener downloadsSelectAllClickListener;

    private void openDownloadsEmbedded() {
        hideBottomHint();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            getSupportActionBar().setTitle(R.string.download_management);
        }
        enterRootNavigationMode();
        currentView = "downloads";
        // 进入页面时默认展开两栏，并退出任何残留的选择模式
        selectionMode = false;
        selectedSongIds.clear();
        downloadsHeaderActiveExpanded = true;
        downloadsHeaderCompletedExpanded = true;
        if (downloadTaskAdapter == null) downloadTaskAdapter = new DownloadTaskAdapter(this);
        if (downloadedSongAdapter == null) downloadedSongAdapter = new DownloadedSongAdapter(this, this);
        // 明确隐藏右侧下载区域图标（保持行右侧不显示）
        try { downloadedSongAdapter.setShowDownloadStatus(false); } catch (Exception ignore) {}
        headerActive = new SectionHeaderAdapter("正在下载");
        headerCompleted = new SectionHeaderAdapter("已下载");
        // 点击区头实现折叠/展开
        View.OnClickListener toggleActive = v -> {
            downloadsHeaderActiveExpanded = !downloadsHeaderActiveExpanded;
            rebuildDownloadsConcat();
            // 折叠/展开后立即强制刷新一次，更新计数文案
            try { refreshDownloadsData(true); } catch (Exception ignore) {}
        };
        View.OnClickListener toggleCompleted = v -> {
            downloadsHeaderCompletedExpanded = !downloadsHeaderCompletedExpanded;
            rebuildDownloadsConcat();
            try { refreshDownloadsData(true); } catch (Exception ignore) {}
        };
        headerActive.setOnClickListener(toggleActive);
        headerCompleted.setOnClickListener(toggleCompleted);
        // 定义“全选”点击行为
        downloadsSelectAllClickListener = v -> {
            try {
                if (!selectionMode || downloadedSongAdapter == null) return;
                java.util.List<String> allIds = new java.util.ArrayList<>();
                for (int i = 0; i < downloadedSongAdapter.getItemCount(); i++) {
                    com.watch.limusic.model.SongWithIndex swi = downloadedSongAdapter.getSongItemAt(i);
                    if (swi != null && swi.getSong() != null) allIds.add(swi.getSong().getId());
                }
                try { downloadedSongAdapter.clearSelection(); } catch (Exception ignore2) {}
                selectedSongIds.clear();
                selectedSongIds.addAll(allIds);
                downloadedSongAdapter.setSelectionMode(true);
                for (String sid : allIds) downloadedSongAdapter.toggleSelect(sid);
                updateSelectionTitle();
                invalidateOptionsMenu();
            } catch (Exception ignore3) {}
        };
        // 初始根据多选状态控制可见性（进入页面默认非多选，隐藏）
        headerCompleted.setSelectAllVisible(selectionMode, downloadsSelectAllClickListener);
        recyclerView.setItemAnimator(null);
        recyclerView.setHasFixedSize(true);
        rebuildDownloadsConcat();
        // 为"已下载"列表设置长按进入多选
        try {
            downloadedSongAdapter.setOnItemLongClickListener(pos -> {
                com.watch.limusic.model.Song s = downloadedSongAdapter.getSongItemAt(pos).getSong();
                if (s == null) return;
                selectionMode = true;
                selectedSongIds.clear();
                selectedSongIds.add(s.getId());
                downloadedSongAdapter.setSelectionMode(true);
                downloadedSongAdapter.toggleSelect(s.getId());
                downloadedSongAdapter.setOnSelectionChangedListener(count -> {
                    selectedSongIds.clear();
                    selectedSongIds.addAll(downloadedSongAdapter.getSelectedIdsInOrder());
                    updateSelectionTitle();
                    invalidateOptionsMenu();
                });
                try { headerCompleted.setSelectAllVisible(true, downloadsSelectAllClickListener); } catch (Exception ignore) {}
                updateSelectionTitle();
                invalidateOptionsMenu();
                updateNavigationForSelectionMode();
            });
        } catch (Exception ignore) {}
        // 长按手势：正在下载 -> 取消该任务；已下载 -> 交由适配器长按回调进入多选
        try {
            recyclerView.addOnItemTouchListener(new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                final android.view.GestureDetector detector = new android.view.GestureDetector(MainActivity.this, new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public void onLongPress(android.view.MotionEvent e) {
                        android.view.View child = recyclerView.findChildViewUnder(e.getX(), e.getY());
                        if (child == null) return;
                        int pos = recyclerView.getChildAdapterPosition(child);
                        if (pos == RecyclerView.NO_POSITION) return;
                        RecyclerView.Adapter<?> ad = recyclerView.getAdapter();
                        if (!(ad instanceof androidx.recyclerview.widget.ConcatAdapter)) return;
                        androidx.recyclerview.widget.ConcatAdapter ca = (androidx.recyclerview.widget.ConcatAdapter) ad;
                        int offset = 0;
                        for (RecyclerView.Adapter<?> sub : ca.getAdapters()) {
                            int count = sub.getItemCount();
                            if (pos >= offset && pos < offset + count) {
                                int innerPos = pos - offset;
                                if (sub == downloadTaskAdapter) {
                                    com.watch.limusic.model.DownloadInfo di = downloadTaskAdapter.getCurrentList().get(innerPos);
                                    if (di != null && di.getSongId() != null) {
                                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                                .setTitle("取消下载")
                                                .setMessage("确定取消这首歌的下载吗？")
                                                .setPositiveButton("确定", (d,w) -> {
                                                    try { DownloadManager.getInstance(MainActivity.this).cancelDownload(di.getSongId()); } catch (Exception ignore) {}
                                                    refreshDownloadsData();
                                                })
                                                .setNegativeButton("取消", null)
                                                .show();
                                    }
                                }
                                break;
                            }
                            offset += count;
                        }
                    }
                });
                @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                    detector.onTouchEvent(e);
                    return false;
                }
            });
        } catch (Exception ignore) {}
        // 启动时兜底修复被强杀后卡住的任务
        try { com.watch.limusic.download.DownloadManager.getInstance(this).reconcileStaleDownloads(); } catch (Exception ignore) {}
        // 初次加载数据
        refreshDownloadsData();
    }

    private void rebuildDownloadsConcat() {
        java.util.List<RecyclerView.Adapter<?>> parts = new java.util.ArrayList<>();
        if (headerActive == null) headerActive = new SectionHeaderAdapter("正在下载");
        if (headerCompleted == null) headerCompleted = new SectionHeaderAdapter("已下载");
        parts.add(headerActive);
        if (downloadsHeaderActiveExpanded) parts.add(downloadTaskAdapter);
        parts.add(headerCompleted);
        if (downloadsHeaderCompletedExpanded) parts.add(downloadedSongAdapter);
        // 使用稳定ID模式，避免 ConcatAdapter 忽略子适配器的稳定ID并产生警告
        androidx.recyclerview.widget.ConcatAdapter.Config cfg = new androidx.recyclerview.widget.ConcatAdapter.Config.Builder()
                .setStableIdMode(androidx.recyclerview.widget.ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                .build();
        downloadsConcatAdapter = new androidx.recyclerview.widget.ConcatAdapter(cfg, parts);
        recyclerView.setAdapter(downloadsConcatAdapter);
        try {
            String pA = downloadsHeaderActiveExpanded ? "▼ " : "▶ ";
            String pC = downloadsHeaderCompletedExpanded ? "▼ " : "▶ ";
            int activeCount = downloadTaskAdapter != null ? downloadTaskAdapter.getItemCount() : 0;
            int completedCount = downloadedSongAdapter != null ? downloadedSongAdapter.getItemCount() : 0;
            if (headerActive != null) headerActive.setTitle(pA + "正在下载 (" + activeCount + ")");
            if (headerCompleted != null) headerCompleted.setTitle(pC + "已下载 (" + completedCount + ")");
        } catch (Exception ignore) {}
    }

    private final Object downloadsRefreshLock = new Object();
    private volatile long lastDownloadsRefreshMs = 0L;
    private static final long DOWNLOADS_REFRESH_MIN_INTERVAL_MS = 150L; // 节流：最少 150ms 刷新一次
    private final java.util.concurrent.ExecutorService downloadsRefreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void refreshDownloadsData() { refreshDownloadsData(false); }

    private void refreshDownloadsData(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastDownloadsRefreshMs < DOWNLOADS_REFRESH_MIN_INTERVAL_MS)) return;
        lastDownloadsRefreshMs = now;
        downloadsRefreshExecutor.submit(() -> {
            try {
                java.util.List<com.watch.limusic.database.DownloadEntity> act = com.watch.limusic.database.DownloadRepository.getInstance(this).getActiveDownloads();
                java.util.List<DownloadInfo> activeInfos = new java.util.ArrayList<>();
                if (act != null) {
                    for (com.watch.limusic.database.DownloadEntity e : act) {
                        DownloadInfo di = com.watch.limusic.database.DownloadRepository.toDownloadInfo(e);
                        DownloadInfo mem = DownloadManager.getInstance(this).getDownloadInfo(e.getSongId());
                        if (mem != null) {
                            if (mem.getTotalBytes() > 0) di.setTotalBytes(mem.getTotalBytes());
                            if (mem.getDownloadedBytes() > 0) di.setDownloadedBytes(mem.getDownloadedBytes());
                            di.setStatus(mem.getStatus());
                        }
                        activeInfos.add(di);
                    }
                }
                java.util.List<com.watch.limusic.database.DownloadEntity> comp = com.watch.limusic.database.DownloadRepository.getInstance(this).getCompletedDownloads();
                java.util.List<Song> completedSongs = new java.util.ArrayList<>();
                if (comp != null) {
                    for (com.watch.limusic.database.DownloadEntity e : comp) {
                        com.watch.limusic.database.SongEntity se = com.watch.limusic.database.MusicDatabase.getInstance(this).songDao().getSongById(e.getSongId());
                        if (se != null) {
                            Song s = new Song(se.getId(), se.getTitle(), se.getArtist(), se.getAlbum(), se.getCoverArt(), se.getStreamUrl(), se.getDuration());
                            s.setAlbumId(se.getAlbumId());
                            completedSongs.add(s);
                        }
                    }
                }
                runOnUiThread(() -> {
                    String pA = downloadsHeaderActiveExpanded ? "▼ " : "▶ ";
                    String pC = downloadsHeaderCompletedExpanded ? "▼ " : "▶ ";
                    if (headerActive != null) headerActive.setTitle(pA + "正在下载 (" + (activeInfos != null ? activeInfos.size() : 0) + ")");
                    if (headerCompleted != null) headerCompleted.setTitle(pC + "已下载 (" + (completedSongs != null ? completedSongs.size() : 0) + ")");
                    downloadTaskAdapter.submitList(activeInfos);
                    downloadedSongAdapter.processAndSubmitListKeepOrder(completedSongs);
                });
            } catch (Exception ignore) {}
        });
    }

    // 下载管理页专用：合并刷新（仅当当前视图为 downloads 时触发刷新）
    private final BroadcastReceiver downloadsUiReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!"downloads".equals(currentView)) return;
            String action = intent != null ? intent.getAction() : null;
            if (action == null) return;
            if (DownloadManager.ACTION_DOWNLOAD_PROGRESS.equals(action)) {
                refreshDownloadsData(false);
            } else if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)
                    || DownloadManager.ACTION_DOWNLOAD_CANCELED.equals(action)) {
                // 完成/取消：强制刷新，绕过节流，修复最后一首驻留问题
                refreshDownloadsData(true);
            } else if (DownloadManager.ACTION_DOWNLOAD_FAILED.equals(action)) {
                refreshDownloadsData(false);
            }
        }
    };

    private void pauseAllDownloads() {
        try {
            // 启动全局暂停，禁止等待队列被自动拉起
            com.watch.limusic.download.DownloadManager.getInstance(this).pauseAll();
            // 本地立即切换所有任务的UI为暂停
            java.util.List<com.watch.limusic.model.DownloadInfo> list = downloadTaskAdapter != null ? downloadTaskAdapter.getSnapshot() : java.util.Collections.emptyList();
            for (com.watch.limusic.model.DownloadInfo di : list) {
                if (di != null) {
                    try {
                        com.watch.limusic.model.DownloadInfo clone = new com.watch.limusic.model.DownloadInfo(di);
                        clone.setStatus(com.watch.limusic.model.DownloadStatus.DOWNLOAD_PAUSED);
                        downloadTaskAdapter.upsert(clone);
                    } catch (Exception ignore) {}
                }
            }
            refreshDownloadsData();
            // 额外两次轻量延迟刷新，兜底异步写入延迟
            try { recyclerView.postDelayed(this::refreshDownloadsData, 180); } catch (Exception ignore) {}
            try { recyclerView.postDelayed(this::refreshDownloadsData, 400); } catch (Exception ignore) {}
        } catch (Exception ignore) {}
    }

    private void resumeAllDownloads() {
        new Thread(() -> {
            try {
                // 解除全局暂停，让等待任务重新进入队列并由调度器按并发上限启动
                com.watch.limusic.download.DownloadManager.getInstance(this).resumeAll();
                runOnUiThread(() -> {
                    // UI 侧立即把活动列表内状态为 PAUSED 的项切换成 WAITING 提示
                    try {
                        java.util.List<com.watch.limusic.model.DownloadInfo> list = downloadTaskAdapter != null ? downloadTaskAdapter.getSnapshot() : java.util.Collections.emptyList();
                        for (com.watch.limusic.model.DownloadInfo di : list) {
                            if (di != null && di.getStatus() == com.watch.limusic.model.DownloadStatus.DOWNLOAD_PAUSED) {
                                com.watch.limusic.model.DownloadInfo clone = new com.watch.limusic.model.DownloadInfo(di);
                                clone.setStatus(com.watch.limusic.model.DownloadStatus.WAITING);
                                downloadTaskAdapter.upsert(clone);
                            }
                        }
                    } catch (Exception ignore) {}
                    refreshDownloadsData();
                    try { recyclerView.postDelayed(this::refreshDownloadsData, 180); } catch (Exception ignore) {}
                    try { recyclerView.postDelayed(this::refreshDownloadsData, 400); } catch (Exception ignore) {}
                });
            } catch (Exception ignore) {}
        }).start();
    }

    private void confirmCancelAllDownloads() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认取消所有下载任务？")
            .setMessage("已完成文件不受影响")
            .setPositiveButton("取消全部", (d,w) -> {
                try { DownloadManager.getInstance(this).cancelAll(); } catch (Exception ignore) {}
                refreshDownloadsData();
            })
            .setNegativeButton("返回", null)
            .show();
    }
}