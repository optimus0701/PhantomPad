#include <jni.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <linux/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <iomanip>
#include <unwind.h>
#include <sstream>
#include <fstream>
#include <thread>
#include <codecvt>

#include "logging.h"
#include "native_api.h"
#include "KittyMemory/KittyInclude.hpp"

#ifdef __aarch64__
#include "InlineHook/InlineHook.hpp"
#endif

#include "PhantomBridge.h"
#include "MicDSP.h"
#include "hook_compat.h"

struct UnknownArgs {
    char data[1024];
};

jobject j_phantomManager;

JavaVM* JVM;
PhantomBridge* g_phantomBridge;

// Forward declarations
bool attach_env(JNIEnv** env);
void detach_env(bool attached);

HookFunType hook_func;
UnhookFunType unhook_func;

int need_log = 5;
size_t acc_frame_count = 0;
int acc_offset = 0;
// Session counter: incremented each time AudioRecord::stop is called.
// Used to detect a new recording session so format is re-detected.
std::atomic<int> g_record_session{0};
int g_last_format_session = -1;

// Cached format from the last AudioRecord::set call.
// Fallback if WebRTC bypasses set entirely.
std::atomic<int> g_cached_sample_rate{0};
std::atomic<int> g_cached_channel_mask{0};

// Whether to also apply mic boost to file audio playback
std::atomic<bool> g_boost_file{false};

// Professional DSP chain for mic boost (Noise Gate → Compressor → Gain → Limiter)
MicDSP g_mic_dsp;

// Track primary AudioRecord instance — Discord creates multiple instances
// that all call obtainBuffer. We must only feed audio to ONE to prevent
// the ring buffer from being drained at 1.5x speed (960+480 frames per period).
void* g_primary_record = nullptr;

int32_t (*obtainBuffer_backup)(void*, void*, void*, void*, void*);
int32_t  obtainBuffer_hook(void* v0, void* v1, void* v2, void* v3, void* v4) {
    int32_t status = obtainBuffer_backup(v0, v1, v2, v3, v4);
    size_t frameCount = * (size_t*) v1;
    size_t size = * (size_t*) ((uintptr_t) v1 + sizeof(size_t));
    char* raw = * (char**) ((uintptr_t) v1 + sizeof(size_t) * 2);

    // Guard: Discord sometimes returns empty buffers
    if (frameCount == 0 || size == 0 || raw == nullptr) {
        return status;
    }

    // Only feed audio to the PRIMARY AudioRecord instance.
    // Discord creates 2 instances that both call obtainBuffer (960+480 frames per 20ms).
    // Feeding both drains ring buffer at 1.5x speed.
    if (g_primary_record == nullptr) {
        g_primary_record = v0;
        LOGI("Primary AudioRecord locked: %p", v0);
    }
    if (v0 != g_primary_record) {
        // Secondary instance — don't feed audio, let it keep original mic data
        return status;
    }

    size_t frameSize = size / frameCount;

    if (g_phantomBridge != nullptr) {
        // Re-detect format on each new recording session.
        int currentSession = g_record_session.load();
        if (g_last_format_session != currentSession) {
            g_last_format_session = currentSession;

            // Use cached values from set_hook if available (most reliable).
            // Only compute from frameSize as a last resort.
            int cachedRate = g_cached_sample_rate.load();
            int cachedMask = g_cached_channel_mask.load();
            int sampleRate = (cachedRate > 0) ? cachedRate : 48000;
            uint32_t channelMask;

            if (cachedMask > 0) {
                // set_hook already captured the correct values
                channelMask = cachedMask;
            } else {
                // Fallback: compute from frameSize (WebRTC bypass case)
                int audioFmt = g_phantomBridge->getAudioFormat();
                int bytesPerSample = (audioFmt == 0x5u) ? sizeof(float) : sizeof(int16_t);
                int channelCount = (frameSize > 0) ? (int)(frameSize / bytesPerSample) : 1;
                if (channelCount < 1) channelCount = 1;
                if (channelCount > 2) channelCount = 2;
                channelMask = (channelCount == 1) ? 16 : 12;
            }

            JNIEnv* env = nullptr;
            bool attached = attach_env(&env);
            if (env != nullptr) {
                g_phantomBridge->update_audio_format(env, sampleRate, 1 /* ENCODING_PCM_16BIT */, channelMask);
                LOGI("Session %d: format -> %dHz mask=%d (frameSize=%zu, cached=%d)",
                     currentSession, sampleRate, channelMask, frameSize, (cachedMask > 0));
            }
            detach_env(attached);
        }
    }

    // Apply professional DSP chain to raw microphone audio
    if (g_phantomBridge != nullptr) {
        g_mic_dsp.process(raw, size, g_phantomBridge->getAudioFormat());
    }

    // Pass boost info to file playback so it can optionally apply boost to file audio too
    float current_boost = g_mic_dsp.getBoost();
    float file_boost = (g_boost_file.load() && current_boost > 1.0f) ? current_boost : 1.0f;
    if (g_phantomBridge->overwrite_buffer_peek(raw, size, file_boost) && need_log > 0) {
        LOGI("Overwritten data");
    }

    if (need_log > 0) {
        need_log--;
        LOGI("[%zu] Inside obtainBuffer (%zu x %zu = %zu)", acc_frame_count, frameCount, frameSize, size);
    }

    acc_frame_count += frameCount;
    acc_offset += size;
    return status;
}

void (*releaseBuffer_backup)(void* thiz, void* buffer);
void releaseBuffer_hook(void* thiz, void* buffer) {
    if (thiz == g_primary_record && g_phantomBridge != nullptr) {
        size_t frameCount = * (size_t*) buffer;
        
        // Calculate frame size based on detected/cached format
        int cachedMask = g_cached_channel_mask.load();
        int audioFmt = g_phantomBridge->getAudioFormat();
        int bytesPerSample = (audioFmt == 0x5u) ? sizeof(float) : sizeof(int16_t);
        
        // Default to MONO (16) if unknown
        int channelMask = (cachedMask > 0) ? cachedMask : 16;
        int channelCount = (channelMask == 16) ? 1 : ((channelMask == 12) ? 2 : 1);
        int frameSize = channelCount * bytesPerSample;
        
        int bytesConsumed = frameCount * frameSize;
        if (bytesConsumed > 0) {
            g_phantomBridge->advance_read_position(bytesConsumed);
        }
    }
    releaseBuffer_backup(thiz, buffer);
}

bool attach_env(JNIEnv** env) {
    if (JVM == nullptr) return false;
    int getEnvStat = JVM->GetEnv((void**)env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (JVM->AttachCurrentThread(env, nullptr) == 0) {
            return true;
        }
    }
    return false;
}

void detach_env(bool attached) {
    if (attached && JVM != nullptr) {
        JVM->DetachCurrentThread();
    }
}

void (*stop_backup)(void*);
void  stop_hook(void* thiz) {
    stop_backup(thiz);

    if (thiz == g_primary_record) {
        g_primary_record = nullptr;
        LOGI("Primary AudioRecord stopped, clearing lock");
    }

    // Increment session so the next recording re-detects the audio format.
    // IMPORTANT: Do NOT call g_phantomBridge->unload() here!
    // Discord rapidly cycles AudioRecord stop/start multiple times (~6x in 0.3s)
    // during internal reconfiguration. Calling unload() wipes the audio buffer
    // each time, destroying audio that was just loaded.
    // Unload is triggered by the explicit "stop" broadcast command instead.
    g_record_session.fetch_add(1);
    acc_frame_count = 0;
    acc_offset = 0;

    LOGI("AudioRecord::stop() -> session now %d", g_record_session.load());
}

int32_t (*set_backup)(void* thiz, int32_t inputSource, uint32_t sampleRate, uint32_t format,
                      uint32_t channelMask, size_t frameCount, void* callback_ptr, void* callback_refs,
                      uint32_t notificationFrames, bool threadCanCallJava, int32_t sessionId,
                      int transferType, uint32_t flags, uint32_t uid, int32_t pid, void* pAttributes,
                      int selectedDeviceId, int selectedMicDirection, float microphoneFieldDimension,
                      int32_t maxSharedAudioHistoryMs);
int32_t set_hook(void* thiz, int32_t inputSource, uint32_t sampleRate, uint32_t format,
                 uint32_t channelMask, size_t frameCount, void* callback_ptr, void* callback_refs,
                 uint32_t notificationFrames, bool threadCanCallJava, int32_t sessionId,
                 int transferType, uint32_t flags, uint32_t uid, int32_t pid, void* pAttributes,
                 int selectedDeviceId, int selectedMicDirection, float microphoneFieldDimension,
                 int32_t maxSharedAudioHistoryMs) {

    // Unconditional log to verify hook is actually intercepting calls
    LOGI("set_hook CALLED: source=%d, rate=%u, fmt=%u, mask=%u", 
         inputSource, sampleRate, format, channelMask);

    // Force ANY audio source to VOICE_RECOGNITION (6) to disable hardware audio processing
    // VOICE_COMMUNICATION (7) enables Noise Suppression, Echo Cancellation, AGC at HAL level
    // which destroys injected music audio (filters music as noise, creates robot effect)
    int32_t originalSource = inputSource;
    if (inputSource != 6) { // If not already VOICE_RECOGNITION
        inputSource = 6;    // VOICE_RECOGNITION
        LOGI("set_hook FORCING source %d -> %d (bypass hardware audio processing)", 
             originalSource, inputSource);
    }

    int32_t result = set_backup(thiz, inputSource, sampleRate, format, channelMask, frameCount,
                                callback_ptr, callback_refs, notificationFrames, threadCanCallJava,
                                sessionId, transferType, flags, uid, pid, pAttributes,
                                selectedDeviceId, selectedMicDirection, microphoneFieldDimension,
                                maxSharedAudioHistoryMs);

    LOGI("AudioRecord::set -> SampleRate: %d, Format: %d, ChannelMask: %d, Source: %d->%d, Result: %d", 
         sampleRate, format, channelMask, originalSource, inputSource, result);

    // Cache these values so obtainBuffer_hook can use them instead of guessing
    g_cached_sample_rate.store((int)sampleRate);
    g_cached_channel_mask.store((int)channelMask);

    // Update DSP chain sample rate for correct filter coefficients
    g_mic_dsp.setSampleRate((float)sampleRate);

    JNIEnv* env = nullptr;
    bool attached = attach_env(&env);
    
    if (env != nullptr && g_phantomBridge != nullptr) {
        g_phantomBridge->update_audio_format(env, sampleRate, format, channelMask);
        g_phantomBridge->load(env);
    }
    
    detach_env(attached);

    return result;
}

void on_library_loaded(const char *name, void *handle) {
//    LOGI("Library Loaded %s", name);
}


extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *jvm, void*) {
    JNIEnv *env = nullptr;
    jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    LOGI("JNI_OnLoad");

    JVM = jvm;

    return JNI_VERSION_1_6;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    hook_func = entries->hook_func;
    unhook_func = entries->unhook_func;
    return on_library_loaded;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeSetPaused(JNIEnv *env, jobject thiz, jboolean paused) {
    if (g_phantomBridge != nullptr) {
        g_phantomBridge->set_paused(paused);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeHook(JNIEnv *env, jobject thiz) {
    j_phantomManager = env->NewGlobalRef(thiz);
    g_phantomBridge = new PhantomBridge(j_phantomManager);

    LOGI("Doing c++ hook");

    ElfScanner g_libTargetELF = ElfScanner::createWithPath(HookCompat::get_library_name());

    uintptr_t set_symbol = HookCompat::get_set_symbol(g_libTargetELF);
    LOGI("AudioRecord::set at %p", (void*) set_symbol);
    uintptr_t obtainBuffer_symbol = HookCompat::get_obtainBuffer_symbol(g_libTargetELF);
    LOGI("AudioRecord::obtainBuffer at %p", (void*) obtainBuffer_symbol);
    uintptr_t stop_symbol = HookCompat::get_stop_symbol(g_libTargetELF);
    LOGI("AudioRecord::stop at %p", (void*) stop_symbol);
    uintptr_t releaseBuffer_symbol = HookCompat::get_releaseBuffer_symbol(g_libTargetELF);
    LOGI("AudioRecord::releaseBuffer at %p", (void*) releaseBuffer_symbol);

    hook_func((void*) obtainBuffer_symbol, (void*) obtainBuffer_hook, (void**) &obtainBuffer_backup);
    if (releaseBuffer_symbol != 0) {
        hook_func((void*) releaseBuffer_symbol, (void*) releaseBuffer_hook, (void**) &releaseBuffer_backup);
    }
    hook_func((void*) stop_symbol, (void*) stop_hook, (void**) &stop_backup);
    hook_func((void*) set_symbol, (void*) set_hook, (void**) &set_backup);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeClearBuffer(JNIEnv *env, jobject thiz) {
    if (g_phantomBridge != nullptr) {
        g_phantomBridge->nativeClearBuffer();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeSetMixAudio(JNIEnv *env, jobject thiz, jboolean mix_audio) {
    if (g_phantomBridge != nullptr) {
        g_phantomBridge->set_mix_audio(mix_audio);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeSetMicBoost(JNIEnv *env, jobject thiz, jfloat boost) {
    g_mic_dsp.setBoost(boost);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeSetBoostFile(JNIEnv *env, jobject thiz, jboolean enabled) {
    g_boost_file.store(enabled);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeSetGateThreshold(JNIEnv *env, jobject thiz, jfloat db) {
    g_mic_dsp.setGateThresholdDb(db);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeSetCompThreshold(JNIEnv *env, jobject thiz, jfloat db) {
    g_mic_dsp.setCompThresholdDb(db);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_PhantomManager_nativeSetCompRatio(JNIEnv *env, jobject thiz, jfloat ratio) {
    g_mic_dsp.setCompRatio(ratio);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_audio_AudioMaster_onBufferChunkLoaded(JNIEnv *env, jobject thiz,
                                                                jbyteArray buffer_chunk) {
    jbyte* buffer = env->GetByteArrayElements(buffer_chunk, nullptr);
    int size = env->GetArrayLength(buffer_chunk);

    g_phantomBridge->on_buffer_chunk_loaded(buffer, size);

    env->ReleaseByteArrayElements(buffer_chunk, buffer, 0);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_audio_AudioMaster_onLoadDone(JNIEnv *env, jobject thiz) {
    g_phantomBridge->on_load_done();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_optimus0701_phantompad_audio_AudioMaster_nativeStartLoading(JNIEnv *env, jobject thiz) {
    g_phantomBridge->start_loading();
}
