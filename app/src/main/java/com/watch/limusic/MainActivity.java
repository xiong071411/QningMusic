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

import com.bumptech.glide.Glide;
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
import android.view.View;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
    private static final int PAGE_SIZE = 100;  // 每页加载的专辑数量
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
    
    // 检测缓存状态的广播接收器
    private BroadcastReceiver cacheStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.watch.limusic.CACHE_STATUS_CHANGED")) {
                String songId = intent.getStringExtra("songId");
                boolean isCached = intent.getBooleanExtra("isCached", false);
                
                // 更新适配器中的缓存状态
                if (songAdapter != null && songId != null) {
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
                case DownloadManager.ACTION_DOWNLOAD_PROGRESS:
                    // 仅刷新该条目以更新进度UI（若需要）
                    int progress = intent.getIntExtra(DownloadManager.EXTRA_PROGRESS, 0);
                    songAdapter.updateSongDownloadProgress(songId, progress);
                    break;
                case DownloadManager.ACTION_DOWNLOAD_COMPLETE:
                    // 刷新该条目：按钮应切换为“已下载”图标
                    songAdapter.updateSongDownloadStatus(songId);
                    // 同时广播缓存状态已变化（供其他组件使用）
                    Intent cacheIntent = new Intent("com.watch.limusic.CACHE_STATUS_CHANGED");
                    cacheIntent.putExtra("songId", songId);
                    cacheIntent.putExtra("isCached", true);
                    sendBroadcast(cacheIntent);
                    break;
                case DownloadManager.ACTION_DOWNLOAD_FAILED:
                    songAdapter.updateSongDownloadStatus(songId);
                    break;
                case DownloadManager.ACTION_DOWNLOAD_CANCELED:
                    songAdapter.updateSongDownloadStatus(songId);
                    break;
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
                    String coverArtUrl = null;
                    boolean offline = !NetworkUtils.isNetworkAvailable(context);
                    if (offline) {
                        String localCover = new LocalFileDetector(context).getDownloadedAlbumCoverPath(albumId);
                        if (localCover != null) {
                            coverArtUrl = "file://" + localCover;
                        }
                    }
                    if (coverArtUrl == null) {
                        coverArtUrl = NavidromeApi.getInstance(context).getCoverArtUrl(albumId);
                    }
                    Glide.with(context)
                        .load(coverArtUrl)
                        .override(150, 150)
                        .placeholder(R.drawable.default_album_art)
                        .error(R.drawable.default_album_art)
                        .into(albumArt);
                }
            }
        }
    };

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

        // 初始化下载管理器
        downloadManager = DownloadManager.getInstance(this);
        localFileDetector = new LocalFileDetector(this);

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
        
        // 设置标题滚动效果
        setupTitleScrolling();
    }

    private void setupDrawer() {
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        
        // 禁用侧滑手势，但允许通过按钮打开
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
        
        // 确保左上角按钮可以点击
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
            
            // 更新专辑封面
            if (playerService.getCurrentSong() != null) {
                Song currentSong = playerService.getCurrentSong();
                
                // 更新专辑封面
                String coverArtUrl = null;
                
                Log.d("MainActivity", "更新封面 - 歌曲: " + currentSong.getTitle());
                Log.d("MainActivity", "更新封面 - 专辑ID: " + (currentSong.getAlbumId() != null ? currentSong.getAlbumId() : "null"));
                Log.d("MainActivity", "更新封面 - 封面URL: " + (currentSong.getCoverArtUrl() != null ? currentSong.getCoverArtUrl() : "null"));
                
                // 优先使用专辑ID获取封面
                if (currentSong.getAlbumId() != null && !currentSong.getAlbumId().isEmpty()) {
                    coverArtUrl = NavidromeApi.getInstance(this).getCoverArtUrl(currentSong.getAlbumId());
                    Log.d("MainActivity", "使用专辑ID获取封面: " + coverArtUrl);
                } 
                // 如果没有专辑ID但有封面URL，则使用封面URL
                else if (currentSong.getCoverArtUrl() != null && !currentSong.getCoverArtUrl().isEmpty()) {
                    coverArtUrl = currentSong.getCoverArtUrl();
                    Log.d("MainActivity", "使用封面URL: " + coverArtUrl);
                }
                
                // 加载封面
                if (coverArtUrl != null) {
                    Log.d("MainActivity", "加载封面: " + coverArtUrl);
                    Glide.with(this)
                        .load(coverArtUrl)
                        .override(150, 150)
                        .placeholder(R.drawable.default_album_art)
                        .error(R.drawable.default_album_art)
                        .into(albumArt);
                } else {
                    Log.d("MainActivity", "没有封面URL，使用默认封面");
                    albumArt.setImageResource(R.drawable.default_album_art);
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
                List<Song> currentList = songAdapter.getSongList();
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
                    String coverArtUrl = null;
                    if (!isNetworkAvailable) {
                        String localCover = new LocalFileDetector(this).getDownloadedAlbumCoverPath(song.getAlbumId());
                        if (localCover != null) {
                            coverArtUrl = "file://" + localCover;
                        }
                    }
                    if (coverArtUrl == null) {
                        coverArtUrl = NavidromeApi.getInstance(this).getCoverArtUrl(song.getAlbumId());
                    }
                    Glide.with(this)
                        .load(coverArtUrl)
                        .override(150, 150)
                        .placeholder(R.drawable.default_album_art)
                        .error(R.drawable.default_album_art)
                        .into(albumArt);
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
        getMenuInflater().inflate(R.menu.menu_songs, menu);
        
        // 只有在查看所有歌曲时才显示字母索引按钮
        boolean showLetterIndex = currentView.equals("songs");
        menu.findItem(R.id.action_letter_index).setVisible(showLetterIndex);
        
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.action_letter_index) {
            showLetterIndexDialog();
            return true;
        } else if (id == android.R.id.home) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
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
            // 通知系统更新菜单
            invalidateOptionsMenu();
        } else if (id == R.id.nav_playlists) {
            currentView = "playlists";
            // TODO: 实现播放列表功能
            Toast.makeText(this, "播放列表功能开发中", Toast.LENGTH_SHORT).show();
            // 通知系统更新菜单
            invalidateOptionsMenu();
        } else if (id == R.id.nav_artists) {
            currentView = "artists";
            // TODO: 实现艺术家功能
            Toast.makeText(this, "艺术家功能开发中", Toast.LENGTH_SHORT).show();
            // 通知系统更新菜单
            invalidateOptionsMenu();
        } else if (id == R.id.nav_albums) {
            currentView = "albums";
            recyclerView.setAdapter(albumAdapter);
            loadAlbums();
            // 通知系统更新菜单
            invalidateOptionsMenu();
            // 确保播放进度条可见
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
        
        // 重置状态和列表
        hasMoreData = true;
        currentOffset = 0;
        
        // 显示加载中
        progressBar.setVisibility(View.VISIBLE);
        isLoading = true;
        
        // 使用Repository加载专辑
        new Thread(() -> {
            try {
                final List<Album> albums = musicRepository.getAlbums("newest", PAGE_SIZE, currentOffset);
                
                // 确保专辑数据被保存到数据库
                if (isNetworkAvailable && albums != null && !albums.isEmpty()) {
                    // 对于每个专辑，预先加载并保存其歌曲到数据库
                    for (Album album : albums) {
                        try {
                            List<Song> songs = NavidromeApi.getInstance(this).getAlbumSongs(album.getId());
                            if (songs != null && !songs.isEmpty()) {
                                musicRepository.saveSongsToDatabase(songs);
                                Log.d(TAG, "预加载并保存专辑 " + album.getName() + " 的歌曲: " + songs.size() + " 首");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "预加载专辑 " + album.getName() + " 的歌曲失败", e);
                        }
                    }
                }
                
                runOnUiThread(() -> {
                    if (albums != null && !albums.isEmpty()) {
                        // 更新UI
                        currentView = "albums";
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("专辑");
        }
        
                        // 设置适配器
                        if (albumAdapter == null) {
                            albumAdapter = new AlbumAdapter(MainActivity.this);
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
                        recyclerView.setAdapter(albumAdapter);
                        }
                        
                        // 更新数据
                        albumAdapter.setAlbums(albums);
                        
                        // 在线时预取专辑封面，供离线显示
                        if (isNetworkAvailable) {
                            new Thread(() -> prefetchAlbumCovers(albums)).start();
                        }
                        
                        // 更新加载状态
                        currentOffset += albums.size();
                        isLoading = false;
                        progressBar.setVisibility(View.GONE);
                        
                        // 检查是否还有更多数据
                        hasMoreData = albums.size() >= PAGE_SIZE;
                    } else {
                        // 没有数据或发生错误
                        Toast.makeText(MainActivity.this, "没有专辑或加载失败", Toast.LENGTH_SHORT).show();
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
                        .getAlbumList("newest", PAGE_SIZE - 20, currentOffset);
                
                runOnUiThread(() -> {
                    if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                        albumAdapter.addAlbums(response.getData());
                        currentOffset += response.getData().size();
                        hasMoreData = response.getData().size() >= (PAGE_SIZE - 20);
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
                        // 更新UI
                        currentView = "album_songs";  // 使用特定标识，区分普通歌曲列表
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setTitle(songs.get(0).getAlbum());
                        }
                        
                        // 设置适配器
                        if (songAdapter == null) {
                            songAdapter = new SongAdapter(MainActivity.this, this);
                        }
                        
                        // 显式设置RecyclerView的适配器为歌曲适配器
                        recyclerView.setAdapter(songAdapter);
                        
                        // 专辑内歌曲列表不显示封面
                        songAdapter.setShowCoverArt(false);

                        // 更新数据
                        songAdapter.processAndSubmitList(songs);
                        
                        // 通知系统更新菜单
                        invalidateOptionsMenu();
                        
                        // 记录日志
                        Log.d(TAG, "专辑歌曲加载成功: " + songs.size() + " 首歌曲, 专辑ID: " + albumId);
                    } else {
                        Toast.makeText(MainActivity.this, "没有找到歌曲或加载失败", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "专辑歌曲加载失败: 没有找到歌曲, 专辑ID: " + albumId);
                        
                        // 如果有网络，尝试直接从API加载
                        if (isNetworkAvailable) {
                            Log.d(TAG, "尝试直接从API加载专辑歌曲: " + albumId);
                            try {
                                List<Song> apiSongs = NavidromeApi.getInstance(MainActivity.this).getAlbumSongs(albumId);
                                if (apiSongs != null && !apiSongs.isEmpty()) {
                                    // 保存到数据库
                                    musicRepository.saveSongsToDatabase(apiSongs);
                                    
                                    // 更新UI
                                    currentView = "album_songs";
                                    if (getSupportActionBar() != null) {
                                        getSupportActionBar().setTitle(apiSongs.get(0).getAlbum());
                                    }
                                    
                                    // 设置适配器
                                    if (songAdapter == null) {
                                        songAdapter = new SongAdapter(MainActivity.this, MainActivity.this);
                                    }
                                    
                                    // 显式设置RecyclerView的适配器为歌曲适配器
                                    recyclerView.setAdapter(songAdapter);
                                    
                                    // 专辑内歌曲列表不显示封面
                                    songAdapter.setShowCoverArt(false);

                                    // 更新数据
                                    songAdapter.processAndSubmitList(apiSongs);
                                    
                                    // 通知系统更新菜单
                                    invalidateOptionsMenu();
                                    
                                    Log.d(TAG, "直接从API加载专辑歌曲成功: " + apiSongs.size() + " 首");
                                    Toast.makeText(MainActivity.this, "加载成功: " + apiSongs.size() + " 首歌曲", Toast.LENGTH_SHORT).show();
                                } else {
                                    Log.d(TAG, "直接从API加载专辑歌曲失败: 没有找到歌曲");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "直接从API加载专辑歌曲失败", e);
                            }
                        }
                    }
                    
                    // 隐藏加载中
                    progressBar.setVisibility(View.GONE);
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            } catch (Exception e) {
                Log.e(TAG, "加载专辑歌曲失败", e);
                
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "加载歌曲失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            .setMessage("LiMusic\n\n该软件由作者熬了四个大夜（截止至这一个版本更新）呕心沥血匠心制作，由于艺术家分类还没做所以版本号就不更到2.0了。泠鸢yousa    B站uid：282994，快去关注！")
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
                List<Song> songs;
                
                if (isNetworkAvailable) {
                    // 有网络时从API获取
                NavidromeApi api = NavidromeApi.getInstance(this);
                    songs = api.getAllSongs();
                
                    // 确保歌曲数据被保存到数据库
                    if (songs != null && !songs.isEmpty()) {
                        musicRepository.saveSongsToDatabase(songs);
                        Log.d(TAG, "成功保存 " + songs.size() + " 首歌曲到数据库");
                    }
                } else {
                    // 无网络时从数据库获取
                    songs = musicRepository.getAllSongsFromDatabase();
                    Log.d(TAG, "从数据库加载歌曲: " + (songs != null ? songs.size() : 0) + " 首");
                }
                
                final List<Song> finalSongs = songs;
                runOnUiThread(() -> {
                    if (finalSongs != null && !finalSongs.isEmpty()) {
                        recyclerView.setAdapter(songAdapter);
                        songAdapter.setShowCoverArt(false);  // 所有歌曲列表不显示封面
                        songAdapter.processAndSubmitList(finalSongs);
                        
                        // 设置Toolbar标题
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setTitle(R.string.all_songs);
                        }
                        
                        // 更新菜单状态
                        invalidateOptionsMenu();
                        
                        Log.d(TAG, "显示所有歌曲: " + finalSongs.size() + " 首");
                    } else {
                        Toast.makeText(this, R.string.error_no_songs, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "没有找到歌曲");
                    }
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            } catch (Exception e) {
                Log.e(TAG, "加载歌曲失败", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
                    
                    // 确保播放进度条可见
                    ensureProgressBarVisible();
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
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
        if (songAdapter == null || songAdapter.getItemCount() == 0) {
            Toast.makeText(this, R.string.error_no_songs, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 获取所有可用的字母索引
        List<String> availableLetters = songAdapter.getAvailableIndexLetters();
        if (availableLetters.isEmpty()) {
            return;
        }
        
        // 创建并显示字母索引对话框
        LetterIndexDialog dialog = new LetterIndexDialog(this, availableLetters);
        dialog.setOnLetterSelectedListener(letter -> {
            // 获取选定字母的位置
            int position = songAdapter.getPositionForLetter(letter);
            // 滚动到指定位置
            if (position >= 0 && position < songAdapter.getItemCount()) {
                recyclerView.scrollToPosition(position);
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
                        // 更新UI显示
                        if (songAdapter != null) {
                            songAdapter.updateSongDownloadStatus(song.getId());
                        }
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
}