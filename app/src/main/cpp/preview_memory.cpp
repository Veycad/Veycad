#include <jni.h>
#include <cstdlib>
#include <limits>
#include <map>
#include <mutex>
#include <new>
#include <thread>

namespace {
struct Allocation { void* bytes; jint capacity; std::thread::id worker; };
std::mutex guard;
std::map<jlong, Allocation> allocations;
jlong next_handle = 0;

void fail(JNIEnv* env, const char* type, const char* message) {
    auto clazz = env->FindClass(type);
    if (clazz != nullptr) env->ThrowNew(clazz, message);
}

auto find_owned(JNIEnv* env, jlong handle) {
    auto found = allocations.find(handle);
    if (found == allocations.end() || found->second.worker != std::this_thread::get_id()) {
        fail(env, "java/lang/IllegalStateException", "Invalid, freed or foreign-worker preview allocation");
        return allocations.end();
    }
    return found;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_veycad_app_PreviewNativeMemory_allocate(JNIEnv* env, jobject, jint capacity) {
    if (capacity <= 0) {
        fail(env, "java/lang/IllegalArgumentException", "Preview capacity must be positive");
        return 0;
    }
    std::lock_guard<std::mutex> lock(guard);
    if (next_handle == std::numeric_limits<jlong>::max()) {
        fail(env, "java/lang/IllegalStateException", "Preview allocation handles exhausted");
        return 0;
    }
    void* bytes = std::calloc(static_cast<size_t>(capacity), 1);
    if (bytes == nullptr) {
        fail(env, "java/lang/OutOfMemoryError", "Preview staging allocation failed");
        return 0;
    }
    const auto handle = ++next_handle; // Never reuse tokens; no caller-supplied address is freed.
    try {
        allocations.emplace(handle, Allocation{bytes, capacity, std::this_thread::get_id()});
    } catch (const std::bad_alloc&) {
        std::free(bytes);
        fail(env, "java/lang/OutOfMemoryError", "Preview allocation bookkeeping failed");
        return 0;
    }
    return handle;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_veycad_app_PreviewNativeMemory_view(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(guard);
    const auto found = find_owned(env, handle);
    if (found == allocations.end()) return nullptr;
    return env->NewDirectByteBuffer(found->second.bytes, found->second.capacity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_veycad_app_PreviewNativeMemory_free(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(guard);
    const auto found = find_owned(env, handle);
    if (found == allocations.end()) return;
    std::free(found->second.bytes);
    allocations.erase(found); // Returning without an exception acknowledges completed disposal.
}
