// tinydj native audio engine.
//
// Responsibilities:
//   * Decode a whole FLAC/MP3 file to interleaved stereo float PCM in RAM (dr_libs).
//   * Drive a low-latency Oboe output stream whose callback reads the PCM buffer
//     through a *fractional* read pointer. The pointer advances by `speed` frames
//     per output frame (scaled for the file/device sample-rate ratio).
//   * `speed` may be negative -> audible reverse playback. This single mechanism
//     serves normal play (speed=1), varispeed (0<speed!=1), and scratch (rapidly
//     changing / negative speed driven by the reel gesture in the UI).
//
// All DSP lives here; all policy (when to play, what speed the finger implies)
// lives in Kotlin. The JNI surface is intentionally tiny.

#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <unistd.h>
#include <vector>

#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"
#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"

#define LOG_TAG "tinydj-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace oboe;

namespace {

// 4-point cubic Hermite interpolation (Catmull-Rom). Cheap, sounds good for scratch.
inline float hermite(float y0, float y1, float y2, float y3, float t) {
    const float c0 = y1;
    const float c1 = 0.5f * (y2 - y0);
    const float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    const float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
    return ((c3 * t + c2) * t + c1) * t + c0;
}

struct WavFmt {
    uint16_t audioFormat;
    uint16_t numChannels;
    uint32_t sampleRate;
    uint32_t byteRate;
    uint16_t blockAlign;
    uint16_t bitsPerSample;
};

struct WavChunk {
    char id[4];
    uint32_t size;
};

bool parseWav(const std::vector<uint8_t>& bytes, std::vector<float>& outPcm, unsigned int& outChannels, unsigned int& outRate, uint64_t& outTotalFrames) {
    if (bytes.size() < 44) return false;
    if (std::memcmp(bytes.data(), "RIFF", 4) != 0 || std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
        return false;
    }
    
    size_t offset = 12;
    WavFmt fmt{};
    bool foundFmt = false;
    bool foundData = false;
    const uint8_t* pData = nullptr;
    uint32_t dataSize = 0;
    
    while (offset + 8 <= bytes.size()) {
        WavChunk chunk;
        std::memcpy(&chunk, bytes.data() + offset, 8);
        offset += 8;
        
        if (std::memcmp(chunk.id, "fmt ", 4) == 0) {
            if (chunk.size >= 16 && offset + 16 <= bytes.size()) {
                std::memcpy(&fmt, bytes.data() + offset, 16);
                foundFmt = true;
            }
            offset += chunk.size;
        } else if (std::memcmp(chunk.id, "data", 4) == 0) {
            dataSize = chunk.size;
            if (offset + dataSize > bytes.size()) {
                dataSize = bytes.size() - offset;
            }
            pData = bytes.data() + offset;
            foundData = true;
            break;
        } else {
            offset += chunk.size;
        }
    }
    
    if (!foundFmt || !foundData || !pData) return false;
    if (fmt.audioFormat != 1 && fmt.audioFormat != 3) {
        return false; // Only PCM (1) or Float (3) is supported
    }
    
    outChannels = fmt.numChannels;
    outRate = fmt.sampleRate;
    
    size_t bytesPerSample = fmt.bitsPerSample / 8;
    if (bytesPerSample == 0) return false;
    size_t totalSamples = dataSize / bytesPerSample;
    outTotalFrames = totalSamples / fmt.numChannels;
    
    outPcm.resize(totalSamples);
    
    if (fmt.audioFormat == 1) { // PCM
        if (fmt.bitsPerSample == 16) {
            const int16_t* src = reinterpret_cast<const int16_t*>(pData);
            for (size_t i = 0; i < totalSamples; ++i) {
                outPcm[i] = src[i] / 32768.0f;
            }
        } else if (fmt.bitsPerSample == 8) {
            for (size_t i = 0; i < totalSamples; ++i) {
                outPcm[i] = (pData[i] - 128.0f) / 128.0f;
            }
        } else if (fmt.bitsPerSample == 24) {
            for (size_t i = 0; i < totalSamples; ++i) {
                int32_t val = (pData[i * 3 + 0]) | (pData[i * 3 + 1] << 8) | (static_cast<int8_t>(pData[i * 3 + 2]) << 16);
                outPcm[i] = val / 8388608.0f;
            }
        } else {
            return false;
        }
    } else if (fmt.audioFormat == 3) { // Float
        if (fmt.bitsPerSample == 32) {
            const float* src = reinterpret_cast<const float*>(pData);
            std::memcpy(outPcm.data(), src, totalSamples * sizeof(float));
        } else {
            return false;
        }
    }
    return true;
}

}  // namespace

class TinyDjEngine : public AudioStreamDataCallback {
public:
    bool openStream() {
        AudioStreamBuilder b;
        b.setDirection(Direction::Output)
            ->setPerformanceMode(PerformanceMode::LowLatency)
            ->setSharingMode(SharingMode::Exclusive)
            ->setFormat(AudioFormat::Float)
            ->setChannelCount(2)
            ->setUsage(Usage::Media)
            ->setContentType(ContentType::Music)
            ->setDataCallback(this);

        Result r = b.openStream(mStream);
        if (r != Result::OK) {
            LOGE("openStream failed: %s", convertToText(r));
            return false;
        }
        mDeviceRate = mStream->getSampleRate();
        // Two bursts keeps latency low while staying glitch-resistant.
        mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
        LOGI("stream open: deviceRate=%d, burst=%d", mDeviceRate,
             mStream->getFramesPerBurst());
        return true;
    }

    void start() { if (mStream) mStream->requestStart(); }
    void stop()  { if (mStream) mStream->requestStop(); }

    void close() {
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
        }
    }

    int64_t loadFd(int fd, bool isFlac) {
        std::vector<uint8_t> bytes = readAllAndClose(fd);
        if (bytes.empty()) {
            LOGE("loadFd: empty file");
            return -1;
        }

        std::vector<float> decodedPcm;
        unsigned int channels = 0;
        unsigned int rate = 0;
        uint64_t totalFrames = 0;
        bool isWav = parseWav(bytes, decodedPcm, channels, rate, totalFrames);
        float* decoded = nullptr;

        if (isWav) {
            // Already parsed
        } else if (isFlac) {
            drflac_uint64 n = 0;
            decoded = drflac_open_memory_and_read_pcm_frames_f32(
                bytes.data(), bytes.size(), &channels, &rate, &n, nullptr);
            totalFrames = n;
        } else {
            drmp3_config cfg{};
            drmp3_uint64 n = 0;
            decoded = drmp3_open_memory_and_read_pcm_frames_f32(
                bytes.data(), bytes.size(), &cfg, &n, nullptr);
            channels = cfg.channels;
            rate = cfg.sampleRate;
            totalFrames = n;
        }

        if (!isWav && (decoded == nullptr || totalFrames == 0 || channels == 0)) {
            LOGE("decode failed (isFlac=%d, ch=%u, rate=%u, frames=%llu)", isFlac,
                 channels, rate, (unsigned long long)totalFrames);
            if (decoded) { isFlac ? drflac_free(decoded, nullptr) : drmp3_free(decoded, nullptr); }
            return -1;
        }

        // Down/up-mix to stereo interleaved so the callback is always 2-channel.
        std::vector<float> stereo(static_cast<size_t>(totalFrames) * 2);
        for (uint64_t f = 0; f < totalFrames; ++f) {
            float l, r;
            if (channels == 1) {
                l = r = isWav ? decodedPcm[f] : decoded[f];
            } else {
                l = isWav ? decodedPcm[f * channels + 0] : decoded[f * channels + 0];
                r = isWav ? decodedPcm[f * channels + 1] : decoded[f * channels + 1];
            }
            stereo[f * 2] = l;
            stereo[f * 2 + 1] = r;
        }
        if (!isWav) {
            isFlac ? drflac_free(decoded, nullptr) : drmp3_free(decoded, nullptr);
        }

        {
            // The audio callback uses try_lock; it briefly outputs silence while we swap.
            std::lock_guard<std::mutex> lk(mBufMutex);
            mPcm = std::move(stereo);
            mTotalFrames.store(static_cast<int64_t>(totalFrames));
            mFileRate.store(static_cast<int>(rate));
            mReadFrame.store(0.0);
            mSpeed.store(1.0);
            mPlaying.store(false);
            mScrubbing.store(false);
        }
        LOGI("loaded: frames=%lld, fileRate=%d (isFlac=%d)",
             (long long)mTotalFrames.load(), mFileRate.load(), isFlac);
        return mTotalFrames.load();
    }

    void play()  { mPlaying.store(true); }
    void pause() {
        mPlaying.store(false);
        mLeftLevel.store(0.0f);
        mRightLevel.store(0.0f);
    }
    void setSpeed(double s) { mSpeed.store(s); }
    void setScrubbing(bool s) { mScrubbing.store(s); }
    void setGain(float g) { mGain.store(g); }
    void setLoop(bool on) { mLoop.store(on); }

    void seekFrame(int64_t f) {
        const int64_t total = mTotalFrames.load();
        double v = static_cast<double>(f);
        if (v < 0) v = 0;
        if (total > 1 && v > total - 1) v = total - 1;
        mReadFrame.store(v);
    }

    void scrubBy(double deltaFrames) {
        const int64_t total = mTotalFrames.load();
        double cur = mReadFrame.load() + deltaFrames;
        if (cur < 0) cur = 0;
        if (total > 1 && cur > total - 1) cur = total - 1;
        mReadFrame.store(cur);
    }

    void startRecording() {
        std::lock_guard<std::mutex> lk(mRecMutex);
        mRecBuffer.clear();
        mRecording.store(true);
    }

    void setRecording(bool enabled) {
        mRecording.store(enabled);
    }

    int pullRecording(int16_t* outBuf, int maxShorts) {
        std::lock_guard<std::mutex> lk(mRecMutex);
        int count = std::min(maxShorts, static_cast<int>(mRecBuffer.size()));
        if (count > 0) {
            std::copy(mRecBuffer.begin(), mRecBuffer.begin() + count, outBuf);
            mRecBuffer.erase(mRecBuffer.begin(), mRecBuffer.begin() + count);
        }
        return count;
    }

    int getDeviceRate() const {
        return mDeviceRate;
    }

    int64_t positionFrames() const { return static_cast<int64_t>(mReadFrame.load()); }
    float leftLevel() const { return mLeftLevel.load(); }
    float rightLevel() const { return mRightLevel.load(); }
    int64_t totalFrames() const { return mTotalFrames.load(); }
    int fileRate() const { return mFileRate.load(); }
    int deviceRate() const { return mDeviceRate; }

    DataCallbackResult onAudioReady(AudioStream*, void* audioData,
                                    int32_t numFrames) override {
        float* out = static_cast<float*>(audioData);

        std::unique_lock<std::mutex> lk(mBufMutex, std::try_to_lock);
        const int64_t total = mTotalFrames.load();
        if (!lk.owns_lock() || mPcm.empty() || total <= 1) {
            for (int i = 0; i < numFrames * 2; ++i) out[i] = 0.0f;
            mLeftLevel.store(0.0f);
            mRightLevel.store(0.0f);
            return DataCallbackResult::Continue;
        }

        const bool moving = mPlaying.load() || mScrubbing.load();
        const bool loop = mLoop.load();
        const float gain = mGain.load();
        double speed = mSpeed.load();
        double pos = mReadFrame.load();
        const float* p = mPcm.data();
        const double step = moving ? speed * (static_cast<double>(mFileRate.load()) /
                                              static_cast<double>(mDeviceRate))
                                   : 0.0;

        float leftLevel = mLeftLevel.load();
        float rightLevel = mRightLevel.load();
        float decay = 0.9998f;
        if (mDeviceRate > 0) {
            decay = std::exp(-1.0f / (static_cast<float>(mDeviceRate) * 0.15f));
        }

        for (int i = 0; i < numFrames; ++i) {
            if (step == 0.0) {
                // Transport stopped, or finger holding the reel still: silence,
                // like a needle resting on a motionless record.
                out[i * 2] = 0.0f;
                out[i * 2 + 1] = 0.0f;
                leftLevel *= decay;
                rightLevel *= decay;
                continue;
            }

            if (pos <= 0.0 && step < 0.0) {     // hit the start going backwards
                if (loop) {
                    pos = total - 1;            // wrap to the end, keep playing
                } else {
                    pos = 0.0;
                    mPlaying.store(false);
                    out[i * 2] = 0.0f; out[i * 2 + 1] = 0.0f;
                    leftLevel *= decay;
                    rightLevel *= decay;
                    continue;
                }
            }
            if (pos >= total - 1 && step > 0.0) { // hit the end going forwards
                if (loop) {
                    pos = 0.0;                  // wrap to the start, keep playing
                } else {
                    pos = total - 1;
                    mPlaying.store(false);
                    out[i * 2] = 0.0f; out[i * 2 + 1] = 0.0f;
                    leftLevel *= decay;
                    rightLevel *= decay;
                    continue;
                }
            }

            const int64_t i1 = static_cast<int64_t>(pos);
            const float t = static_cast<float>(pos - static_cast<double>(i1));
            const int64_t i0 = i1 > 0 ? i1 - 1 : 0;
            const int64_t i2 = i1 + 1 <= total - 1 ? i1 + 1 : total - 1;
            const int64_t i3 = i1 + 2 <= total - 1 ? i1 + 2 : total - 1;

            float l = gain * hermite(p[i0 * 2], p[i1 * 2], p[i2 * 2], p[i3 * 2], t);
            float r = gain * hermite(p[i0 * 2 + 1], p[i1 * 2 + 1], p[i2 * 2 + 1], p[i3 * 2 + 1], t);
            out[i * 2] = l;
            out[i * 2 + 1] = r;

            leftLevel = std::max(std::abs(l), leftLevel * decay);
            rightLevel = std::max(std::abs(r), rightLevel * decay);

            pos += step;
        }
        mLeftLevel.store(leftLevel);
        mRightLevel.store(rightLevel);
        mReadFrame.store(pos);

        if (mRecording.load()) {
            std::lock_guard<std::mutex> recLk(mRecMutex);
            for (int i = 0; i < numFrames; ++i) {
                float monoVal = (out[i * 2] + out[i * 2 + 1]) * 0.5f * 32767.0f;
                int16_t monoShort = static_cast<int16_t>(std::max(-32768.0f, std::min(32767.0f, monoVal)));
                mRecBuffer.push_back(monoShort);
            }
        }

        return DataCallbackResult::Continue;
    }

private:
    static std::vector<uint8_t> readAllAndClose(int fd) {
        std::vector<uint8_t> data;
        if (fd < 0) return data;
        FILE* f = fdopen(fd, "rb");
        if (!f) { ::close(fd); return data; }
        uint8_t buf[1 << 16];
        size_t n;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
            data.insert(data.end(), buf, buf + n);
        }
        fclose(f);  // also closes the underlying fd
        return data;
    }

    std::shared_ptr<AudioStream> mStream;

    std::mutex mBufMutex;          // guards swap of mPcm
    std::vector<float> mPcm;       // interleaved stereo
    // Atomic so the lock-free JNI getters can't tear a 64-bit read on 32-bit ABIs.
    std::atomic<int64_t> mTotalFrames{0};
    std::atomic<int> mFileRate{48000};
    int mDeviceRate = 48000;       // set once in openStream(), before any concurrency

    std::atomic<double> mReadFrame{0.0};
    std::atomic<double> mSpeed{1.0};
    std::atomic<bool> mPlaying{false};
    std::atomic<bool> mScrubbing{false};
    std::atomic<float> mGain{1.0f};
    std::atomic<bool> mLoop{false};

    std::atomic<float> mLeftLevel{0.0f};
    std::atomic<float> mRightLevel{0.0f};

    std::mutex mRecMutex;
    std::vector<int16_t> mRecBuffer;
    std::atomic<bool> mRecording{false};
};

// ---------------------------------------------------------------------------
// JNI bridge. The handle is a raw pointer to a TinyDjEngine, passed as a jlong.
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeCreate(JNIEnv*, jobject) {
    auto* e = new TinyDjEngine();
    if (!e->openStream()) {
        // Keep the object alive even if the stream failed; load() may still decode
        // and a later start can retry. But log loudly.
        LOGE("nativeCreate: stream open failed");
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jlong JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeLoadFd(JNIEnv*, jobject, jlong h,
                                                        jint fd, jboolean isFlac) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    if (!e) return -1;
    return e->loadFd(static_cast<int>(fd), isFlac == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeStart(JNIEnv*, jobject, jlong h) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->start();
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeStop(JNIEnv*, jobject, jlong h) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->stop();
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativePlay(JNIEnv*, jobject, jlong h) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->play();
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativePause(JNIEnv*, jobject, jlong h) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->pause();
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeSetSpeed(JNIEnv*, jobject, jlong h,
                                                          jdouble s) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->setSpeed(s);
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeSetScrubbing(JNIEnv*, jobject,
                                                              jlong h, jboolean s) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->setScrubbing(s == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeSetVolume(JNIEnv*, jobject, jlong h,
                                                           jfloat g) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->setGain(g);
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeSetLoop(JNIEnv*, jobject, jlong h,
                                                         jboolean on) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->setLoop(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeSeekFrame(JNIEnv*, jobject, jlong h,
                                                           jlong f) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->seekFrame(f);
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeScrubBy(JNIEnv*, jobject, jlong h,
                                                         jdouble deltaFrames) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->scrubBy(deltaFrames);
}

JNIEXPORT jlong JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativePositionFrames(JNIEnv*, jobject,
                                                                jlong h) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    return e ? e->positionFrames() : 0;
}

JNIEXPORT jlong JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeTotalFrames(JNIEnv*, jobject, jlong h) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    return e ? e->totalFrames() : 0;
}

JNIEXPORT jint JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeFileRate(JNIEnv*, jobject, jlong h) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    return e ? e->fileRate() : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeVuLeft(JNIEnv*, jobject, jlong h) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    return e ? e->leftLevel() : 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeVuRight(JNIEnv*, jobject, jlong h) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    return e ? e->rightLevel() : 0.0f;
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeRelease(JNIEnv*, jobject, jlong h) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) {
        e->close();
        delete e;
    }
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeStartRecording(JNIEnv*, jobject, jlong h) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->startRecording();
}

JNIEXPORT void JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeStopRecording(JNIEnv*, jobject, jlong h) {
    if (auto* e = reinterpret_cast<TinyDjEngine*>(h)) e->setRecording(false);
}

JNIEXPORT jint JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativePullRecording(JNIEnv* env, jobject, jlong h, jshortArray jArr) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    if (!e) return 0;
    
    jsize len = env->GetArrayLength(jArr);
    jshort* body = env->GetShortArrayElements(jArr, nullptr);
    
    int pulled = e->pullRecording(reinterpret_cast<int16_t*>(body), len);
    
    env->ReleaseShortArrayElements(jArr, body, 0);
    return pulled;
}

JNIEXPORT jint JNICALL
Java_com_tinydj_core_audio_OboeAudioEngine_nativeDeviceRate(JNIEnv*, jobject, jlong h) {
    auto* e = reinterpret_cast<TinyDjEngine*>(h);
    return e ? e->getDeviceRate() : 48000;
}

}  // extern "C"
