package com.optimus0701.phantompad;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.documentfile.provider.DocumentFile;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import com.optimus0701.phantompad.log.Logger;

import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {
    private SharedPreferences mPrefs;
    private RecyclerView mRecyclerView;
    private AudioFilesAdapter mAdapter;
    private List<AudioFile> mAudioFiles = new ArrayList<>();
    private String mCurrentPlayingUri = null;
    private final Handler mAutoStopHandler = new Handler(Looper.getMainLooper());
    private Runnable mAutoStopRunnable = null;
    private long mRemainingDurationMs = 0;
    private long mCurrentPlayStartTime = 0;
    private MediaPlayer mMediaPlayer = null;

    private ActivityResultLauncher<Uri> mFolderPicker;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFolderPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null && getContext() != null) {
                        getContext().getContentResolver().takePersistableUriPermission(uri, 
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        mPrefs.edit().putString("recordings_path_uri", uri.toString()).apply();
                        loadAudioFiles(uri);
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        
        if (getContext() == null) return view;
        mPrefs = getContext().getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
        
        mRecyclerView = view.findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        view.findViewById(R.id.btn_select_folder).setOnClickListener(v -> mFolderPicker.launch(null));
        view.findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            String savedUriString = mPrefs.getString("recordings_path_uri", null);
            if (savedUriString != null) {
                loadAudioFiles(Uri.parse(savedUriString));
                Toast.makeText(getContext(), "Refreshed", Toast.LENGTH_SHORT).show();
            }
        });
        
        mAdapter = new AudioFilesAdapter(mAudioFiles, mPrefs, new AudioFilesAdapter.OnItemClickListener() {
            @Override
            public void onPlayClick(AudioFile file) {
                String uriStr = file.uri.toString();
                
                if (uriStr.equals(mCurrentPlayingUri)) {
                    stopPlayback();
                    if (getContext() != null) Toast.makeText(getContext(), "Stopped", Toast.LENGTH_SHORT).show();
                } else {
                    if (mCurrentPlayingUri != null) {
                        stopPlayback();
                    }
                    playFile(file);
                }
            }

            @Override
            public void onPauseClick(AudioFile file) {
                if (getContext() == null) return;
                
                if (mMediaPlayer != null) {
                    try {
                        if (mMediaPlayer.isPlaying()) mMediaPlayer.pause();
                    } catch (Exception ignored) {}
                }
                
                Intent intent = new Intent("com.optimus0701.phantompad.ACTION_CONTROL");
                intent.putExtra("cmd", "pause");
                getContext().sendBroadcast(intent);
                mAdapter.setPaused(true);
                
                long watched = android.os.SystemClock.elapsedRealtime() - mCurrentPlayStartTime;
                mRemainingDurationMs -= watched;
                cancelAutoStop();
            }

            @Override
            public void onResumeClick(AudioFile file) {
                if (getContext() == null) return;
                
                if (mMediaPlayer != null) {
                    try {
                        mMediaPlayer.start();
                    } catch (Exception ignored) {}
                }
                
                Intent intent = new Intent("com.optimus0701.phantompad.ACTION_CONTROL");
                intent.putExtra("cmd", "resume");
                getContext().sendBroadcast(intent);
                mAdapter.setPaused(false);
                
                mCurrentPlayStartTime = android.os.SystemClock.elapsedRealtime();
                if (mRemainingDurationMs > 0) {
                    mAutoStopHandler.postDelayed(mAutoStopRunnable, mRemainingDurationMs);
                }
            }

            @Override
            public void onMoreClick(AudioFile file) {
                showAssignmentDialog(file);
            }
        });
        mRecyclerView.setAdapter(mAdapter);
        
        String savedUriString = mPrefs.getString("recordings_path_uri", null);
        if (savedUriString != null) {
            loadAudioFiles(Uri.parse(savedUriString));
        }
        
        return view;
    }

    private void playFile(AudioFile file) {
        if (getContext() == null) return;
        String uriStr = file.uri.toString();
        
        String cachedFilePath = copyToAccessibleCache(file.uri);
        
        boolean mixAudio = mPrefs.getBoolean("mix_audio", false);
        boolean playLocal = mPrefs.getBoolean("play_local", false);
        Intent intent = new Intent("com.optimus0701.phantompad.ACTION_CONTROL");
        intent.putExtra("cmd", "play");
        intent.putExtra("mix_audio", mixAudio);
        intent.putExtra("uri", uriStr); 
        if (cachedFilePath != null) {
            intent.putExtra("file_path", cachedFilePath);
        }
        getContext().sendBroadcast(intent);
        
        mCurrentPlayingUri = uriStr;
        
        long durationMs = getAudioDuration(file.uri);
        mAdapter.setNowPlaying(uriStr, durationMs);
        
        if (durationMs > 0) {
            cancelAutoStop();
            mAutoStopRunnable = () -> {
                mCurrentPlayingUri = null;
                mAdapter.setNowPlaying(null, 0);
                if (mMediaPlayer != null) {
                    try {
                        mMediaPlayer.stop();
                        mMediaPlayer.release();
                    } catch (Exception ignored) {}
                    mMediaPlayer = null;
                }
                if (getContext() != null) {
                    Intent stopIntent = new Intent("com.optimus0701.phantompad.ACTION_CONTROL");
                    stopIntent.putExtra("cmd", "stop");
                    getContext().sendBroadcast(stopIntent);
                }
            };
            mRemainingDurationMs = durationMs;
            mCurrentPlayStartTime = android.os.SystemClock.elapsedRealtime();
            mAutoStopHandler.postDelayed(mAutoStopRunnable, mRemainingDurationMs);
        }
        
        if (playLocal) {
            playLocalAudio(file.uri);
        }
        
        Toast.makeText(getContext(), "▶ " + file.name, Toast.LENGTH_SHORT).show();
    }
    
    private void playLocalAudio(Uri uri) {
        if (getContext() == null) return;
        try {
            if (mMediaPlayer != null) {
                mMediaPlayer.release();
            }
            mMediaPlayer = new MediaPlayer();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            } else {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            }
            mMediaPlayer.setVolume(1.0f, 1.0f);
            mMediaPlayer.setDataSource(getContext(), uri);
            mMediaPlayer.prepare();
            mMediaPlayer.start();
        } catch (Exception ignored) {}
    }

    private String copyToAccessibleCache(Uri sourceUri) {
        if (getContext() == null) return null;
        try {
            java.io.File privateFile = new java.io.File(getContext().getCacheDir(), "phantom_staging.tmp");
            try (java.io.InputStream is = getContext().getContentResolver().openInputStream(sourceUri);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(privateFile)) {
                if (is == null) return null;
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            }
            
            String destPath = "/data/local/tmp/phantom_audio.tmp";
            String srcPath = privateFile.getAbsolutePath();
            
            Process process = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "cp " + srcPath + " " + destPath + " && chmod 644 " + destPath
            });
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                com.optimus0701.phantompad.log.Logger.d("Audio copied via su to: " + destPath);
                return destPath;
            } else {
                com.optimus0701.phantompad.log.Logger.d("su copy failed with exit code: " + exitCode);
                return null;
            }
        } catch (Exception e) {
            com.optimus0701.phantompad.log.Logger.d("Failed to copy audio: " + e.getMessage());
            return null;
        }
    }

    private void stopPlayback() {
        if (getContext() == null) return;
        cancelAutoStop();
        
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.stop();
                mMediaPlayer.release();
            } catch (Exception ignored) {}
            mMediaPlayer = null;
        }
        
        Intent intent = new Intent("com.optimus0701.phantompad.ACTION_CONTROL");
        intent.putExtra("cmd", "stop");
        getContext().sendBroadcast(intent);
        mCurrentPlayingUri = null;
        mAdapter.setNowPlaying(null, 0);
    }

    private void cancelAutoStop() {
        if (mAutoStopRunnable != null) {
            mAutoStopHandler.removeCallbacks(mAutoStopRunnable);
            mAutoStopRunnable = null;
        }
    }

    private long getAudioDuration(Uri uri) {
        if (getContext() == null) return 0;
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(getContext(), uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();
            if (durationStr != null) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception e) {
        }
        return 0;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelAutoStop();
    }

    private void loadAudioFiles(Uri parentUri) {
        if (getContext() == null) return;
        mAudioFiles.clear();
        DocumentFile directory = DocumentFile.fromTreeUri(getContext(), parentUri);
        if (directory != null && directory.isDirectory()) {
            for (DocumentFile file : directory.listFiles()) {
                if (file.isFile() && file.getType() != null && file.getType().startsWith("audio/")) {
                    mAudioFiles.add(new AudioFile(file.getName(), file.getUri()));
                }
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private void showAssignmentDialog(AudioFile audioFile) {
        if (getContext() == null) return;
        BottomSheetDialog dialog = new BottomSheetDialog(getContext());
        ListView listView = new ListView(getContext());
        
        List<String> options = new ArrayList<>();
        List<String> packageNames = new ArrayList<>();
        
        options.add("Global (All Apps)");
        packageNames.add("global");
        
        android.content.pm.PackageManager pm = getContext().getPackageManager();
        List<android.content.pm.ApplicationInfo> installedApps = pm.getInstalledApplications(0);
        
        class AppItem {
            String name;
            String pkg;
            AppItem(String n, String p) { name = n; pkg = p; }
        }
        List<AppItem> appItems = new ArrayList<>();
        
        java.util.HashSet<String> addedPkgs = new java.util.HashSet<>();
        
        for (android.content.pm.ApplicationInfo info : installedApps) {
            boolean isLaunchable = pm.getLaunchIntentForPackage(info.packageName) != null;
            boolean isHooked = mPrefs.getBoolean("hooked_" + info.packageName, false);
            
            if (isLaunchable || isHooked || info.packageName.contains("recorder")) {
                if (addedPkgs.add(info.packageName)) {
                    appItems.add(new AppItem(pm.getApplicationLabel(info).toString(), info.packageName));
                }
            }
        }
        
        for (String key : mPrefs.getAll().keySet()) {
            if (key.startsWith("hooked_")) {
                String pkg = key.substring(7);
                if (addedPkgs.add(pkg)) {
                    appItems.add(new AppItem(pkg, pkg));
                }
            }
        }
        
        java.util.Collections.sort(appItems, (a, b) -> a.name.compareToIgnoreCase(b.name));
        for (AppItem item : appItems) {
            options.add(item.name);
            packageNames.add(item.pkg);
        }
        
        options.add("Clear Assignment");
        packageNames.add("clear");
        
        listView.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, options));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String pkg = packageNames.get(position);
            if (pkg.equals("clear")) {
                mPrefs.edit().remove("global_audio").apply();
                for (String key : mPrefs.getAll().keySet()) {
                    if (key.startsWith("app_") && mPrefs.getString(key, "").equals(audioFile.uri.toString())) {
                        mPrefs.edit().remove(key).apply();
                    }
                }
            } else if (pkg.equals("global")) {
                mPrefs.edit().putString("global_audio", audioFile.uri.toString()).apply();
            } else {
                mPrefs.edit().putString("app_" + pkg, audioFile.uri.toString()).apply();
            }
            mAdapter.notifyDataSetChanged();
            dialog.dismiss();
            Toast.makeText(getContext(), "Assigned to " + options.get(position), Toast.LENGTH_SHORT).show();
        });
        
        dialog.setContentView(listView);
        dialog.show();
    }
}
