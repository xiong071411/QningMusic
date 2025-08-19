package com.watch.limusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.watch.limusic.R;
import com.watch.limusic.api.NavidromeApi;
import com.watch.limusic.database.PlaylistEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {
	public interface OnPlaylistListener {
		void onClick(PlaylistEntity playlist);
		void onLongClick(View anchor, PlaylistEntity playlist);
	}

	private final Context context;
	private final OnPlaylistListener listener;
	private final List<PlaylistEntity> data = new ArrayList<>();
	private final Map<String, String> coverArtByServerId = new HashMap<>();

	public PlaylistAdapter(Context context, OnPlaylistListener listener) {
		this.context = context;
		this.listener = listener;
	}

	public void submitList(List<PlaylistEntity> list) {
		data.clear();
		if (list != null) data.addAll(list);
		notifyDataSetChanged();
	}

	public void setCoverArtMap(Map<String, String> map) {
		coverArtByServerId.clear();
		if (map != null) coverArtByServerId.putAll(map);
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist, parent, false);
		return new ViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		PlaylistEntity p = data.get(position);
		holder.name.setText(p.getName());
		holder.info.setText(p.getSongCount() + " 首歌曲 · " + (p.getOwner() == null || p.getOwner().isEmpty() ? "-" : p.getOwner()));
		holder.publicFlag.setText(p.isPublic() ? "公开" : "私有");

		// 封面：优先使用服务端返回的 playlist coverArt，否则占位
		String coverId = null;
		if (p.getServerId() != null) {
			coverId = coverArtByServerId.get(p.getServerId());
		}
		Object source;
		if (coverId != null && !coverId.isEmpty()) {
			source = NavidromeApi.getInstance(context).getCoverArtUrl(coverId);
		} else {
			source = R.drawable.default_album_art;
		}

		RequestOptions opts = new RequestOptions()
				.format(DecodeFormat.PREFER_RGB_565)
				.disallowHardwareConfig()
				.dontAnimate()
				.override(40, 40)
				.placeholder(R.drawable.default_album_art)
				.error(R.drawable.default_album_art);

		Glide.with(context)
				.load(source)
				.apply(opts.diskCacheStrategy(DiskCacheStrategy.AUTOMATIC))
				.into(holder.cover);

		holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(p); });
		holder.itemView.setOnLongClickListener(v -> { if (listener != null) listener.onLongClick(v, p); return true; });
	}

	@Override
	public int getItemCount() { return data.size(); }

	static class ViewHolder extends RecyclerView.ViewHolder {
		ImageView cover;
		TextView name;
		TextView info;
		TextView publicFlag;
		ViewHolder(@NonNull View itemView) {
			super(itemView);
			cover = itemView.findViewById(R.id.playlist_cover);
			name = itemView.findViewById(R.id.playlist_name);
			info = itemView.findViewById(R.id.playlist_info);
			publicFlag = itemView.findViewById(R.id.playlist_public_flag);
		}
	}
} 