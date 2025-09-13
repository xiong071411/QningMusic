package com.watch.limusic.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;
import com.watch.limusic.database.PlaylistEntity;

import java.util.ArrayList;
import java.util.List;

public class PlaylistPickerAdapter extends RecyclerView.Adapter<PlaylistPickerAdapter.ViewHolder> {
    public interface OnItemSelectedListener {
        void onItemSelected(PlaylistEntity entity);
    }

    private final List<PlaylistEntity> all;
    private final List<PlaylistEntity> filtered;
    private long selectedLocalId = -1L;
    private OnItemSelectedListener listener;

    public PlaylistPickerAdapter(List<PlaylistEntity> data) {
        this.all = data != null ? new ArrayList<>(data) : new ArrayList<>();
        this.filtered = new ArrayList<>(this.all);
        setHasStableIds(true);
    }

    public void setOnItemSelectedListener(OnItemSelectedListener l) { this.listener = l; }

    // 保留接口但不启用搜索过滤，以后可按需开启
    public void filter(String q) {
        this.filtered.clear();
        this.filtered.addAll(this.all);
        notifyDataSetChanged();
    }

    public void setSelectedLocalId(long id) {
        if (this.selectedLocalId == id) return;
        this.selectedLocalId = id;
        notifyDataSetChanged();
    }

    public PlaylistEntity getSelected() {
        for (PlaylistEntity e : filtered) {
            if (e.getLocalId() == selectedLocalId) return e;
        }
        return null;
    }

    @Override
    public long getItemId(int position) {
        PlaylistEntity e = filtered.get(position);
        return e != null ? e.getLocalId() : RecyclerView.NO_ID;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist_pick, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PlaylistEntity e = filtered.get(position);
        String name = e.getName() != null ? e.getName() : "";
        holder.title.setText(name);
        holder.subtitle.setText("(" + e.getSongCount() + " 首)");
        boolean checked = e.getLocalId() == selectedLocalId;
        holder.check.setImageResource(checked ? R.drawable.ic_check_box : R.drawable.ic_check_box_outline);
        holder.itemView.setOnClickListener(v -> {
            setSelectedLocalId(e.getLocalId());
            if (listener != null) listener.onItemSelected(e);
        });
    }

    @Override
    public int getItemCount() { return filtered.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageView check;
        ViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            check = itemView.findViewById(R.id.check);
        }
    }
} 