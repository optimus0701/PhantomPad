package com.optimus0701.phantompad.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.nailik.androidresampler.Resampler;
import io.github.nailik.androidresampler.ResamplerConfiguration;
import io.github.nailik.androidresampler.data.ResamplerChannel;
import io.github.nailik.androidresampler.data.ResamplerQuality;
import com.optimus0701.phantompad.log.Logger;

public class AudioMaster {
    private static final int TIMEOUT_MS = 1000;

    private AudioFormat mOutFormat;
    private final java.util.concurrent.atomic.AtomicInteger mLoadId = new java.util.concurrent.atomic.AtomicInteger(0);

    private final ExecutorService audioLoadExecutor = Executors.newSingleThreadExecutor();

    public void load(FileDescriptor fd) {
        int loadId = mLoadId.incrementAndGet();

        MediaExtractor extractor = new MediaExtractor();

        try {
            extractor.setDataSource(fd);
            extractor.selectTrack(0);
            MediaFormat format = extractor.getTrackFormat(0);

            String mimeType = format.getString(MediaFormat.KEY_MIME);
            if (mimeType == null) {
                Logger.d("mimeType cannot be null");
                return;
            }

            MediaCodec codec = MediaCodec.createDecoderByType(mimeType);
            codec.configure(format, null, null, 0);
            codec.start();

            nativeStartLoading();

            audioLoadExecutor.execute(() -> loadData(codec, format, extractor, loadId));
        } catch (Exception e) {
            Logger.d("AudioMaster: Error configuring MediaCodec: " + e.getMessage());
        }
    }

    public void unload() {
        mLoadId.incrementAndGet(); // This cancels any currently running load loop
    }

    private void loadData(MediaCodec codec, MediaFormat format, MediaExtractor extractor, int loadId) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        boolean isEOS = false;
        boolean aborted = false;
        Resampler resampler = null;
        
        try {
            do {
                if (!isEOS) {
                    int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_MS);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isEOS = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_MS);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null) {
                        byte[] pcmData = new byte[bufferInfo.size];
                        outputBuffer.get(pcmData);
                        outputBuffer.clear();

                        resampler = processInBuffer(format, pcmData, resampler);
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    format = codec.getOutputFormat();
                    Logger.d("Format changed to " + format);
                    resampler = null; // Recreate resampler on format change
                }

                if (mLoadId.get() != loadId) {
                    Logger.d("Loading aborted by new load request");
                    aborted = true;
                    break;
                }
            } while ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0);
        } catch (Exception e) {
            Logger.d("Loading exception: " + e.getMessage());
            aborted = true;
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }

        if (!aborted) {
            Logger.d("Loading done successfully");
            onLoadDone();
        } else {
            Logger.d("Loading cleanup completed after abort");
        }
    }

    private Resampler processInBuffer(MediaFormat source, byte[] bufferChunk, Resampler resampler) {
        if (bufferChunk.length == 0) {
            return resampler;
        }

        int sourceSampleRate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int sourceChannelCount = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int targetSampleRate = mOutFormat.getSampleRate();
        
        // Android's getChannelCount() is unreliable if we mix CHANNEL_IN and CHANNEL_OUT masks.
        // We explicitly check the mask value. 16 is CHANNEL_IN_MONO.
        int targetMask = mOutFormat.getChannelMask();
        int targetChannelCount = (targetMask == 16 || targetMask == android.media.AudioFormat.CHANNEL_OUT_MONO) ? 1 : 2;

        if (sourceSampleRate == targetSampleRate && sourceChannelCount == targetChannelCount) {
            // Perfect match, no resampling needed!
            onBufferChunkLoaded(bufferChunk);
            return null; // Return null so we don't hold an unnecessary instance
        }

        if (resampler == null) {
            ResamplerChannel inChannel = (sourceChannelCount == 1) ? ResamplerChannel.MONO : ResamplerChannel.STEREO;
            ResamplerChannel outChannel = (targetChannelCount == 1) ? ResamplerChannel.MONO : ResamplerChannel.STEREO;

            ResamplerConfiguration configuration = new ResamplerConfiguration(ResamplerQuality.BEST, inChannel,
                    sourceSampleRate, outChannel, targetSampleRate);
            resampler = new Resampler(configuration);
            Logger.d("AudioMaster: Initialized Resampler " + sourceSampleRate + "Hz -> " + targetSampleRate + "Hz");
        }

        byte[] resampledChunk = resampler.resample(bufferChunk);
        onBufferChunkLoaded(resampledChunk);
        return resampler;
    }

    public void setFormat(AudioFormat format) {
        mOutFormat = format;
    }

    public void setFormat(int sampleRate, int channelMask, int encoding) {
        mOutFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .build();
    }

    public AudioFormat getFormat() {
        return mOutFormat;
    }

    public native void onBufferChunkLoaded(byte[] bufferChunk);

    public native void onLoadDone();

    public native void nativeStartLoading();
}
