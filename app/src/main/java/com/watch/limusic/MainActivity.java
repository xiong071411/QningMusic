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

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.util.Log;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.core.content.ContextCompat;
import com.watch.limusic.model.SongWithIndex;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        SongAdapter.OnSongClickListener,
        SongAdapter.OnDownloadClickListener {
    
    private static final String TAG = "MainActivity";
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private RecyclerView recyclerView;
    private LinearLayoutManager layoutManager;
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
    
    // 新增：抽屉切换器持有引用，便于动态切换导航图标
    private ActionBarDrawerToggle drawerToggle;
    
    // 新增：双击返回退出的时间控制
    private long lastBackPressedTime = 0L;
    private static final long BACK_EXIT_INTERVAL = 2000L; // 毫秒
    
    // 最近一次字母跳转的目标字母（用于数据库更新后纠正定位）
    private String pendingJumpLetter = null;
    
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
                RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                    int total = musicRepository.getSongCount();
                    java.util.Map<String, Integer> letterOffsets = musicRepository.getLetterOffsetMap();
                    com.watch.limusic.adapter.AllSongsRangeAdapter range = (com.watch.limusic.adapter.AllSongsRangeAdapter) adapter;
                    range.clearCache();
                    range.setTotalCount(total);
                    range.setLetterOffsetMap(letterOffsets);
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

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PlayerService.LocalBinder binder = (PlayerService.LocalBinder) service;
            playerService = binder.getService();
            bound = true;
            // 立即告知服务UI可见
            playerService.setUiVisible(true); 
            updatePlaybackState();
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
                boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
                int mode = intent.getIntExtra("playbackMode", PlayerService.PLAYBACK_MODE_REPEAT_ALL);
                String title = intent.getStringExtra("title");
                String artist = intent.getStringExtra("artist");
                String albumId = intent.getStringExtra("albumId");
                
                // 更新播放/暂停按钮
                playPauseButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                
                // 更新歌曲信息（只有当标题真正变化时才更新，避免重置滚动）
                if (title != null && !title.equals(songTitle.getText().toString())) {
                    songTitle.setText(title);
                    // 重新设置滚动属性
                    songTitle.setSelected(true);
                }
                if (artist != null) songArtist.setText(artist);
                
                // 优化进度条更新：只有在不拖动且满足更新间隔时才更新进度条
                long currentTime = System.currentTimeMillis();
                if (duration > 0) {
                    // 始终更新最大值，确保进度条比例正确
                    seekBar.setMax((int) duration);
                    progressBar.setMax((int) duration);
                    
                    if (!userIsSeeking && (currentTime - lastProgressUpdateTime > PROGRESS_UPDATE_INTERVAL || position == 0)) {
                        // 只在不拖动时更新进度
                        seekBar.setProgress((int) position);
                    progressBar.setProgress((int) position);
                        
                        // 确保进度条始终可见
                        progressBar.setVisibility(View.VISIBLE);
                        
                        progressBar.invalidate();
                        lastProgressUpdateTime = currentTime;
                    }
                }
                
                // 更新播放模式按钮
                updatePlaybackModeButton();

                // 更新专辑封面（仅当albumId变化时）
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
                loadPlaylists();
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
                    new Thread(() -> {
                        List<com.watch.limusic.model.Song> songs2 = playlistRepository.getSongsInPlaylist(pid, 500, 0);
                        runOnUiThread(() -> songAdapter.processAndSubmitListKeepOrder(songs2));
                    }).start();
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
        
        // 检查配置，加载数据
        checkConfiguration();
        
        // 注册缓存状态变化广播接收器
        IntentFilter cacheFilter = new IntentFilter("com.watch.limusic.CACHE_STATUS_CHANGED");
        registerReceiver(cacheStatusReceiver, cacheFilter);
        
        // 注册网络状态变化广播接收器
        IntentFilter networkFilter = new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE");
        registerReceiver(networkStatusReceiver, networkFilter);
        
        // 注册下载相关的本地广播
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_PROGRESS));
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_FAILED));
        lbm.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_CANCELED));
        
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
                    // 获取数据库中的歌曲数量
                    int songCount = musicRepository.getSongCount();
                    Log.d(TAG, "启动时预加载 - 数据库中的歌曲数量: " + songCount);
                    
                    // 如果数据库中的歌曲数量太少，预加载一些歌曲
                    if (songCount < 50) {
                        Log.d(TAG, "启动时预加载 - 数据库中歌曲数量不足，开始预加载");
                        
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
        
        // 绑定服务
        bindService();
        
        // 注册播放状态接收器
        IntentFilter filter = new IntentFilter("com.watch.limusic.PLAYBACK_STATE_CHANGED");
        registerReceiver(playbackStateReceiver, filter);
        
        // 监听数据库更新
        try { registerReceiver(dbSongsUpdatedReceiver, new IntentFilter("com.watch.limusic.DB_SONGS_UPDATED")); } catch (Exception ignore) {}
        
        // 设置UI可见性标志
        if (bound && playerService != null) {
            playerService.setUiVisible(true);
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
        
        // 设置UI不可见标志
        if (bound && playerService != null) {
            playerService.setUiVisible(false);
        }
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
        // 初始化布局为线性布局，用于专辑视图
        layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        
        // 优化RecyclerView性能
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        
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
            }
        });
        
        // 默认显示专辑列表
        recyclerView.setAdapter(albumAdapter);
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
                        updateTimeDisplay(progress, playerService.getDuration());
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
                    updateTimeDisplay(playerService.getCurrentPosition(), playerService.getDuration());
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
                    playerService.seekTo(progress);
                    
                    // 更新进度条，确保始终可见
                    progressBar.setProgress(progress);
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.invalidate();
                }
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

    private void togglePlayback() {
        if (bound) {
            if (playerService.isPlaying()) {
                playerService.pause();
                playPauseButton.setImageResource(R.drawable.ic_play);
            } else {
                playerService.play();
                playPauseButton.setImageResource(R.drawable.ic_pause);
            }
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
                        .signature(new ObjectKey(key != null ? key : ""))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
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
                if (adapter instanceof com.watch.limusic.adapter.AllSongsRangeAdapter) {
                    // 按需加载模式：为了点击即播，动态构造"当前位置±N"窗口作为播放列表
                    currentList = new java.util.ArrayList<>();
                    int start = Math.max(0, position - 50);
                    int end = Math.min(adapter.getItemCount() - 1, position + 200); // 向后多取，保证顺播
                    // 直接从数据库按排序范围取数据
                    List<Song> range = musicRepository.getSongsRange(end - start + 1, start);
                    currentList.addAll(range);
                    // 重新定位点击项在当前窗口中的索引
                    position = Math.min(Math.max(0, position - start), currentList.size() - 1);
                } else if (songAdapter != null) {
                    currentList = songAdapter.getSongList();
                }
                if (currentList == null || currentList.isEmpty()) {
                    Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 在离线模式下，检查歌曲是否已离线可用（已下载优先，其次缓存）
                if (!isNetworkAvailable) {
                    boolean isDownloaded = localFileDetector.isSongDownloaded(song);
                    boolean isCached = CacheManager.getInstance(this).isCached(NavidromeApi.getInstance(this).getStreamUrl(song.getId()));
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
                    // 如果没有专辑ID，尝试使用封面URL作为专辑ID
                    if (song.getCoverArtUrl() != null && !song.getCoverArtUrl().isEmpty()) {
                        song.setAlbumId(song.getCoverArtUrl());
                    }
                }
                
                // 预先加载封面
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
                
                // 设置整个播放列表，并从选中的歌曲开始播放
                playerService.setPlaylist(currentList, position);
                
            } catch (Exception e) {
                Log.e("MainActivity", "准备播放时出错", e);
                Toast.makeText(this, "播放出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "播放服务未就绪", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.clear();
        if ("songs".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) {
                menu.findItem(R.id.action_letter_index).setVisible(!selectionMode);
            }
            if (menu.findItem(R.id.action_add_to_playlist) != null) {
                menu.findItem(R.id.action_add_to_playlist).setVisible(selectionMode);
            }
        } else if ("album_songs".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) {
                menu.findItem(R.id.action_letter_index).setVisible(false);
            }
            if (menu.findItem(R.id.action_add_to_playlist) != null) {
                menu.findItem(R.id.action_add_to_playlist).setVisible(selectionMode);
            }
        } else if ("playlists".equals(currentView)) {
            getMenuInflater().inflate(R.menu.menu_playlist_list, menu);
        } else if ("playlist_detail".equals(currentView)) {
            // 歌单详情：选择模式下显示删除（白色图标）
            getMenuInflater().inflate(R.menu.menu_songs, menu);
            if (menu.findItem(R.id.action_letter_index) != null) menu.findItem(R.id.action_letter_index).setVisible(false);
            if (menu.findItem(R.id.action_add_to_playlist) != null) {
                MenuItem del = menu.findItem(R.id.action_add_to_playlist);
                del.setIcon(R.drawable.ic_delete);
                del.setTitle("删除");
                del.setVisible(selectionMode);
                try {
                    android.graphics.PorterDuffColorFilter white = new android.graphics.PorterDuffColorFilter(getResources().getColor(android.R.color.white), android.graphics.PorterDuff.Mode.SRC_IN);
                    del.getIcon().setColorFilter(white);
                } catch (Exception ignore) {}
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_to_playlist && "playlist_detail".equals(currentView)) {
            if (!selectionMode || selectedSongIds.isEmpty()) return true;
            // 二次确认，列出要删除的歌曲
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
                    // 计算 ordinals 并删除
                    java.util.List<Integer> ords = com.watch.limusic.database.MusicDatabase.getInstance(this).playlistSongDao().getOrdinalsForSongIds(currentPlaylistLocalId, ids);
                    // 构建服务端索引映射（当前本地 ordinal 与服务端顺序一致）
                    java.util.HashMap<String,Integer> serverIndexBySongId = new java.util.HashMap<>();
                    for (int i = 0; i < ids.size(); i++) {
                        String sid = ids.get(i);
                        int idx = com.watch.limusic.database.MusicDatabase.getInstance(this).playlistSongDao().getOrdinalsForSongIds(currentPlaylistLocalId, java.util.Collections.singletonList(sid)).get(0);
                        serverIndexBySongId.put(sid, idx);
                    }
                    playlistRepository.removeByOrdinals(currentPlaylistLocalId, ords, serverIndexBySongId);
                    // 刷新UI
                    java.util.List<com.watch.limusic.model.Song> s2 = playlistRepository.getSongsInPlaylist(currentPlaylistLocalId, 500, 0);
                    if (songAdapter != null) songAdapter.processAndSubmitListKeepOrder(s2);
                    exitSelectionMode();
                })
                .setNegativeButton("取消", null)
                .show();
            return true;
        }
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
                                    com.watch.limusic.database.PlaylistEntity p = playlistRepository.createPlaylist(name, false);
                                    performAddToPlaylist(p.getLocalId());
                                } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return true;
            }
            // 排序：最近变更优先
            java.util.Collections.sort(lists, (a,b) -> Long.compare(b.getChangedAt(), a.getChangedAt()));
            CharSequence[] names = new CharSequence[lists.size()+1];
            for (int i=0;i<lists.size();i++) names[i] = lists.get(i).getName() + " ("+lists.get(i).getSongCount()+")";
            names[lists.size()] = "新建歌单";
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择歌单")
                .setItems(names, (d, which) -> {
                    if (which == lists.size()) {
                        final android.widget.EditText input = new android.widget.EditText(this);
                        input.setHint("输入歌单名称");
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("新建歌单")
                            .setView(input)
                            .setPositiveButton("创建", (d2,w2) -> {
                                String name = input.getText() != null ? input.getText().toString() : "";
                                try {
                                    com.watch.limusic.database.PlaylistEntity p = playlistRepository.createPlaylist(name, false);
                                    performAddToPlaylist(p.getLocalId());
                                } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    } else {
                        performAddToPlaylist(lists.get(which).getLocalId());
                    }
                })
                .show();
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
            } else if ("playlist_detail".equals(currentView)) {
                navigateBackToPlaylists();
            } else {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            }
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.nav_all_songs) {
            currentView = "songs";
            loadAllSongs();
            invalidateOptionsMenu();
        } else if (id == R.id.nav_playlists) {
            Log.d(TAG, "Navigation: playlists clicked");
            currentView = "playlists";
            loadPlaylists();
            invalidateOptionsMenu();
        } else if (id == R.id.nav_artists) {
            currentView = "artists";
            Toast.makeText(this, "艺术家功能开发中", Toast.LENGTH_SHORT).show();
            invalidateOptionsMenu();
        } else if (id == R.id.nav_albums) {
            currentView = "albums";
            recyclerView.setAdapter(albumAdapter);
            loadAlbums();
            invalidateOptionsMenu();
            ensureProgressBarVisible();
        } else if (id == R.id.nav_downloads) {
            startActivity(new Intent(this, DownloadSettingsActivity.class));
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
        
        // 显示加载中
        progressBar.setVisibility(View.VISIBLE);
        isLoading = true;

        // 保证骨架屏已显示
        showSkeleton();

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
                                loadAlbumSongs(album.getId());
                            });
                            recyclerView.setAdapter(albumAdapter);
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
                                loadAlbumSongs(album.getId());
                            });
                            recyclerView.setAdapter(albumAdapter);
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
                        
                        // 专辑内歌曲列表不显示封面
                        songAdapter.setShowCoverArt(false);
                        
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
        new AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage("版本：v2.3Beta2\n\n软件作者 九秒冬眠 b站uid：515083950 ")
            .setPositiveButton("确定", null)
            .show();
    }

    private void checkConfiguration() {
        SharedPreferences prefs = getSharedPreferences("navidrome_settings", MODE_PRIVATE);
        String serverUrl = prefs.getString("server_url", "");
        if (serverUrl.isEmpty()) {
            // 如果未配置，直接打开设置界面
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            // 已配置，加载音乐列表
            loadAlbums();
        }
    }

    private void loadAllSongs() {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // 在线时尝试刷新一次数据库（不阻塞 UI 首屏）
                if (isNetworkAvailable) {
                    try {
                        List<Song> online = NavidromeApi.getInstance(this).getAllSongs();
                        if (online != null && !online.isEmpty()) {
                            musicRepository.saveSongsToDatabase(online);
                        }
                    } catch (Exception ignore) {
                        // 忽略网络异常，走本地数据
                    }
                }
                // 获取总数与字母偏移映射
                int total = musicRepository.getSongCount();
                java.util.Map<String, Integer> letterOffsets = musicRepository.getLetterOffsetMap();

                runOnUiThread(() -> {
                    // 初始化按需加载适配器
                    rangeAdapter = new com.watch.limusic.adapter.AllSongsRangeAdapter(this, musicRepository, this);
                    rangeAdapter.setOnDownloadClickListener(this);
                    rangeAdapter.setShowCoverArt(false);
                    rangeAdapter.setShowDownloadStatus(true);
                    rangeAdapter.clearCache();
                    rangeAdapter.setTotalCount(total);
                    rangeAdapter.setLetterOffsetMap(letterOffsets);

                    recyclerView.setAdapter(rangeAdapter);
                    // 选择模式长按入口
                    rangeAdapter.setOnItemLongClickListener(pos -> {
                        String id = rangeAdapter.getSongIdAt(pos);
                        if (id == null) return;
                        selectionMode = true;
                        selectedSongIds.clear();
                        selectedSongIds.add(id);
                        rangeAdapter.setSelectionMode(true);
                        // 立即选中长按的项以高亮显示
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

                         if (getSupportActionBar() != null) {
                             getSupportActionBar().setTitle(R.string.all_songs);
                         }
                        currentView = "songs";
                        enterRootNavigationMode();
                        invalidateOptionsMenu();
                        
                    // 首屏预取
                    rangeAdapter.prefetch(0);
                });
            } catch (Exception e) {
                Log.e(TAG, "加载歌曲失败", e);
                runOnUiThread(() -> Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show());
            } finally {
                runOnUiThread(this::ensureProgressBarVisible);
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        
        if ("album_songs".equals(currentView)) {
            // 二级界面：返回到专辑选择界面
            navigateBackToAlbums();
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
    }

    // 确保播放进度条可见的方法
    private void ensureProgressBarVisible() {
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
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
        return "albums".equals(view) || "songs".equals(view) || "artists".equals(view) || "playlists".equals(view);
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
            tv.setSelected(true); // 启用跑马灯
            androidx.appcompat.app.ActionBar.LayoutParams lp = new androidx.appcompat.app.ActionBar.LayoutParams(
                androidx.appcompat.app.ActionBar.LayoutParams.MATCH_PARENT,
                androidx.appcompat.app.ActionBar.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            int startPaddingPx = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 0,
                //以上代码用于设置返回箭头与标题之间的间距，0代表间距
                getResources().getDisplayMetrics());
            custom.setPadding(startPaddingPx, 0, 0, 0);
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
        currentView = "albums";
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("专辑");
        }
        if (albumAdapter != null) {
            recyclerView.setAdapter(albumAdapter);
        }
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
                String[] items = new String[]{"删除", playlist.isPublic() ? "设为私有" : "设为公开", "重命名"};
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
                        }
                    }).show();
            }
        });
        recyclerView.setAdapter(playlistAdapter);

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
                                if (local.isDeleted()) {
                                    continue; // 本地已删除则跳过展示
                                }
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
                            // 歌单列表按"最近变更优先"展示（不按拼音排序）
                            java.util.Collections.sort(uiList, (a,b) -> Long.compare(b.getChangedAt(), a.getChangedAt()));
                            playlistAdapter.submitList(uiList);
                            playlistAdapter.setCoverArtMap(finalCoverMap);
                            progressBar.setVisibility(View.GONE);
                            if (skeletonContainer != null) skeletonContainer.setVisibility(View.GONE);
                            if (emptyContainer != null) emptyContainer.setVisibility(View.GONE);
                            if (errorContainer != null) errorContainer.setVisibility(View.GONE);
                            ensureProgressBarVisible();
                        });
                        // 后台持久化到本地
                        try { playlistRepository.syncPlaylistsHeader(); } catch (Exception ignore) {}
                    }
                } catch (Exception netEx) {
                    // ignore, fallback to DB below
                }

                // 同步一次本地头部与服务端（即使服务端为空也要对账清理）
                try { playlistRepository.syncPlaylistsHeader(); } catch (Exception ignore) {}
                // 二次清理本地孤儿
                try { com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().deleteOrphanLocalPlaylists(); } catch (Exception ignore) {}

                // DB 回退/刷新
                List<com.watch.limusic.database.PlaylistEntity> list = com.watch.limusic.database.MusicDatabase.getInstance(MainActivity.this).playlistDao().getAll();
                final boolean fromNet = shownFromNetwork;
                runOnUiThread(() -> {
                    if (!fromNet) {
                        playlistAdapter.submitList(list);
                    }
                    progressBar.setVisibility(View.GONE);
                    if ((list == null || list.isEmpty()) && !fromNet) {
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
        currentView = "playlist_detail";
        currentPlaylistTitle = name;
        enterAlbumDetailNavigationMode(name);
        // 设置返回键逻辑为返回歌单列表
        toolbar.setNavigationOnClickListener(v -> navigateBackToPlaylists());
        if (songAdapter == null) songAdapter = new SongAdapter(this, this);
        recyclerView.setAdapter(songAdapter);
        songAdapter.setShowCoverArt(false);
        songAdapter.setPlaylistDetail(true);
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
        new Thread(() -> {
            List<Song> songs = playlistRepository.getSongsInPlaylist(playlistLocalId, 500, 0);
            runOnUiThread(() -> songAdapter.processAndSubmitListKeepOrder(songs));
        }).start();
        // 轻量校验并可能刷新
        new Thread(() -> {
            boolean refreshed = playlistRepository.validateAndMaybeRefreshFromServer(playlistLocalId);
            List<Song> songs2 = playlistRepository.getSongsInPlaylist(playlistLocalId, 500, 0);
            runOnUiThread(() -> songAdapter.processAndSubmitListKeepOrder(songs2));
        }).start();
    }

    private void updateSelectionTitle() {
        if (getSupportActionBar() == null) return;
        android.view.View v = getSupportActionBar().getCustomView();
        android.widget.TextView tv = v != null ? (android.widget.TextView) v.findViewById(R.id.toolbar_title) : null;
        if (selectionMode) {
            String txt = "已选择 " + selectedSongIds.size() + " 首";
            if (tv != null) tv.setText(txt);
            getSupportActionBar().setTitle(txt);
            if ("playlist_detail".equals(currentView)) enableDragHandleInPlaylistDetail(true);
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
        if ("songs".equals(currentView)) {
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.all_songs);
        } else if ("album_songs".equals(currentView)) {
            if (getSupportActionBar() != null && currentAlbumTitle != null) getSupportActionBar().setTitle(currentAlbumTitle);
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
                if (skippedTitles != null && !skippedTitles.isEmpty()) {
                    String joined = android.text.TextUtils.join("、", skippedTitles);
                    Toast.makeText(this, "跳过已存在：" + joined, Toast.LENGTH_LONG).show();
                }
                if (serverOk) {
                    Toast.makeText(this, "歌单保存成功", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "本地保存成功，服务器同步失败", Toast.LENGTH_SHORT).show();
                }
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
        if ("albums".equals(currentView) || "songs".equals(currentView) || "artists".equals(currentView) || "playlists".equals(currentView)) {
            enterRootNavigationMode();
        } else if ("album_songs".equals(currentView)) {
            // 专辑详情保留原有返回行为
            enterAlbumDetailNavigationMode(currentAlbumTitle);
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
                        suppressPlaylistChangedUntilMs = System.currentTimeMillis() + 700L;
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
}