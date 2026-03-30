#include "PhantomBridge.h"
#include <malloc.h>
#include <cstring>
#include <unistd.h>
#include <chrono>
#include <cmath>
#include "../logging.h"

PhantomBridge* PhantomBridge::s_instance = nullptr;

PhantomBridge::PhantomBridge(jobject j_phantomManager) : j_phantomManager(j_phantomManager) {
    s_instance = this;
    m_buffer = (jbyte*)malloc(m_buffer_size); 
}

int audioFormatToJava(int audioFormat) {
    if (audioFormat == 0x1u) {
        // ENCODING_PCM_16BIT
        return 2;
    } else if (audioFormat == 0x5u) {
        // ENCODING_PCM_FLOAT
        return 4;
    }
    // Default fallback
    return 2;
}

void PhantomBridge::update_audio_format(JNIEnv* env, int sampleRate, int audioFormat, int channelMask) {
    jclass j_phantomManagerClass = env->GetObjectClass(j_phantomManager);
    jmethodID method = env->GetMethodID(j_phantomManagerClass, "updateAudioFormat", "(III)V");
    env->CallVoidMethod(j_phantomManager, method, sampleRate, channelMask, audioFormatToJava(audioFormat));

    mAudioFormat = audioFormat;
}

void PhantomBridge::load(JNIEnv *env) {
    jclass j_phantomManagerClass = env->GetObjectClass(j_phantomManager);
    jmethodID method = env->GetMethodID(j_phantomManagerClass, "load", "()V");
    env->CallVoidMethod(j_phantomManager, method);
}

void PhantomBridge::nativeClearBuffer() {
    m_playing_live.store(false);
    m_loading_active.store(false); // Stop any pending writes
    m_buffer_loaded.store(false);
    m_buffer_read_position.store(0);
    m_buffer_write_position.store(0);
}

void PhantomBridge::set_mix_audio(bool mix) {
    m_mix_audio = mix;
}

void PhantomBridge::start_loading() {
    nativeClearBuffer();
    // Prepare for streaming — playback will begin once MIN_BUFFER_BEFORE_PLAY bytes
    // have been written (see on_buffer_chunk_loaded). This avoids both:
    //   - Initial underrun (starting too early with an empty buffer)
    //   - Deadlock on long files (waiting for the entire file before reading)
    m_loading_active.store(true);
}

void PhantomBridge::on_buffer_chunk_loaded(jbyte *buffer, jsize size) {
    if (!m_loading_active.load()) return;

    if (m_buffer == nullptr) return;

    int required_size = size;
    if (mAudioFormat == 0x5u) {
        required_size = (size / sizeof(int16_t)) * sizeof(float);
    }

    while (m_loading_active.load()) {
        long long available_space = m_buffer_size - (m_buffer_write_position.load() - m_buffer_read_position.load());
        if (available_space >= required_size) {
            break;
        }
        usleep(5000); // 5ms wait if buffer is full
    }

    if (!m_loading_active.load()) return;

    if (mAudioFormat == 0x5u) {
        int16_t* src16 = reinterpret_cast<int16_t*>(buffer);
        size_t n_samples = size / sizeof(int16_t);
        long long write_pos = m_buffer_write_position.load();
        
        for (size_t i = 0; i < n_samples; ++i) {
            float val = src16[i] / 32768.0f;
            int pos = write_pos % (long long)m_buffer_size;
            memcpy((char*)m_buffer + pos, &val, sizeof(float));
            write_pos += sizeof(float);
        }
        m_buffer_write_position.store(write_pos);
    } else {
        long long write_pos = m_buffer_write_position.load();
        int pos = write_pos % (long long)m_buffer_size;
        
        if (pos + size <= m_buffer_size) {
            memcpy((char*)m_buffer + pos, buffer, size);
        } else {
            int first_part = m_buffer_size - pos;
            memcpy((char*)m_buffer + pos, buffer, first_part);
            memcpy((char*)m_buffer, buffer + first_part, size - first_part);
        }
        m_buffer_write_position.fetch_add(size);
    }

    // Start playback once we have enough buffered data to avoid initial underrun.
    // This threshold prevents both:
    //   - Starting too early (broken audio at start)
    //   - Waiting too long / deadlocking (for files larger than ring buffer)
    if (!m_playing_live.load() && m_buffer_write_position.load() >= MIN_BUFFER_BEFORE_PLAY) {
        m_playing_live.store(true);
        LOGI("Streaming started: %lld bytes buffered", m_buffer_write_position.load());
    }
}

void PhantomBridge::mix_or_copy(char* dst_raw, const char* src_raw, int size, bool mix) {
    // Direct copy when not mixing — no per-sample processing to avoid artifacts
    if (!mix) {
        memcpy(dst_raw, src_raw, size);
        return;
    }

    // Mix mode: add our audio on top of mic data with Soft Clipping (Audio Limiter)
    // Threshold: 0.90 (starts compressing softly above 90% volume to prevent harsh digital clipping)
    const float THRESHOLD = 0.90f;

    if (mAudioFormat == 0x5u) {
        float* dst = reinterpret_cast<float*>(dst_raw);
        const float* src = reinterpret_cast<const float*>(src_raw);
        size_t count = size / sizeof(float);
        for(size_t i = 0; i < count; ++i) {
            float val = src[i] + dst[i];
            float abs_val = std::abs(val);
            if (abs_val > THRESHOLD) {
                // Mathematical soft clipper: asymptotic curve approaching 1.0
                float over = abs_val - THRESHOLD;
                val = (val > 0 ? 1.0f : -1.0f) * (THRESHOLD + over / (1.0f + over * 2.0f));
            }
            dst[i] = val;
        }
    } else {
        int16_t* dst = reinterpret_cast<int16_t*>(dst_raw);
        const int16_t* src = reinterpret_cast<const int16_t*>(src_raw);
        size_t count = size / sizeof(int16_t);
        for(size_t i = 0; i < count; ++i) {
            float val = ((float)src[i] + (float)dst[i]) / 32768.0f;
            float abs_val = std::abs(val);
            if (abs_val > THRESHOLD) {
                float over = abs_val - THRESHOLD;
                val = (val > 0.0f ? 1.0f : -1.0f) * (THRESHOLD + over / (1.0f + over * 2.0f));
            }
            int32_t final_val = (int32_t)(val * 32767.0f);
            if (final_val > 32767) final_val = 32767;
            else if (final_val < -32768) final_val = -32768;
            dst[i] = (int16_t)final_val;
        }
    }
}

bool PhantomBridge::overwrite_buffer_peek(char* buffer, int size) {
    if (!m_playing_live.load() || m_paused.load()) return false;

    long long read_pos = m_buffer_read_position.load();
    long long write_pos = m_buffer_write_position.load();
    long long available = write_pos - read_pos;
    
    if (available <= 0) {
        if (m_buffer_loaded.load()) {
            m_playing_live.store(false);
        }
        return false;
    }

    int bytes_to_copy = size;
    if (bytes_to_copy > available) {
        bytes_to_copy = available;
    }

    int frame_size = (mAudioFormat == 0x5u) ? sizeof(float) : sizeof(int16_t);
    bytes_to_copy -= (bytes_to_copy % frame_size);

    if (bytes_to_copy <= 0) return false;

    int pos = read_pos % (long long)m_buffer_size;
    if (pos + bytes_to_copy <= m_buffer_size) {
        mix_or_copy(buffer, (char*)m_buffer + pos, bytes_to_copy, m_mix_audio);
    } else {
        int first_part = m_buffer_size - pos;
        mix_or_copy(buffer, (char*)m_buffer + pos, first_part, m_mix_audio);
        mix_or_copy(buffer + first_part, (char*)m_buffer, bytes_to_copy - first_part, m_mix_audio);
    }

    // REMOVED: m_buffer_read_position.fetch_add(bytes_to_copy);
    // Position tracking is now handled by advance_read_position() called from releaseBuffer_hook.

    // If we didn't have enough data to fill the entire output buffer,
    // zero the remaining bytes so the app hears silence instead of stale mic data.
    if (bytes_to_copy < size) {
        memset(buffer + bytes_to_copy, 0, size - bytes_to_copy);
        if (m_buffer_loaded.load()) {
            m_playing_live.store(false);
        }
    }

    return true;
}

void PhantomBridge::advance_read_position(int size) {
    if (size <= 0 || m_paused.load()) return;

    // Diagnostic: track consumption rate exactly based on released bytes
    static long long total_consumed = 0;
    static auto start_time = std::chrono::steady_clock::now();
    total_consumed += size;
    if (total_consumed >= 96000) { // Log every ~1 second of audio (48kHz mono PCM16)
        auto now = std::chrono::steady_clock::now();
        double elapsed_ms = std::chrono::duration<double, std::milli>(now - start_time).count();
        LOGI("CONSUMPTION: %lld bytes in %.0fms (expected ~1000ms for 96000B)", total_consumed, elapsed_ms);
        total_consumed = 0;
        start_time = now;
    }

    m_buffer_read_position.fetch_add(size);
}

void PhantomBridge::on_load_done() {
    m_buffer_loaded.store(true);
    // If the file was short enough that we haven't started playback yet
    // (less than MIN_BUFFER_BEFORE_PLAY), start it now.
    if (!m_playing_live.load()) {
        m_playing_live.store(true);
    }
}

void PhantomBridge::unload(JNIEnv *env) {
    m_buffer_loaded.store(false);
    m_playing_live.store(false);
    m_loading_active.store(false);
    m_paused.store(false);
    m_buffer_size = 4 * 1024 * 1024;
    m_buffer_write_position.store(0);
    m_buffer_read_position.store(0);

    if (j_phantomManager != nullptr) {
        jclass j_phantomManagerClass = env->GetObjectClass(j_phantomManager);
        jmethodID method = env->GetMethodID(j_phantomManagerClass, "unload", "()V");
        env->CallVoidMethod(j_phantomManager, method);
    }
}

void PhantomBridge::set_paused(bool paused) {
    m_paused.store(paused);
}
