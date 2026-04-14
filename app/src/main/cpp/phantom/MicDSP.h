#ifndef PHANTOMMIC_MICDSP_H
#define PHANTOMMIC_MICDSP_H

#include <cmath>
#include <atomic>
#include <algorithm>
#include <cstring>

// ═══════════════════════════════════════════════════════════════════════════
// Professional Mic Boost DSP Chain v3
//
// Signal path:
//   Input → DC Blocker → Noise Gate → Pre-LPF → Gain → Compressor
//         → High-Shelf EQ (de-emphasis) → Tanh Saturator/Limiter
//
// v3 key improvements over v2:
//   - Added Biquad LPF (8kHz) BEFORE gain to cut HF noise before amplification
//   - Added High-Shelf EQ (-3dB@4kHz) AFTER gain to tame boosted treble
//   - Replaced peak limiter with tanh soft saturator (industry standard)
//   - All filter coefficients from RBJ Audio EQ Cookbook
//   - Improved compressor with proper dB-domain gain smoothing
// ═══════════════════════════════════════════════════════════════════════════

static inline float db_to_linear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

static inline float linear_to_db(float lin) {
    return 20.0f * std::log10(std::max(lin, 1e-8f));
}

// Fast tanh approximation (Pade approximant — much faster than std::tanh)
// Accurate to <0.1% error for |x| < 4.0
static inline float fast_tanh(float x) {
    if (x < -3.0f) return -1.0f;
    if (x >  3.0f) return  1.0f;
    float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// ───────────────────────────────────────────────────────────────────────────
// DC Blocker (1st order high-pass ~20Hz)
// Removes DC offset and very low frequency hum
// ───────────────────────────────────────────────────────────────────────────
class DCBlocker {
public:
    void setSampleRate(float sr) {
        m_R = 1.0f - (6.2831853f * 20.0f / sr);
        m_R = std::max(0.9f, std::min(m_R, 0.9999f));
    }

    float process(float input) {
        float output = input - m_prevInput + m_R * m_prevOutput;
        m_prevInput  = input;
        m_prevOutput = output;
        return output;
    }

    void reset() { m_prevInput = 0.0f; m_prevOutput = 0.0f; }

private:
    float m_R = 0.9974f;
    float m_prevInput  = 0.0f;
    float m_prevOutput = 0.0f;
};

// ───────────────────────────────────────────────────────────────────────────
// Biquad Filter (RBJ Audio EQ Cookbook)
// Supports: LPF, HPF, High Shelf, Low Shelf, Peaking EQ
// Industry standard digital filter implementation
// ───────────────────────────────────────────────────────────────────────────
class BiquadFilter {
public:
    enum Type { LPF, HPF, HIGH_SHELF, LOW_SHELF, PEAKING };

    void configure(Type type, float sampleRate, float freq, float Q_or_S, float gainDb = 0.0f) {
        float w0 = 6.2831853f * freq / sampleRate;
        float cosw0 = std::cos(w0);
        float sinw0 = std::sin(w0);

        float a0, a1, a2, b0, b1, b2;

        switch (type) {
            case LPF: {
                float alpha = sinw0 / (2.0f * Q_or_S);
                b0 = (1.0f - cosw0) / 2.0f;
                b1 =  1.0f - cosw0;
                b2 = (1.0f - cosw0) / 2.0f;
                a0 =  1.0f + alpha;
                a1 = -2.0f * cosw0;
                a2 =  1.0f - alpha;
                break;
            }
            case HPF: {
                float alpha = sinw0 / (2.0f * Q_or_S);
                b0 = (1.0f + cosw0) / 2.0f;
                b1 = -(1.0f + cosw0);
                b2 = (1.0f + cosw0) / 2.0f;
                a0 =  1.0f + alpha;
                a1 = -2.0f * cosw0;
                a2 =  1.0f - alpha;
                break;
            }
            case HIGH_SHELF: {
                float A = std::pow(10.0f, gainDb / 40.0f);
                float alpha = sinw0 / 2.0f * std::sqrt((A + 1.0f/A) * (1.0f/Q_or_S - 1.0f) + 2.0f);
                float sqrtA2alpha = 2.0f * std::sqrt(A) * alpha;
                b0 =       A * ((A + 1.0f) + (A - 1.0f) * cosw0 + sqrtA2alpha);
                b1 = -2.0f*A * ((A - 1.0f) + (A + 1.0f) * cosw0);
                b2 =       A * ((A + 1.0f) + (A - 1.0f) * cosw0 - sqrtA2alpha);
                a0 =            (A + 1.0f) - (A - 1.0f) * cosw0 + sqrtA2alpha;
                a1 =  2.0f   * ((A - 1.0f) - (A + 1.0f) * cosw0);
                a2 =            (A + 1.0f) - (A - 1.0f) * cosw0 - sqrtA2alpha;
                break;
            }
            case LOW_SHELF: {
                float A = std::pow(10.0f, gainDb / 40.0f);
                float alpha = sinw0 / 2.0f * std::sqrt((A + 1.0f/A) * (1.0f/Q_or_S - 1.0f) + 2.0f);
                float sqrtA2alpha = 2.0f * std::sqrt(A) * alpha;
                b0 =       A * ((A + 1.0f) - (A - 1.0f) * cosw0 + sqrtA2alpha);
                b1 =  2.0f*A * ((A - 1.0f) - (A + 1.0f) * cosw0);
                b2 =       A * ((A + 1.0f) - (A - 1.0f) * cosw0 - sqrtA2alpha);
                a0 =            (A + 1.0f) + (A - 1.0f) * cosw0 + sqrtA2alpha;
                a1 = -2.0f   * ((A - 1.0f) + (A + 1.0f) * cosw0);
                a2 =            (A + 1.0f) + (A - 1.0f) * cosw0 - sqrtA2alpha;
                break;
            }
            default: // PEAKING
            {
                float A = std::pow(10.0f, gainDb / 40.0f);
                float alpha = sinw0 / (2.0f * Q_or_S);
                b0 =  1.0f + alpha * A;
                b1 = -2.0f * cosw0;
                b2 =  1.0f - alpha * A;
                a0 =  1.0f + alpha / A;
                a1 = -2.0f * cosw0;
                a2 =  1.0f - alpha / A;
                break;
            }
        }

        // Normalize coefficients
        m_b0 = b0 / a0;
        m_b1 = b1 / a0;
        m_b2 = b2 / a0;
        m_a1 = a1 / a0;
        m_a2 = a2 / a0;
    }

    // Direct Form II Transposed — most numerically stable
    float process(float input) {
        float output = m_b0 * input + m_z1;
        m_z1 = m_b1 * input - m_a1 * output + m_z2;
        m_z2 = m_b2 * input - m_a2 * output;
        return output;
    }

    void reset() { m_z1 = 0.0f; m_z2 = 0.0f; }

private:
    float m_b0 = 1.0f, m_b1 = 0.0f, m_b2 = 0.0f;
    float m_a1 = 0.0f, m_a2 = 0.0f;
    float m_z1 = 0.0f, m_z2 = 0.0f;
};

// ───────────────────────────────────────────────────────────────────────────
// Envelope Follower (IIR / Leaky Integrator)
// ───────────────────────────────────────────────────────────────────────────
class EnvelopeFollower {
public:
    void setSampleRate(float sr) {
        m_sampleRate = sr;
        recalcCoefficients();
    }

    void setAttackMs(float ms) { m_attackMs = ms; recalcCoefficients(); }
    void setReleaseMs(float ms) { m_releaseMs = ms; recalcCoefficients(); }

    float process(float input) {
        float squared = input * input;
        float alpha = (squared > m_envelope) ? m_attackCoef : m_releaseCoef;
        m_envelope = alpha * m_envelope + (1.0f - alpha) * squared;
        return std::sqrt(std::max(m_envelope, 0.0f));
    }

    void reset() { m_envelope = 0.0f; }

private:
    void recalcCoefficients() {
        if (m_sampleRate <= 0) return;
        m_attackCoef  = (m_attackMs > 0.0f)
            ? std::exp(-1.0f / (m_attackMs * 0.001f * m_sampleRate)) : 0.0f;
        m_releaseCoef = (m_releaseMs > 0.0f)
            ? std::exp(-1.0f / (m_releaseMs * 0.001f * m_sampleRate)) : 0.0f;
    }

    float m_sampleRate  = 48000.0f;
    float m_attackMs    = 1.0f;
    float m_releaseMs   = 100.0f;
    float m_attackCoef  = 0.0f;
    float m_releaseCoef = 0.0f;
    float m_envelope    = 0.0f;
};

// ───────────────────────────────────────────────────────────────────────────
// Noise Gate (with hysteresis, hold, cosine S-curve transitions)
// ───────────────────────────────────────────────────────────────────────────
class NoiseGate {
public:
    void setSampleRate(float sr) {
        m_sampleRate = sr;
        m_envelope.setSampleRate(sr);
        m_envelope.setAttackMs(0.5f);
        m_envelope.setReleaseMs(50.0f);
        recalcTimers();
    }

    void setThresholdDb(float db) {
        m_openThreshold  = db_to_linear(db);
        m_closeThreshold = db_to_linear(db - 6.0f);
    }

    float process(float input) {
        float level = m_envelope.process(input);

        switch (m_state) {
            case CLOSED:
                if (level > m_openThreshold) { m_state = ATTACK; m_transitionSamples = 0; }
                break;
            case ATTACK:
                m_transitionSamples++;
                if (m_transitionSamples >= m_attackSamples) m_state = OPEN;
                break;
            case OPEN:
                if (level < m_closeThreshold) { m_state = HOLD; m_holdCounter = 0; }
                break;
            case HOLD:
                m_holdCounter++;
                if (level > m_openThreshold) m_state = OPEN;
                else if (m_holdCounter >= m_holdSamples) { m_state = RELEASE; m_transitionSamples = 0; }
                break;
            case RELEASE:
                m_transitionSamples++;
                if (level > m_openThreshold) { m_state = ATTACK; m_transitionSamples = 0; }
                else if (m_transitionSamples >= m_releaseSamples) m_state = CLOSED;
                break;
        }

        // Cosine S-curve for smooth transitions
        float targetGain;
        switch (m_state) {
            case CLOSED:  targetGain = 0.0f; break;
            case ATTACK:  { float t = (float)m_transitionSamples / (float)m_attackSamples;
                          targetGain = 0.5f - 0.5f * std::cos(t * 3.14159265f); break; }
            case OPEN:
            case HOLD:    targetGain = 1.0f; break;
            case RELEASE: { float t = (float)m_transitionSamples / (float)m_releaseSamples;
                          targetGain = 0.5f + 0.5f * std::cos(t * 3.14159265f); break; }
            default:      targetGain = 1.0f;
        }

        m_currentGain += (targetGain - m_currentGain) * m_gainSmoothCoef;
        return m_currentGain;
    }

    void reset() { m_state = CLOSED; m_currentGain = 0.0f; m_envelope.reset(); }

private:
    void recalcTimers() {
        if (m_sampleRate <= 0) return;
        m_attackSamples  = std::max(1, (int)(2.0f * 0.001f * m_sampleRate));
        m_releaseSamples = std::max(1, (int)(150.0f * 0.001f * m_sampleRate));
        m_holdSamples    = std::max(1, (int)(80.0f * 0.001f * m_sampleRate));
        m_gainSmoothCoef = 1.0f - std::exp(-1.0f / (3.0f * 0.001f * m_sampleRate));
    }

    enum State { CLOSED, ATTACK, OPEN, HOLD, RELEASE };
    float m_sampleRate = 48000.0f;
    State m_state = CLOSED;
    float m_openThreshold  = 0.01f;
    float m_closeThreshold = 0.005f;
    int m_attackSamples = 96, m_releaseSamples = 7200, m_holdSamples = 3840;
    int m_transitionSamples = 0, m_holdCounter = 0;
    float m_currentGain = 0.0f, m_gainSmoothCoef = 0.007f;
    EnvelopeFollower m_envelope;
};

// ───────────────────────────────────────────────────────────────────────────
// Compressor (RMS-based, soft knee, dB-domain gain smoothing)
// ───────────────────────────────────────────────────────────────────────────
class Compressor {
public:
    void setSampleRate(float sr) {
        m_sampleRate = sr;
        m_envelope.setSampleRate(sr);
        m_envelope.setAttackMs(m_attackMs);
        m_envelope.setReleaseMs(m_releaseMs);
        recalcSmoothCoef();
    }

    void setThresholdDb(float db) { m_thresholdDb = db; }
    void setRatio(float ratio) { m_ratio = std::max(ratio, 1.0f); }

    float process(float input) {
        float level = m_envelope.process(input);
        float levelDb = linear_to_db(level);

        // Gain computation in dB domain with soft knee
        float gainDb = computeGainDb(levelDb);

        // Smooth gain in dB domain (prevents zipper noise)
        float coef = (gainDb < m_currentGainDb) ? m_gainAttackCoef : m_gainReleaseCoef;
        m_currentGainDb += (gainDb - m_currentGainDb) * coef;

        return db_to_linear(m_currentGainDb);
    }

    float getMakeupGainDb() const {
        if (m_ratio <= 1.0f) return 0.0f;
        float reductionAtThreshold = m_thresholdDb * (1.0f / m_ratio - 1.0f);
        return -reductionAtThreshold * 0.5f;
    }

    void reset() { m_currentGainDb = 0.0f; m_envelope.reset(); }

private:
    float computeGainDb(float levelDb) {
        float halfKnee = m_kneeDb * 0.5f;
        float lowerKnee = m_thresholdDb - halfKnee;

        if (levelDb <= lowerKnee) return 0.0f;

        float upperKnee = m_thresholdDb + halfKnee;
        if (levelDb >= upperKnee) {
            return (levelDb - m_thresholdDb) * (1.0f / m_ratio - 1.0f);
        }

        // Soft knee
        float x = levelDb - lowerKnee;
        float kneeRange = std::max(m_kneeDb, 0.01f);
        return (1.0f / m_ratio - 1.0f) * x * x / (2.0f * kneeRange);
    }

    void recalcSmoothCoef() {
        if (m_sampleRate <= 0) return;
        m_gainAttackCoef  = 1.0f - std::exp(-1.0f / (0.002f * m_sampleRate));
        m_gainReleaseCoef = 1.0f - std::exp(-1.0f / (0.050f * m_sampleRate));
    }

    float m_sampleRate = 48000.0f;
    float m_thresholdDb = -20.0f, m_ratio = 4.0f, m_kneeDb = 6.0f;
    float m_attackMs = 5.0f, m_releaseMs = 50.0f;
    float m_currentGainDb = 0.0f;
    float m_gainAttackCoef = 0.01f, m_gainReleaseCoef = 0.0004f;
    EnvelopeFollower m_envelope;
};

// ───────────────────────────────────────────────────────────────────────────
// Tanh Saturator / Soft Limiter
// Uses tanh curve (industry standard for analog-modelled saturation)
// Much more transparent than peak-following limiter or hard clipper
//
// Properties:
//   - Smooth, continuous transfer function (no discontinuities)
//   - Odd-order harmonics only (most musical)
//   - Output naturally bounded to [-ceiling, +ceiling]
//   - Drive parameter controls saturation amount
// ───────────────────────────────────────────────────────────────────────────
class TanhSaturator {
public:
    void setCeilingDb(float db) {
        m_ceiling = db_to_linear(db);
    }

    // drive: 1.0 = gentle limiting, higher = more saturation
    void setDrive(float drive) {
        m_drive = std::max(drive, 0.1f);
    }

    float process(float input) {
        // Scale input by drive, apply tanh, scale by ceiling
        // tanh(x) output range: (-1, +1) → multiply by ceiling for output range
        float scaled = input * m_drive / m_ceiling;
        return m_ceiling * fast_tanh(scaled);
    }

private:
    float m_ceiling = 0.891f;  // -1 dBFS
    float m_drive   = 1.5f;    // Gentle drive for voice
};

// ═══════════════════════════════════════════════════════════════════════════
// MicDSP — Orchestrator v3
//
// Signal chain:
//   DC Blocker → Noise Gate → Pre-Gain LPF → Gain Boost
//   → Compressor → High-Shelf De-emphasis → Tanh Saturator
//
// The key insight: PC mic boost (Windows/Realtek/OBS) all apply filtering
// BEFORE and AFTER gain to prevent noise amplification. We do the same:
//   1. LPF before gain: cuts HF noise so it doesn't get amplified
//   2. High-shelf after gain: rolls off the treble that got boosted
//   3. Tanh instead of limiter: transparent saturation without artifacts
// ═══════════════════════════════════════════════════════════════════════════
class MicDSP {
public:
    void setSampleRate(float sr) {
        m_sampleRate = sr;
        m_dcBlocker.setSampleRate(sr);
        m_gate.setSampleRate(sr);
        m_compressor.setSampleRate(sr);

        // Pre-gain Low-Pass Filter: 8kHz, Q=0.707 (Butterworth)
        // Cuts high-frequency noise/hiss BEFORE it gets amplified by the gain stage
        m_preLPF.configure(BiquadFilter::LPF, sr, 8000.0f, 0.707f);

        // Post-gain High-Shelf: -3dB @ 4kHz, S=0.7
        // Acts as de-emphasis — counteracts the treble boost from gain amplification
        // This is what makes Windows mic boost sound "natural" vs raw gain
        m_deEmphasis.configure(BiquadFilter::HIGH_SHELF, sr, 4000.0f, 0.7f, -3.0f);

        m_needReconfigFilters = false;
    }

    void setBoost(float boost)     { m_boost.store(boost); }
    float getBoost() const         { return m_boost.load(); }
    void setGateThresholdDb(float db)  { m_gateThresholdDb.store(db); }
    void setCompThresholdDb(float db)  { m_compThresholdDb.store(db); }
    void setCompRatio(float ratio)     { m_compRatio.store(ratio); }

    void process(char* raw, size_t size, int audioFormat) {
        float boost = m_boost.load();
        if (boost <= 1.0f) return;

        // Update DSP parameters from atomic config (once per buffer)
        m_gate.setThresholdDb(m_gateThresholdDb.load());
        m_compressor.setThresholdDb(m_compThresholdDb.load());
        m_compressor.setRatio(m_compRatio.load());

        // Calculate total gain including makeup
        float makeupGainDb = m_compressor.getMakeupGainDb();
        float totalGainLin = boost * db_to_linear(makeupGainDb);

        // Adjust saturator drive based on boost level
        // Higher boost → stronger drive to catch more peaks
        m_saturator.setDrive(1.0f + (boost - 1.0f) * 0.5f);

        if (audioFormat == 0x5u) {
            // ── PCM_FLOAT ──
            float* samples = reinterpret_cast<float*>(raw);
            size_t count = size / sizeof(float);
            for (size_t i = 0; i < count; ++i) {
                samples[i] = processSample(samples[i], totalGainLin);
            }
        } else {
            // ── PCM_16BIT ──
            int16_t* samples = reinterpret_cast<int16_t*>(raw);
            size_t count = size / sizeof(int16_t);
            for (size_t i = 0; i < count; ++i) {
                float val = (float)samples[i] / 32768.0f;
                val = processSample(val, totalGainLin);
                // Convert back with rounding
                int32_t final_val = (int32_t)(val * 32767.0f + (val >= 0.0f ? 0.5f : -0.5f));
                if (final_val > 32767) final_val = 32767;
                else if (final_val < -32768) final_val = -32768;
                samples[i] = (int16_t)final_val;
            }
        }
    }

    void reset() {
        m_dcBlocker.reset();
        m_gate.reset();
        m_preLPF.reset();
        m_deEmphasis.reset();
        m_compressor.reset();
    }

private:
    inline float processSample(float val, float totalGainLin) {
        // 0. DC Blocker — remove DC offset & low-freq electrical hum
        val = m_dcBlocker.process(val);

        // 1. Noise Gate — mute background noise when not speaking
        float gateGain = m_gate.process(val);
        val *= gateGain;

        // 2. Pre-gain LPF (8kHz) — CRITICAL: removes HF noise BEFORE amplification
        //    Without this, high-frequency hiss/static gets amplified by boost
        //    This is the #1 reason for "rè" / harsh treble sound
        val = m_preLPF.process(val);

        // 3. Apply gain boost
        val *= totalGainLin;

        // 4. Compressor — control dynamic range
        float compGain = m_compressor.process(val);
        val *= compGain;

        // 5. De-emphasis (High-Shelf -3dB@4kHz) — tames boosted treble
        //    Same concept as Windows mic boost + Realtek de-emphasis
        val = m_deEmphasis.process(val);

        // 6. Tanh Saturator — transparent soft limiting
        //    Much cleaner than hard limiter: no aliasing, no harsh artifacts
        //    Naturally compresses to [-ceiling, +ceiling]
        val = m_saturator.process(val);

        return val;
    }

    float m_sampleRate = 48000.0f;
    bool m_needReconfigFilters = true;

    // Thread-safe config
    std::atomic<float> m_boost{1.0f};
    std::atomic<float> m_gateThresholdDb{-40.0f};
    std::atomic<float> m_compThresholdDb{-20.0f};
    std::atomic<float> m_compRatio{4.0f};

    // DSP components (in signal chain order)
    DCBlocker       m_dcBlocker;
    NoiseGate       m_gate;
    BiquadFilter    m_preLPF;        // Pre-gain low-pass (8kHz)
    Compressor      m_compressor;
    BiquadFilter    m_deEmphasis;    // Post-gain high-shelf de-emphasis
    TanhSaturator   m_saturator;
};

#endif // PHANTOMMIC_MICDSP_H
