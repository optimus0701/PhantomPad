package com.optimus0701.phantompad;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.lang.ref.WeakReference;

import android.media.AudioAttributes;
import android.media.AudioManager;

import com.optimus0701.phantompad.audio.AudioMaster;
import com.optimus0701.phantompad.log.Logger;

public class PhantomManager {
    private final WeakReference<Context> mContext;
    private final AudioMaster mAudioMaster;
    
    private ParcelFileDescriptor mCurrentPfd = null;

    public PhantomManager(Context context, boolean isNativeHook) {
        Logger.d("Init phantom manager");

        mContext = new WeakReference<>(context);
        mAudioMaster = new AudioMaster();

        if (isNativeHook) {
            nativeHook();
        }
    }

    private String mLastLoadedFilePath = null;
    private boolean mHasActiveAudio = false; // True only after an explicit play command

    // Debounce format-change reloads: Discord fires format updates multiple times in rapid
    // succession (set_hook + obtainBuffer_hook). Without debounce, each update cancels the
    // previous decode and restarts it, causing vỡ tiếng / mất âm.
    private final android.os.Handler mFormatUpdateHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mPendingFormatUpdate = null;

    public void updateAudioFormat(int sampleRate, int channelMask, int encoding) {
        android.media.AudioFormat currentFormat = mAudioMaster.getFormat();
        boolean formatChanged = currentFormat == null ||
                                currentFormat.getSampleRate() != sampleRate ||
                                currentFormat.getChannelMask() != channelMask;

        mAudioMaster.setFormat(sampleRate, channelMask, encoding);
        Logger.d("Target format updated: " + sampleRate + "Hz, mask " + channelMask);

        if (formatChanged && mHasActiveAudio) {
            // Only reload if audio was previously loaded — avoid spurious reloads
            // during Discord's initial format detection when nothing is playing.
            if (mPendingFormatUpdate != null) {
                mFormatUpdateHandler.removeCallbacks(mPendingFormatUpdate);
            }
            final String filePathSnapshot = mLastLoadedFilePath;
            mPendingFormatUpdate = () -> {
                Logger.d("Format stabilised, reloading audio (path=" + filePathSnapshot + ")");
                if (filePathSnapshot != null) {
                    loadFromFile(filePathSnapshot);
                } else {
                    load();
                }
                mPendingFormatUpdate = null;
            };
            mFormatUpdateHandler.postDelayed(mPendingFormatUpdate, 150);
        }
    }

    public void load() {
        mLastLoadedFilePath = null;
        load(null);
    }
    
    public void loadFromFile(String filePath) {
        if (mAudioMaster.getFormat() == null) {
            Logger.d("No audio format detected, using default 48kHz Mono PCM16");
            mAudioMaster.setFormat(48000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
        }
        unload();
        try {
            java.io.File file = new java.io.File(filePath);
            if (!file.exists() || !file.canRead()) {
                Logger.d("File not accessible: " + filePath + " (exists=" + file.exists() + ", canRead=" + file.canRead() + ")");
                // Fall back to URI-based load
                load(null);
                return;
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            mAudioMaster.load(fis.getFD());
            mHasActiveAudio = true;
            mLastLoadedFilePath = filePath;
            Logger.d("Audio file loaded directly from path: " + filePath);
        } catch (Exception e) {
            Logger.d("Error loading from file path: " + e.getMessage());
        }
    }

    public void load(String explicitUriStr) {
        if (mAudioMaster.getFormat() == null) {
            // No format set yet (e.g. MediaRecorder apps). Use a safe default.
            Logger.d("No audio format detected, using default 48kHz Mono PCM16");
            mAudioMaster.setFormat(48000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
        }
        unload();
        
        Context context = mContext.get();
        if (context == null) {
            Logger.d("Context is null, cannot load audio");
            return;
        }
        
        try {
            if (explicitUriStr != null) {
                // Try direct SAF URI open first (works when this process has SAF permission)
                try {
                    Uri directUri = Uri.parse(explicitUriStr);
                    mCurrentPfd = context.getContentResolver().openFileDescriptor(directUri, "r");
                    if (mCurrentPfd != null) {
                        mAudioMaster.load(mCurrentPfd.getFileDescriptor());
                        mHasActiveAudio = true;
                        Logger.d("Audio file loaded directly from URI");
                        return;
                    }
                } catch (Exception directEx) {
                    Logger.d("Direct URI failed (" + directEx.getMessage() + "), trying Provider...");
                }
            }
            
            // Fallback: use ContentProvider (works for Messenger and apps that can see Provider)
            Uri.Builder builder = Uri.parse("content://com.optimus0701.phantompad.provider/audio").buildUpon();
            if (explicitUriStr != null) {
                builder.appendQueryParameter("target", explicitUriStr);
            }
            Uri providerUri = builder.build();
            
            mCurrentPfd = context.getContentResolver().openFileDescriptor(providerUri, "r");
            if (mCurrentPfd != null) {
                mAudioMaster.load(mCurrentPfd.getFileDescriptor());
                mHasActiveAudio = true;
                Logger.d("Audio file loaded from SoundboardProvider");
            } else {
                Logger.d("SoundboardProvider returned null PFD");
            }
        } catch (Exception e) {
            Logger.d("Error loading audio: " + e.getMessage());
        }
    }

    public void unload() {
        mHasActiveAudio = false;
        mAudioMaster.unload();
        
        if (mCurrentPfd != null) {
            try {
                mCurrentPfd.close();
            } catch (IOException e) {
                Logger.d("Error closing PFD: " + e.getMessage());
            }
            mCurrentPfd = null;
        }
        
        nativeClearBuffer();
        Logger.d("Done unloading data");
    }

    public void setMixAudio(boolean mixAudio) {
        nativeSetMixAudio(mixAudio);
    }

    public void setMicBoost(float boost) {
        nativeSetMicBoost(boost);
    }

    public void setBoostFile(boolean enabled) {
        nativeSetBoostFile(enabled);
    }

    public void setGateThreshold(float db) {
        nativeSetGateThreshold(db);
    }

    public void setCompThreshold(float db) {
        nativeSetCompThreshold(db);
    }

    public void setCompRatio(float ratio) {
        nativeSetCompRatio(ratio);
    }

    public void setPaused(boolean paused) {
        nativeSetPaused(paused);
    }

    public native void nativeSetMixAudio(boolean mixAudio);
    public native void nativeSetMicBoost(float boost);
    public native void nativeSetBoostFile(boolean enabled);
    public native void nativeSetGateThreshold(float db);
    public native void nativeSetCompThreshold(float db);
    public native void nativeSetCompRatio(float ratio);
    public native void nativeSetPaused(boolean paused);
    public native void nativeClearBuffer();
    private native void nativeHook();
}
