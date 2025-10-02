package com.watch.limusic.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;

public class SectionHeaderAdapter extends RecyclerView.Adapter<SectionHeaderAdapter.VH> {
    private CharSequence title;
    private View.OnClickListener clickListener;
    private boolean showSelectAll = false;
    private View.OnClickListener selectAllClickListener;

    public SectionHeaderAdapter(CharSequence title) {
        this.title = title;
        setHasStableIds(true);
    }

    public void setTitle(CharSequence t) {
        this.title = t;
        notifyDataSetChanged();
    }

    public void setOnClickListener(View.OnClickListener l) {
        this.clickListener = l;
        notifyDataSetChanged();
    }

    public void setSelectAllVisible(boolean visible, View.OnClickListener l) {
        this.showSelectAll = visible;
        this.selectAllClickListener = l;
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) { return 1L; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_artist_section_header, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.text.setText(title);
        // 扩大点击区域：整行可点击折叠/展开
        if (clickListener != null) holder.itemView.setOnClickListener(clickListener); else holder.itemView.setOnClickListener(null);
        if (holder.selectAll != null) {
            holder.selectAll.setVisibility(showSelectAll ? View.VISIBLE : View.GONE);
            if (showSelectAll) holder.selectAll.setOnClickListener(selectAllClickListener);
            else holder.selectAll.setOnClickListener(null);
        }
    }

    @Override
    public int getItemCount() { return 1; }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;
        final TextView selectAll;
        VH(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.section_title) instanceof TextView ? (TextView) itemView.findViewById(R.id.section_title) : (TextView) itemView;
            selectAll = itemView.findViewById(R.id.btn_select_all) instanceof TextView ? (TextView) itemView.findViewById(R.id.btn_select_all) : null;
        }
    }
} 