package com.watch.limusic.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;

public class HintFooterAdapter extends RecyclerView.Adapter<HintFooterAdapter.HintViewHolder> {
    private String text = null;
    private boolean visible = false;

    public void setHint(String t) {
        this.text = t;
        this.visible = (t != null && !t.isEmpty());
        notifyDataSetChanged();
    }

    public void hide() {
        this.text = null;
        this.visible = false;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return visible ? 1 : 0;
    }

    @NonNull
    @Override
    public HintViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_footer_hint, parent, false);
        return new HintViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull HintViewHolder holder, int position) {
        holder.textView.setText(text != null ? text : "");
    }

    static class HintViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;
        HintViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.footer_hint_text);
        }
    }
} 