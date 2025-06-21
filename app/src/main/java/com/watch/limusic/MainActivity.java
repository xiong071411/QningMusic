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
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.appcompat.widget.Toolbar;
import androidx.annotation.NonNull;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.util.Log;
import android.view.View;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity 
        implements NavigationView.OnNavigationItemSelectedListener,
        SongAdapter.OnSongClickListener {
    
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private RecyclerView recyclerView;
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
                
                // 更新歌曲信息
                if (title != null) songTitle.setText(title);
                if (artist != null) songArtist.setText(artist);
                
                // 只有在用户不在拖动进度条时才更新进度
                if (!userIsSeeking && duration > 0) {
                    seekBar.setMax((int) duration);
                    seekBar.setProgress((int) position);
                    progressBar.setMax((int) duration);
                    progressBar.setProgress((int) position);
                }
                
                // 更新播放模式按钮
                updatePlaybackModeButton();

                // 更新专辑封面（仅当albumId变化时）
                if (albumId != null && !albumId.isEmpty() && !albumId.equals(lastAlbumId)) {
                    lastAlbumId = albumId;
                    String coverArtUrl = NavidromeApi.getInstance(context).getCoverArtUrl(albumId);
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

        // 初始化视图
        initViews();
        
        // 设置导航抽屉
        setupDrawer();
        
        // 设置RecyclerView
        setupRecyclerView();
        
        // 设置播放控制
        setupPlaybackControls();
        
        // 注册广播接收器
        registerReceiver(playbackStateReceiver, new IntentFilter("com.watch.limusic.PLAYBACK_STATE_CHANGED"));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 绑定服务
        bindService();
        // 检查是否已配置
        checkConfiguration();
        if (bound && playerService != null) {
            playerService.setUiVisible(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound && playerService != null) {
            playerService.setUiVisible(false);
        }
        // 解绑服务
        if (bound) {
            unbindService(connection);
            bound = false;
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        recyclerView = findViewById(R.id.recycler_view);
        albumArt = findViewById(R.id.album_art);
        songTitle = findViewById(R.id.song_title);
        songArtist = findViewById(R.id.song_artist);
        timeDisplay = findViewById(R.id.time_display);
        playPauseButton = findViewById(R.id.btn_play_pause);
        repeatModeButton = findViewById(R.id.btn_repeat_mode);
        seekBar = findViewById(R.id.seek_bar);
        progressBar = findViewById(R.id.progress_bar);
        toolbar = findViewById(R.id.toolbar);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
            // 设置更简洁的标题
            getSupportActionBar().setTitle("LiMusic");
            // 隐藏副标题
            getSupportActionBar().setSubtitle(null);
        }
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
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        
        // 添加分隔线
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(
                recyclerView.getContext(), layoutManager.getOrientation());
        dividerItemDecoration.setDrawable(getResources().getDrawable(R.drawable.list_divider));
        recyclerView.addItemDecoration(dividerItemDecoration);
        
        // 初始化适配器
        albumAdapter = new AlbumAdapter(this);
        songAdapter = new SongAdapter(this, this);
        
        // 设置专辑点击事件
        albumAdapter.setOnAlbumClickListener(album -> loadAlbumSongs(album.getId()));
        
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

        // 设置歌曲标题滚动效果
        songTitle.setSelected(true);
        // 防止滚动被打断
        songTitle.setHorizontalFadingEdgeEnabled(true);
        songTitle.setMarqueeRepeatLimit(-1); // 无限循环

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && bound && playerService != null) {
                    playerService.seekTo(progress);
                    // 同步更新ProgressBar
                    progressBar.setProgress(progress);
                    
                    // 更新时间显示
                    if (isSeeking) {
                        updateTimeDisplay(progress, playerService.getDuration());
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userIsSeeking = true;
                isSeeking = true;
                
                // 隐藏艺术家名称
                songArtist.setVisibility(View.GONE);
                
                // 显示时间文本
                timeDisplay.setVisibility(View.VISIBLE);
                
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
                
                if (bound && playerService != null) {
                    int progress = seekBar.getProgress();
                    playerService.seekTo(progress);
                    // 同步更新ProgressBar
                    progressBar.setProgress(progress);
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
                    String coverArtUrl = NavidromeApi.getInstance(this).getCoverArtUrl(song.getAlbumId());
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
        } else if (id == R.id.nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.nav_about) {
            showAboutDialog();
        }

        drawerLayout.closeDrawers();
        return true;
    }

    private void loadAlbums() {
        currentOffset = 0;
        hasMoreData = true;
        isLoading = true;

        SharedPreferences prefs = getSharedPreferences("navidrome_settings", MODE_PRIVATE);
        String serverUrl = prefs.getString("server_url", "");
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, R.string.error_no_server, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                NavidromeApi api = NavidromeApi.getInstance(this);
                SubsonicResponse<List<Album>> response = api.getAlbumList("newest", PAGE_SIZE, 0);
                
                runOnUiThread(() -> {
                    isLoading = false;
                    if (response != null && response.isSuccess() && response.getData() != null) {
                        albumAdapter.submitList(response.getData());
                    } else {
                        String error = response != null ? response.getError() : getString(R.string.error_server);
                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    isLoading = false;
                    Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void loadAlbumSongs(String albumId) {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                NavidromeApi api = NavidromeApi.getInstance(this);
                List<Song> songs = api.getAlbumSongs(albumId);
                
                runOnUiThread(() -> {
                    if (songs != null && !songs.isEmpty()) {
                        currentView = "songs";
                        recyclerView.setAdapter(songAdapter);
                        songAdapter.setShowCoverArt(true);  // 显示封面
                        songAdapter.processAndSubmitList(songs);
                    } else {
                        Toast.makeText(this, R.string.error_no_songs, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> 
                    Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show()
                );
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

    private void loadMoreAlbums() {
        if (isLoading || !hasMoreData) return;
        
        isLoading = true;
        currentOffset += PAGE_SIZE;
        
        new Thread(() -> {
            try {
                NavidromeApi api = NavidromeApi.getInstance(this);
                SubsonicResponse<List<Album>> response = api.getAlbumList("newest", PAGE_SIZE, currentOffset);
                
                runOnUiThread(() -> {
                    isLoading = false;
                    if (response != null && response.isSuccess() && response.getData() != null) {
                        List<Album> newAlbums = response.getData();
                        if (newAlbums.isEmpty()) {
                            hasMoreData = false;
                        } else {
                            // 创建一个包含旧数据和新数据的新列表
                            List<Album> currentList = new ArrayList<>(albumAdapter.getCurrentList());
                            currentList.addAll(newAlbums);
                            albumAdapter.submitList(currentList);
                        }
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    isLoading = false;
                    Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void loadAllSongs() {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                NavidromeApi api = NavidromeApi.getInstance(this);
                List<Song> songs = api.getAllSongs();
                
                runOnUiThread(() -> {
                    if (songs != null && !songs.isEmpty()) {
                        recyclerView.setAdapter(songAdapter);
                        songAdapter.setShowCoverArt(false);  // 不显示封面
                        songAdapter.processAndSubmitList(songs);
                        
                        // 设置Toolbar标题
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setTitle(R.string.all_songs);
                        }
                        
                        // 更新菜单状态
                        invalidateOptionsMenu();
                    } else {
                        Toast.makeText(this, R.string.error_no_songs, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> 
                    Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show()
                );
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
        // 注销广播接收器
        unregisterReceiver(playbackStateReceiver);
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
} 