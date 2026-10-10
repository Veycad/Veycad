#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <string>
#include <vector>
#include <thread>

namespace {
struct Cancellation { JavaVM *vm; jobject object; jmethodID method; };
bool cancelled(void *opaque) {
    auto *c = static_cast<Cancellation *>(opaque);
    JNIEnv *env = nullptr;
    bool attached = c->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached && c->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
    bool result = env->CallBooleanMethod(c->object, c->method);
    if (env->ExceptionCheck()) { env->ExceptionClear(); result = true; }
    if (attached) c->vm->DetachCurrentThread();
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_veycad_app_WhisperNative_open(JNIEnv *env,jobject,jstring path) {
    const char *p=env->GetStringUTFChars(path,nullptr);
    auto params=whisper_context_default_params(); params.use_gpu=false;
    auto *context=whisper_init_from_file_with_params(p,params);
    env->ReleaseStringUTFChars(path,p);
    return reinterpret_cast<jlong>(context);
}
extern "C" JNIEXPORT void JNICALL Java_com_veycad_app_WhisperNative_close(JNIEnv *,jobject,jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_veycad_app_WhisperNative_recognize(JNIEnv *env,jobject,jlong handle,jfloatArray audio,jstring language,jobject cancel) {
    auto *ctx=reinterpret_cast<whisper_context *>(handle);
    const auto length=env->GetArrayLength(audio);
    std::vector<float> samples(length); env->GetFloatArrayRegion(audio,0,length,samples.data());
    const char *lang=env->GetStringUTFChars(language,nullptr);
    std::string selected(lang); env->ReleaseStringUTFChars(language,lang);
    Cancellation cancellation{}; env->GetJavaVM(&cancellation.vm);
    cancellation.object=env->NewGlobalRef(cancel);
    jclass cancelClass=env->GetObjectClass(cancel);
    cancellation.method=env->GetMethodID(cancelClass,"isCancelled","()Z"); env->DeleteLocalRef(cancelClass);
    auto params=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads=std::max(1,std::min(4,static_cast<int>(std::thread::hardware_concurrency())));
    params.language=selected=="auto" ? nullptr : selected.c_str();
    params.detect_language=false; params.translate=false; params.no_context=true;
    params.print_progress=false; params.print_realtime=false; params.print_timestamps=false;
    params.suppress_blank=true; params.suppress_nst=true; params.temperature_inc=0;
    params.token_timestamps=true; params.max_len=42; params.split_on_word=true;
    params.abort_callback=cancelled; params.abort_callback_user_data=&cancellation;
    std::vector<std::string> rows;
    const int status=whisper_full(ctx,params,samples.data(),length);
    const bool aborted=cancelled(&cancellation);
    env->DeleteGlobalRef(cancellation.object);
    if(status!=0 && !aborted) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Whisper transcription failed");
        return nullptr;
    }
    if(!aborted) for(int i=0;i<whisper_full_n_segments(ctx);++i) {
        if(whisper_full_get_segment_no_speech_prob(ctx,i)>.6f) continue;
        const int64_t start=whisper_full_get_segment_t0(ctx,i)*10000;
        const int64_t end=whisper_full_get_segment_t1(ctx,i)*10000;
        const char *text=whisper_full_get_segment_text(ctx,i);
        if(end>start && text && *text) rows.push_back(std::to_string(start)+"\t"+std::to_string(end)+"\t"+text);
    }
    auto array=env->NewObjectArray(rows.size(),env->FindClass("java/lang/String"),nullptr);
    for(size_t i=0;i<rows.size();++i) { jstring row=env->NewStringUTF(rows[i].c_str()); env->SetObjectArrayElement(array,i,row); env->DeleteLocalRef(row); }
    return array;
}
