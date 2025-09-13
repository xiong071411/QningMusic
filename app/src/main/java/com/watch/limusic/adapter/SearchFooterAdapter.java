package com.watch.limusic.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watch.limusic.R;

public class SearchFooterAdapter extends RecyclerView.Adapter<SearchFooterAdapter.FootViewHolder> {

    public enum State { HIDDEN, LOADING, MORE, NOMORE }

    private State state = State.HIDDEN;

    public void setState(State s) {
        if (s == null) s = State.HIDDEN;
        if (this.state == s) return;
        this.state = s;
        notifyDataSetChanged();
    }

    public State getState() { return state; }

    @Override
    public int getItemCount() {
        // 隐藏“加载中”，仅在 MORE/NOMORE 显示
        return (state == State.MORE || state == State.NOMORE) ? 1 : 0;
    }

    @NonNull
    @Override
    public FootViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_footer, parent, false);
        return new FootViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull FootViewHolder holder, int position) {
        // 移除“加载中”的进度与文本，仅展示 MORE/NOMORE
        holder.progress.setVisibility(View.GONE);
        if (state == State.MORE) {
            holder.text.setText("上滑加载更多");
        } else if (state == State.NOMORE) {
            holder.text.setText(R.string.no_more_results);
        }
    }

    static class FootViewHolder extends RecyclerView.ViewHolder {
        final ProgressBar progress;
        final TextView text;
        FootViewHolder(@NonNull View itemView) {
            super(itemView);
            progress = itemView.findViewById(R.id.footer_progress);
            text = itemView.findViewById(R.id.footer_text);
        }
    }
} 