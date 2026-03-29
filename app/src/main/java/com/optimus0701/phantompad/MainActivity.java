package com.optimus0701.phantompad;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import android.widget.CheckBox;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private SharedPreferences mPrefs;
    private RecyclerView mRecyclerView;
    private AudioFilesAdapter mAdapter;
    private List<AudioFile> mAudioFiles = new ArrayList<>();
    private String mCurrentPlayingUri = null;
    private final Handler mAutoStopHandler = new Handler(Looper.getMainLooper());
    private Runnable mAutoStopRunnable = null;

    private final ActivityResultLauncher<Uri> mFolderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(uri, 
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    mPrefs.edit().putString("recordings_path_uri", uri.toString()).apply();
                    loadAudioFiles(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this.getApplication());
        setContentView(R.layout.activity_main);
        
        mPrefs = getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);
        
        mRecyclerView = findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                showSettingsDialog();
                return true;
            }
            return false;
        });
        
        ExtendedFloatingActionButton fab = findViewById(R.id.fab_select_folder);
        fab.setOnClickListener(v -> mFolderPicker.launch(null));
        
        mAdapter = new AudioFilesAdapter(mAudioFiles, mPrefs, new AudioFilesAdapter.OnItemClickListener() {
            @Override
            public void onPlayClick(AudioFile file) {
                String uriStr = file.uri.toString();
                
                if (uriStr.equals(mCurrentPlayingUri)) {
                    // STOP current
                    stopPlayback();
                    Toast.makeText(MainActivity.this, "Stopped", Toast.LENGTH_SHORT).show();
                } else {
                    // If something else is playing, stop it first
                    if (mCurrentPlayingUri != null) {
                        stopPlayback();
                    }
                    // PLAY new file
                    playFile(file);
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
    }

    private void playFile(AudioFile file) {
        String uriStr = file.uri.toString();
        
        // Copy file to world-readable cache so ALL hooked apps can access it
        String cachedFilePath = copyToAccessibleCache(file.uri);
        
        boolean mixAudio = mPrefs.getBoolean("mix_audio", false);
        android.content.Intent intent = new android.content.Intent("com.optimus0701.phantompad.ACTION_CONTROL");
        intent.putExtra("cmd", "play");
        intent.putExtra("mix_audio", mixAudio);
        intent.putExtra("uri", uriStr); // Original SAF URI for ContentProvider fallback
        if (cachedFilePath != null) {
            intent.putExtra("file_path", cachedFilePath); // Direct file path for Discord
        }
        sendBroadcast(intent);
        
        mCurrentPlayingUri = uriStr;
        
        // Get audio duration and schedule auto-stop
        long durationMs = getAudioDuration(file.uri);
        mAdapter.setNowPlaying(uriStr, durationMs);
        
        if (durationMs > 0) {
            cancelAutoStop();
            mAutoStopRunnable = () -> {
                // Auto-stop when duration is reached
                mCurrentPlayingUri = null;
                mAdapter.setNowPlaying(null, 0);
                android.content.Intent stopIntent = new android.content.Intent("com.optimus0701.phantompad.ACTION_CONTROL");
                stopIntent.putExtra("cmd", "stop");
                sendBroadcast(stopIntent);
            };
            mAutoStopHandler.postDelayed(mAutoStopRunnable, durationMs);
        }
        
        Toast.makeText(this, "▶ " + file.name, Toast.LENGTH_SHORT).show();
    }
    
    private String copyToAccessibleCache(Uri sourceUri) {
        try {
            // Step 1: Copy SAF URI to our private cache (PhantomPad has SAF permission)
            java.io.File privateFile = new java.io.File(getCacheDir(), "phantom_staging.tmp");
            try (java.io.InputStream is = getContentResolver().openInputStream(sourceUri);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(privateFile)) {
                if (is == null) return null;
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            }
            
            // Step 2: Use root (su) to copy to world-readable location
            // LSPosed requires Magisk/root, so su is always available
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
        cancelAutoStop();
        android.content.Intent intent = new android.content.Intent("com.optimus0701.phantompad.ACTION_CONTROL");
        intent.putExtra("cmd", "stop");
        sendBroadcast(intent);
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
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();
            if (durationStr != null) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception e) {
            // fallback: unknown duration
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelAutoStop();
    }

    private void loadAudioFiles(Uri parentUri) {
        mAudioFiles.clear();
        DocumentFile directory = DocumentFile.fromTreeUri(this, parentUri);
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
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ListView listView = new ListView(this);
        
        List<String> options = new ArrayList<>();
        List<String> packageNames = new ArrayList<>();
        
        options.add("Global (All Apps)");
        packageNames.add("global");
        
        android.content.pm.PackageManager pm = getPackageManager();
        List<android.content.pm.ApplicationInfo> installedApps = pm.getInstalledApplications(0);
        
        class AppItem {
            String name;
            String pkg;
            AppItem(String n, String p) { name = n; pkg = p; }
        }
        List<AppItem> appItems = new ArrayList<>();
        
        // Use a set to prevent duplicates
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
        
        // Add any remaining hooked apps that pm couldn't resolve
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
        
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, options));
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
            Toast.makeText(this, "Assigned to " + options.get(position), Toast.LENGTH_SHORT).show();
        });
        
        dialog.setContentView(listView);
        dialog.show();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Cài đặt");
        
        android.view.View view = getLayoutInflater().inflate(android.R.layout.simple_list_item_multiple_choice, null);
        CheckBox checkBox = new CheckBox(this);
        boolean isMixed = mPrefs.getBoolean("mix_audio", false);
        checkBox.setText(isMixed ? "Phát âm thanh cùng với mic" : "Phát âm thanh và tắt mic");
        checkBox.setChecked(isMixed);
        
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        checkBox.setPadding(padding, padding, padding, padding);
        
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            checkBox.setText(isChecked ? "Phát âm thanh cùng với mic" : "Phát âm thanh và tắt mic");
        });

        builder.setView(checkBox);
        builder.setPositiveButton("Lưu", (dialog, which) -> {
            boolean checked = checkBox.isChecked();
            mPrefs.edit().putBoolean("mix_audio", checked).apply();
            Toast.makeText(MainActivity.this, "Đã lưu cài đặt", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }
}

