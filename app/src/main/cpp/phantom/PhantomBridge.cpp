#include "PhantomBridge.h"
#include <malloc.h>
#include <cstring>
#include <unistd.h>
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
    // After clearing, prepare for new stream
    m_loading_active.store(true);
    m_playing_live.store(true);
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
}

void PhantomBridge::mix_or_copy(char* dst_raw, const char* src_raw, int size, bool mix) {
    if (!mix) {
        memcpy(dst_raw, src_raw, size);
        return;
    }
    if (mAudioFormat == 0x5u) {
        float* dst = reinterpret_cast<float*>(dst_raw);
        const float* src = reinterpret_cast<const float*>(src_raw);
        size_t count = size / sizeof(float);
        for(size_t i = 0; i < count; ++i) dst[i] += src[i];
    } else {
        int16_t* dst = reinterpret_cast<int16_t*>(dst_raw);
        const int16_t* src = reinterpret_cast<const int16_t*>(src_raw);
        size_t count = size / sizeof(int16_t);
        for(size_t i = 0; i < count; ++i) {
            int32_t mixed = dst[i] + src[i];
            if (mixed > 32767) mixed = 32767;
            else if (mixed < -32768) mixed = -32768;
            dst[i] = mixed;
        }
    }
}

bool PhantomBridge::overwrite_buffer(char* buffer, int size) {
    if (!m_playing_live.load()) return false;

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

    m_buffer_read_position.fetch_add(bytes_to_copy);

    if (bytes_to_copy < size && m_buffer_loaded.load()) {
        m_playing_live.store(false);
    }

    return true;
}

void PhantomBridge::on_load_done() {
    m_buffer_loaded.store(true);
}

void PhantomBridge::unload(JNIEnv *env) {
    m_buffer_loaded.store(false);
    m_playing_live.store(false);
    m_loading_active.store(false);
    m_buffer_size = 4 * 1024 * 1024;
    m_buffer_write_position.store(0);
    m_buffer_read_position.store(0);

    if (j_phantomManager != nullptr) {
        jclass j_phantomManagerClass = env->GetObjectClass(j_phantomManager);
        jmethodID method = env->GetMethodID(j_phantomManagerClass, "unload", "()V");
        env->CallVoidMethod(j_phantomManager, method);
    }
}
