package com.optimus0701.phantompad.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.optimus0701.phantompad.log.Logger;

public class AudioMaster {
    private static final int TIMEOUT_MS = 1000;

    private AudioFormat mOutFormat;
    private final AtomicInteger mLoadId = new AtomicInteger(0);

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

                        processInBuffer(format, pcmData);
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    format = codec.getOutputFormat();
                    Logger.d("Format changed to " + format);
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

    private void processInBuffer(MediaFormat source, byte[] bufferChunk) {
        if (bufferChunk.length == 0) {
            return;
        }

        int sourceSampleRate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int sourceChannelCount = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int targetSampleRate = mOutFormat.getSampleRate();
        
        int targetMask = mOutFormat.getChannelMask();
        int targetChannelCount = (targetMask == 16 || targetMask == android.media.AudioFormat.CHANNEL_OUT_MONO) ? 1 : 2;

        // Step 1: Channel downmix (stereo → mono)
        byte[] monoData = bufferChunk;
        if (sourceChannelCount == 2 && targetChannelCount == 1) {
            monoData = stereoToMono(bufferChunk);
        }

        // Step 2: Sample rate conversion (if needed)
        if (sourceSampleRate != targetSampleRate) {
            byte[] resampledData = resampleLinear(monoData, sourceSampleRate, targetSampleRate);
            Logger.d("Pipeline: " + bufferChunk.length + "B(" + sourceSampleRate + "Hz " + sourceChannelCount + "ch) -> " 
                + monoData.length + "B(mono) -> " + resampledData.length + "B(" + targetSampleRate + "Hz)");
            onBufferChunkLoaded(resampledData);
        } else {
            Logger.d("Pipeline: " + bufferChunk.length + "B(" + sourceSampleRate + "Hz " + sourceChannelCount + "ch) -> " 
                + monoData.length + "B(mono, no rate change)");
            onBufferChunkLoaded(monoData);
        }
    }

    /** Manual stereo→mono downmix: average L and R channels (PCM16 interleaved) */
    private byte[] stereoToMono(byte[] stereoData) {
        int numSamples = stereoData.length / 4; // 4 bytes per stereo frame
        byte[] monoData = new byte[numSamples * 2]; // 2 bytes per mono frame

        for (int i = 0; i < numSamples; i++) {
            int L = (stereoData[i * 4] & 0xFF) | (stereoData[i * 4 + 1] << 8);
            int R = (stereoData[i * 4 + 2] & 0xFF) | (stereoData[i * 4 + 3] << 8);
            int mono = (L + R) / 2;
            monoData[i * 2] = (byte)(mono & 0xFF);
            monoData[i * 2 + 1] = (byte)((mono >> 8) & 0xFF);
        }
        return monoData;
    }

    /** Linear interpolation resampler for mono PCM16 data */
    private byte[] resampleLinear(byte[] data, int srcRate, int dstRate) {
        int inputSamples = data.length / 2;
        int outputSamples = (int)((long)inputSamples * dstRate / srcRate);
        byte[] output = new byte[outputSamples * 2];

        double ratio = (double)(srcRate) / dstRate;

        for (int i = 0; i < outputSamples; i++) {
            double srcPos = i * ratio;
            int srcIndex = (int) srcPos;
            double frac = srcPos - srcIndex;

            int s0 = readInt16LE(data, srcIndex);
            int s1 = (srcIndex + 1 < inputSamples) ? readInt16LE(data, srcIndex + 1) : s0;

            int val = (int)(s0 + (s1 - s0) * frac);
            if (val > 32767) val = 32767;
            else if (val < -32768) val = -32768;

            output[i * 2] = (byte)(val & 0xFF);
            output[i * 2 + 1] = (byte)((val >> 8) & 0xFF);
        }
        return output;
    }

    private int readInt16LE(byte[] data, int sampleIndex) {
        int offset = sampleIndex * 2;
        return (data[offset] & 0xFF) | (data[offset + 1] << 8);
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
