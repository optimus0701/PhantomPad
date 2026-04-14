//
// Created by amin on 7/23/24.
//

#ifndef PHANTOMMIC_PHANTOMBRIDGE_H
#define PHANTOMMIC_PHANTOMBRIDGE_H

#include <jni.h>
#include <atomic>

class PhantomBridge {
public:
    PhantomBridge(jobject j_phantomManager);

    void update_audio_format(JNIEnv* env, int sampleRate, int audioFormat, int channelMask);

    void load(JNIEnv* env);
    void start_loading();

    void on_buffer_chunk_loaded(jbyte* buffer, jsize size);

    bool overwrite_buffer_peek(char* buffer, int size, float file_boost = 1.0f);
    void advance_read_position(int size);

    void on_load_done();

    void unload(JNIEnv *env);

    void nativeClearBuffer();

    void set_mix_audio(bool mix);
    void set_paused(bool paused);

    int getAudioFormat() const { return mAudioFormat; }

private:
    void mix_or_copy(char* dst_raw, const char* src_raw, int size, bool mix);

    jobject j_phantomManager;

    std::atomic<bool> m_playing_live{false};
    std::atomic<bool> m_buffer_loaded{false};
    std::atomic<bool> m_loading_active{false};
    std::atomic<bool> m_paused{false};
    int m_buffer_size = 4 * 1024 * 1024; // 4MB
    std::atomic<long long> m_buffer_write_position{0};
    std::atomic<long long> m_buffer_read_position{0};
    jbyte* m_buffer = nullptr;

    int mAudioFormat = 0x1;
    bool m_mix_audio = false;
    static constexpr int MIN_BUFFER_BEFORE_PLAY = 64 * 1024; // 64KB threshold before starting playback

    static PhantomBridge* s_instance;
public:
    static PhantomBridge* get() { return s_instance; }
    static void set(PhantomBridge* inst) { s_instance = inst; }
};

#endif //PHANTOMMIC_PHANTOMBRIDGE_H
