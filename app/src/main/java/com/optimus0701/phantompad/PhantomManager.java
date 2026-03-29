package com.optimus0701.phantompad;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.lang.ref.WeakReference;

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

    public void updateAudioFormat(int sampleRate, int channelMask, int encoding) {
        // Only trigger a reload if the format actually changes to avoid infinite load loops
        android.media.AudioFormat currentFormat = mAudioMaster.getFormat();
        boolean formatChanged = currentFormat == null || 
                                currentFormat.getSampleRate() != sampleRate ||
                                currentFormat.getChannelMask() != channelMask;
                                
        mAudioMaster.setFormat(sampleRate, channelMask, encoding);
        Logger.d("Target format updated: " + sampleRate + "Hz, mask " + channelMask);
        
        if (formatChanged) {
            Logger.d("Audio format changed, triggering reload of audio file with new resampler settings");
            if (mLastLoadedFilePath != null) {
                loadFromFile(mLastLoadedFilePath);
            } else {
                load();
            }
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
                Logger.d("Audio file loaded from SoundboardProvider");
            } else {
                Logger.d("SoundboardProvider returned null PFD");
            }
        } catch (Exception e) {
            Logger.d("Error loading audio: " + e.getMessage());
        }
    }

    public void unload() {
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

    public native void nativeSetMixAudio(boolean mixAudio);
    public native void nativeClearBuffer();
    private native void nativeHook();
}
