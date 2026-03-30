package com.optimus0701.phantompad;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;

public class AudioFilesAdapter extends RecyclerView.Adapter<AudioFilesAdapter.ViewHolder> {
    private final List<AudioFile> mFiles;
    private final SharedPreferences mPrefs;
    private final OnItemClickListener mListener;
    private String mNowPlayingUri = null;
    private long mPlayStartTime = 0;
    private long mTotalElapsedMs = 0;
    private long mDurationMs = 0;
    private boolean mIsPaused = false;
    private final android.os.Handler mTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mTimerRunnable;

    public interface OnItemClickListener {
        void onPlayClick(AudioFile file);
        void onPauseClick(AudioFile file);
        void onResumeClick(AudioFile file);
        void onMoreClick(AudioFile file);
    }

    public void setNowPlaying(String uri, long durationMs) {
        mNowPlayingUri = uri;
        mDurationMs = durationMs;
        mIsPaused = false;
        mTotalElapsedMs = 0;
        if (uri != null) {
            mPlayStartTime = android.os.SystemClock.elapsedRealtime();
            startTimer();
        } else {
            mPlayStartTime = 0;
            mDurationMs = 0;
            stopTimer();
        }
        notifyDataSetChanged();
    }

    public void setPaused(boolean paused) {
        if (mIsPaused == paused) return;
        mIsPaused = paused;
        long now = android.os.SystemClock.elapsedRealtime();
        if (paused) {
            mTotalElapsedMs += (now - mPlayStartTime);
            stopTimer();
        } else {
            mPlayStartTime = now;
            startTimer();
        }
        notifyDataSetChanged();
    }

    private void startTimer() {
        stopTimer();
        mTimerRunnable = () -> {
            notifyDataSetChanged();
            mTimerHandler.postDelayed(mTimerRunnable, 1000);
        };
        mTimerHandler.postDelayed(mTimerRunnable, 1000);
    }

    private void stopTimer() {
        if (mTimerRunnable != null) {
            mTimerHandler.removeCallbacks(mTimerRunnable);
            mTimerRunnable = null;
        }
    }

    public AudioFilesAdapter(List<AudioFile> files, SharedPreferences prefs, OnItemClickListener listener) {
        mFiles = files;
        mPrefs = prefs;
        mListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_audio, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AudioFile file = mFiles.get(position);
        holder.textFilename.setText(file.name);
        
        StringBuilder status = new StringBuilder();
        String uriStr = file.uri.toString();
        
        if (uriStr.equals(mPrefs.getString("global_audio", ""))) {
            status.append("Global Active");
        }
        
        for (Map.Entry<String, ?> entry : mPrefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("app_") && uriStr.equals(entry.getValue())) {
                if (status.length() > 0) status.append(" | ");
                status.append(entry.getKey().substring(4));
            }
        }
        
        if (status.length() > 0) {
            holder.textStatus.setText(status.toString());
            holder.textStatus.setVisibility(View.VISIBLE);
        } else {
            holder.textStatus.setVisibility(View.GONE);
        }
        
        if (uriStr.equals(mNowPlayingUri)) {
            long elapsedMs = mTotalElapsedMs;
            if (!mIsPaused) {
                elapsedMs += (android.os.SystemClock.elapsedRealtime() - mPlayStartTime);
            }
            long elapsedSec = elapsedMs / 1000;
            long min = elapsedSec / 60;
            long sec = elapsedSec % 60;
            
            holder.progressNowPlaying.setVisibility(View.VISIBLE);
            
            if (mDurationMs > 0) {
                long totalSec = mDurationMs / 1000;
                long totalMin = totalSec / 60;
                long totalSecR = totalSec % 60;
                holder.textNowPlaying.setText(String.format("%s  %d:%02d / %d:%02d", mIsPaused ? "♪ Paused" : "♪ Now Playing", min, sec, totalMin, totalSecR));
                
                holder.progressNowPlaying.setMax((int) mDurationMs);
                holder.progressNowPlaying.setProgress((int) elapsedMs);
            } else {
                holder.textNowPlaying.setText(String.format("%s  %d:%02d", mIsPaused ? "♪ Paused" : "♪ Now Playing", min, sec));
                holder.progressNowPlaying.setIndeterminate(true);
            }
            holder.textNowPlaying.setVisibility(View.VISIBLE);
            
            if (mIsPaused) {
                holder.btnPlay.setImageResource(android.R.drawable.ic_media_play);
                holder.btnPlay.setOnClickListener(v -> mListener.onResumeClick(file));
            } else {
                holder.btnPlay.setImageResource(android.R.drawable.ic_media_pause);
                holder.btnPlay.setOnClickListener(v -> mListener.onPauseClick(file));
            }
        } else {
            holder.btnPlay.setImageResource(android.R.drawable.ic_media_play);
            holder.textNowPlaying.setVisibility(View.GONE);
            holder.progressNowPlaying.setVisibility(View.GONE);
            holder.btnPlay.setOnClickListener(v -> mListener.onPlayClick(file));
        }
        
        holder.btnMore.setOnClickListener(v -> mListener.onMoreClick(file));
    }

    @Override
    public int getItemCount() {
        return mFiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textFilename, textStatus, textNowPlaying;
        android.widget.ImageButton btnPlay, btnMore;
        com.google.android.material.progressindicator.LinearProgressIndicator progressNowPlaying;
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            textFilename = itemView.findViewById(R.id.text_filename);
            textStatus = itemView.findViewById(R.id.text_status);
            textNowPlaying = itemView.findViewById(R.id.text_now_playing);
            progressNowPlaying = itemView.findViewById(R.id.progress_now_playing);
            btnPlay = itemView.findViewById(R.id.btn_play);
            btnMore = itemView.findViewById(R.id.btn_more);
        }
    }
}

