package com.watch.limusic.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.adapter.PlaylistAdapter;
import com.watch.limusic.database.MusicDatabase;
import com.watch.limusic.database.PlaylistEntity;
import com.watch.limusic.repository.PlaylistRepository;

import java.util.List;

public class PlaylistListActivity extends AppCompatActivity implements PlaylistAdapter.OnPlaylistListener {
	private RecyclerView recyclerView;
	private PlaylistAdapter adapter;
	private PlaylistRepository repository;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_playlist_list);
		setSupportActionBar(findViewById(R.id.toolbar));
		if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.playlist);
		repository = PlaylistRepository.getInstance(this);
		recyclerView = findViewById(R.id.recycler_view);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		adapter = new PlaylistAdapter(this, this);
		recyclerView.setAdapter(adapter);
		loadData();
	}

	@Override public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_playlist_list, menu);
		return true;
	}
	@Override public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_new_playlist) { promptCreate(); return true; }
		return super.onOptionsItemSelected(item);
	}

	private void loadData() {
		List<PlaylistEntity> list = MusicDatabase.getInstance(this).playlistDao().getAll();
		adapter.submitList(list);
	}

	private void promptCreate() {
		final EditText input = new EditText(this);
		input.setHint("输入歌单名称");
		new AlertDialog.Builder(this)
				.setTitle("新建歌单")
				.setView(input)
				.setPositiveButton("创建", (dialog, which) -> {
					String name = input.getText() != null ? input.getText().toString() : "";
					try {
						repository.createPlaylist(name, false);
						Toast.makeText(this, "已创建", Toast.LENGTH_SHORT).show();
						loadData();
					} catch (Exception e) {
						Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
					}
				})
				.setNegativeButton("取消", null)
				.show();
	}

	@Override
	public void onClick(PlaylistEntity playlist) {
		// 统一到 MainActivity 内嵌详情：通过 Intent Extra 请求打开该歌单
		android.content.Intent i = new android.content.Intent(this, com.watch.limusic.MainActivity.class);
		i.putExtra("open_playlist_local_id", playlist.getLocalId());
		i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
		startActivity(i);
	}

	@Override
	public void onLongClick(android.view.View anchor, PlaylistEntity playlist) {
		String[] items = new String[]{"删除", playlist.isPublic() ? "设为私有" : "设为公开", "重命名"};
		new AlertDialog.Builder(this)
				.setTitle(playlist.getName())
				.setItems(items, (dialog, which) -> {
					if (which == 0) { confirmDelete(playlist); }
					else if (which == 1) { repository.setPublic(playlist.getLocalId(), !playlist.isPublic()); loadData(); }
					else if (which == 2) { promptRename(playlist); }
				})
				.show();
	}

	private void confirmDelete(PlaylistEntity playlist) {
		new AlertDialog.Builder(this)
				.setTitle("确认删除")
				.setMessage("确认删除歌单‘" + playlist.getName() + "’吗？")
				.setPositiveButton("删除", (d, w) -> { repository.delete(playlist.getLocalId()); loadData(); })
				.setNegativeButton("取消", null)
				.show();
	}

	private void promptRename(PlaylistEntity playlist) {
		final EditText input = new EditText(this);
		input.setText(playlist.getName());
		new AlertDialog.Builder(this)
				.setTitle("重命名")
				.setView(input)
				.setPositiveButton("确定", (d, w) -> {
					try { repository.rename(playlist.getLocalId(), input.getText().toString()); loadData(); }
					catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
				})
				.setNegativeButton("取消", null)
				.show();
	}
} 