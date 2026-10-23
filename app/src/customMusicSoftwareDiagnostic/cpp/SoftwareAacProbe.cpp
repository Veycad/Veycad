#include <jni.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cerrno>
#include <sstream>
#include <stdexcept>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/intreadwrite.h>
#include <libavutil/avutil.h>
#include <libavutil/mem.h>
}

namespace {
// Kotlin may hold raw, presentation, samples and a hashing buffer concurrently.
// A 16 MiB file cap keeps total PCM allocations below the 64 MiB budget.
constexpr uint64_t MAX_PCM = 16ULL * 1024 * 1024;
constexpr int MAX_PACKET = 1024 * 1024;
struct Probe {
    AVCodecContext* codec = nullptr;
    AVFrame* frame = nullptr;
    FILE* output = nullptr;
    int rate = 0, channels = 0;
    uint64_t bytes = 0, frames = 0, packets = 0;
    bool drained = false;
    std::ostringstream ledger;
    ~Probe() { if (output) fclose(output); av_frame_free(&frame); avcodec_free_context(&codec); }
};
void fail(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
Probe* get(jlong h) { if (!h) throw std::runtime_error("closed probe"); return reinterpret_cast<Probe*>(h); }
void receive(Probe* p, bool draining) {
    for (int attempt = 0; attempt < 65536; ++attempt) {
        int result = avcodec_receive_frame(p->codec, p->frame);
        if (result == AVERROR_EOF) { p->drained = true; return; }
        if (result == AVERROR(EAGAIN)) { if (draining) throw std::runtime_error("drain incomplete EAGAIN"); return; }
        if (result < 0) throw std::runtime_error("receive failed");
        AVFrame* f = p->frame;
        if (f->format != AV_SAMPLE_FMT_FLTP || f->sample_rate != p->rate ||
            f->ch_layout.nb_channels != p->channels || f->nb_samples <= 0 || f->nb_samples > 8192 ||
            av_channel_layout_compare(&f->ch_layout, &p->codec->ch_layout) != 0 || !f->extended_data || f->pts == AV_NOPTS_VALUE)
            throw std::runtime_error("actual frame format/rate/layout invalid");
        uint64_t added = uint64_t(f->nb_samples) * p->channels * 4;
        if (p->bytes + added > MAX_PCM || p->frames >= 65536) throw std::runtime_error("PCM bound exceeded");
        for (int c = 0; c < p->channels; ++c)
            if (!f->extended_data[c] || f->linesize[0] < f->nb_samples * 4) throw std::runtime_error("invalid plane");
        // Copy IEEE754 words unchanged: no float arithmetic or integer PCM intermediate.
        for (int n = 0; n < f->nb_samples; ++n) for (int c = 0; c < p->channels; ++c)
            if (fwrite(f->extended_data[c] + n * 4, 1, 4, p->output) != 4) throw std::runtime_error("PCM write failed");
        auto* skip = av_frame_get_side_data(f, AV_FRAME_DATA_SKIP_SAMPLES);
        if (skip && skip->size < 10) throw std::runtime_error("invalid skip side data");
        if (p->frames) p->ledger << ',';
        p->ledger << "{\"index\":" << p->frames << ",\"pts_us\":" << f->pts
          << ",\"samples\":" << f->nb_samples << ",\"format\":\"fltp\",\"rate\":" << f->sample_rate
          << ",\"channels\":" << f->ch_layout.nb_channels << ",\"raw_byte_offset\":" << p->bytes
          << ",\"channel_order\":" << int(f->ch_layout.order)
          << ",\"native_channel_mask\":" << (f->ch_layout.order == AV_CHANNEL_ORDER_NATIVE ? f->ch_layout.u.mask : 0)
          << ",\"plane_count\":" << p->channels << ",\"plane_stride_bytes\":" << f->linesize[0]
          << ",\"skip_side_data_present\":" << (skip ? "true" : "false")
          << ",\"skip_start\":" << (skip ? AV_RL32(skip->data) : 0)
          << ",\"skip_end\":" << (skip ? AV_RL32(skip->data + 4) : 0)
          << ",\"skip_start_reason\":" << (skip ? int(skip->data[8]) : 0)
          << ",\"skip_end_reason\":" << (skip ? int(skip->data[9]) : 0) << '}';
        p->bytes += added; ++p->frames;
        av_frame_unref(f);
    }
    throw std::runtime_error("receive bound exceeded");
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_veycad_app_SoftwareAacNative_create(JNIEnv* e, jobject, jbyteArray csd, jint rate, jint channels, jstring path) {
    Probe* p = nullptr;
    try {
        int length = e->GetArrayLength(csd);
        if (length < 1 || length > 65536 || rate < 8000 || rate > 192000 || channels < 1 || channels > 8) throw std::runtime_error("invalid input config");
        p = new Probe(); p->rate = rate; p->channels = channels;
        auto* decoder = avcodec_find_decoder_by_name("aac");
        if (!decoder || decoder->id != AV_CODEC_ID_AAC) throw std::runtime_error("explicit AAC decoder missing");
        p->codec = avcodec_alloc_context3(decoder); p->frame = av_frame_alloc();
        if (!p->codec || !p->frame) throw std::runtime_error("allocation failed");
        p->codec->sample_rate = rate; av_channel_layout_default(&p->codec->ch_layout, channels);
        p->codec->pkt_timebase = AVRational{1, 1000000};
        p->codec->flags2 |= AV_CODEC_FLAG2_SKIP_MANUAL;
        p->codec->extradata = static_cast<uint8_t*>(av_mallocz(length + AV_INPUT_BUFFER_PADDING_SIZE));
        if (!p->codec->extradata) throw std::runtime_error("CSD allocation failed");
        p->codec->extradata_size = length;
        e->GetByteArrayRegion(csd, 0, length, reinterpret_cast<jbyte*>(p->codec->extradata));
        if (e->ExceptionCheck()) throw std::runtime_error("CSD copy failed");
        if (avcodec_open2(p->codec, decoder, nullptr) < 0) throw std::runtime_error("AAC open failed");
        const char* name = e->GetStringUTFChars(path, nullptr);
        if (!name) throw std::runtime_error("path unavailable");
        p->output = fopen(name, "wb"); e->ReleaseStringUTFChars(path, name);
        if (!p->output) throw std::runtime_error("PCM file unavailable");
        return reinterpret_cast<jlong>(p);
    } catch (const std::exception& ex) { delete p; fail(e, ex.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_veycad_app_SoftwareAacNative_push(JNIEnv* e, jobject, jlong h, jbyteArray data, jlong pts) {
    AVPacket* packet = nullptr;
    try {
        auto* p = get(h); int length = e->GetArrayLength(data);
        if (p->drained || length < 1 || length > MAX_PACKET || p->packets >= 65536) throw std::runtime_error("packet bound/state invalid");
        packet = av_packet_alloc();
        if (!packet || av_new_packet(packet, length) < 0) throw std::runtime_error("packet allocation failed");
        e->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(packet->data));
        if (e->ExceptionCheck()) throw std::runtime_error("packet copy failed");
        packet->pts = pts; packet->dts = pts;
        int result = avcodec_send_packet(p->codec, packet);
        if (result == AVERROR(EAGAIN)) { receive(p, false); result = avcodec_send_packet(p->codec, packet); }
        if (result < 0) throw std::runtime_error("send failed");
        ++p->packets; receive(p, false);
    } catch (const std::exception& ex) { fail(e, ex.what()); }
    av_packet_free(&packet);
}
extern "C" JNIEXPORT void JNICALL Java_com_veycad_app_SoftwareAacNative_drain(JNIEnv* e, jobject, jlong h) {
    try { auto* p = get(h); if (avcodec_send_packet(p->codec, nullptr) < 0) throw std::runtime_error("drain send failed"); receive(p, true); if (fflush(p->output)) throw std::runtime_error("flush failed"); }
    catch (const std::exception& ex) { fail(e, ex.what()); }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_veycad_app_SoftwareAacNative_finish(JNIEnv* e, jobject, jlong h) {
    try { auto* p = get(h); std::ostringstream s; s << "{\"decoder\":\"aac\",\"actual_format\":\"fltp\",\"avcodec_version\":" << avcodec_version() << ",\"avutil_version\":" << avutil_version() << ",\"skip_manual\":true,\"drain_eof\":" << (p->drained ? "true" : "false") << ",\"packets\":" << p->packets << ",\"raw_bytes\":" << p->bytes << ",\"frames\":[" << p->ledger.str() << "]}"; return e->NewStringUTF(s.str().c_str()); }
    catch (const std::exception& ex) { fail(e, ex.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_veycad_app_SoftwareAacNative_destroy(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Probe*>(h); }
