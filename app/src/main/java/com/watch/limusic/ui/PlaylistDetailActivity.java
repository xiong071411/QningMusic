package com.watch.limusic.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.adapter.SongAdapter;
import com.watch.limusic.database.MusicDatabase;
import com.watch.limusic.database.PlaylistEntity;
import com.watch.limusic.model.Song;
import com.watch.limusic.model.SongWithIndex;
import com.watch.limusic.repository.PlaylistRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlaylistDetailActivity extends AppCompatActivity implements SongAdapter.OnSongClickListener {
	private static final String EXTRA_LOCAL_ID = "extra_local_id";
	public static void start(Context ctx, long playlistLocalId) {
		Intent i = new Intent(ctx, PlaylistDetailActivity.class);
		i.putExtra(EXTRA_LOCAL_ID, playlistLocalId);
		ctx.startActivity(i);
	}

	private long playlistLocalId;
	private RecyclerView recyclerView;
	private SongAdapter songAdapter;
	private PlaylistRepository repository;
	private TextView syncStatusView;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_playlist_detail);
		setSupportActionBar(findViewById(R.id.toolbar));
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		findViewById(R.id.toolbar).setOnClickListener(v -> onBackPressed());

		repository = PlaylistRepository.getInstance(this);
		playlistLocalId = getIntent().getLongExtra(EXTRA_LOCAL_ID, -1);
		recyclerView = findViewById(R.id.recycler_view);
		syncStatusView = findViewById(R.id.sync_status);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		songAdapter = new SongAdapter(this, this);
		songAdapter.setShowDownloadStatus(false);
		recyclerView.setAdapter(songAdapter);

		// 设置标题为歌单名
		PlaylistEntity p = MusicDatabase.getInstance(this).playlistDao().getByLocalId(playlistLocalId);
		if (getSupportActionBar() != null && p != null) getSupportActionBar().setTitle(p.getName());

		setupDragAndDrop();
		maybeShowSyncHintOnce();
		loadLocal();
		startValidateSync();
	}

	@Override
	public boolean onSupportNavigateUp() { onBackPressed(); return true; }

	private void setupDragAndDrop() {
		ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
			private int fromPos = -1;
			private int toPos = -1;

			@Override
			public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder vh, RecyclerView.ViewHolder target) {
				int from = vh.getBindingAdapterPosition();
				int to = target.getBindingAdapterPosition();
				if (fromPos == -1) fromPos = from;
				toPos = to;
				List<SongWithIndex> current = new ArrayList<>(songAdapter.getCurrentList());
				if (from < 0 || to < 0 || from >= current.size() || to >= current.size()) return false;
				Collections.swap(current, from, to);
				for (int i = 0; i < current.size(); i++) current.get(i).setPosition(i);
				songAdapter.submitList(current);
				return true;
			}

			@Override
			public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
				super.clearView(rv, vh);
				if (fromPos != -1 && toPos != -1 && fromPos != toPos) {
					repository.reorder(playlistLocalId, fromPos, toPos);
				}
				fromPos = -1;
				toPos = -1;
			}

			@Override public boolean isLongPressDragEnabled() { return false; }
			@Override public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) { /* no-op */ }
		});
		helper.attachToRecyclerView(recyclerView);
		songAdapter.setShowDragHandle(true, vh -> helper.startDrag(vh));
	}

	private void maybeShowSyncHintOnce() {
		SharedPreferences sp = getSharedPreferences("playlist_prefs", MODE_PRIVATE);
		boolean shown = sp.getBoolean("sync_hint_shown", false);
		if (!shown) {
			new androidx.appcompat.app.AlertDialog.Builder(this)
					.setTitle("提示")
					.setMessage("标题栏指示器：绿=已同步，蓝=同步中，红=失败可点重试。")
					.setPositiveButton("确认", (d,w)->{})
					.setNegativeButton("不再提示", (d,w)-> sp.edit().putBoolean("sync_hint_shown", true).apply())
					.show();
		}
	}

	private void setSyncStatusSyncing() { runOnUiThread(() -> { syncStatusView.setText("同步中"); syncStatusView.setTextColor(Color.BLUE); }); }
	private void setSyncStatusOk() { runOnUiThread(() -> { syncStatusView.setText("已同步"); syncStatusView.setTextColor(Color.GREEN); }); }
	private void setSyncStatusFailed() { runOnUiThread(() -> { syncStatusView.setText("同步失败，点此重试"); syncStatusView.setTextColor(Color.RED); syncStatusView.setOnClickListener(v -> startValidateSync()); }); }

	private void startValidateSync() {
		setSyncStatusSyncing();
		new Thread(() -> {
			boolean refreshed = repository.validateAndMaybeRefreshFromServer(playlistLocalId);
			if (refreshed) loadLocal();
			setSyncStatusOk();
		}).start();
	}

	private void loadLocal() {
		List<Song> songs = repository.getSongsInPlaylist(playlistLocalId, 500, 0);
		List<SongWithIndex> list = new ArrayList<>();
		for (int i = 0; i < songs.size(); i++) {
			Song s = songs.get(i);
			list.add(new SongWithIndex(s, i, true));
		}
		runOnUiThread(() -> songAdapter.submitList(list));
	}

	@Override
	public void onSongClick(Song song, int position) { Toast.makeText(this, "点击: " + song.getTitle(), Toast.LENGTH_SHORT).show(); }
} 