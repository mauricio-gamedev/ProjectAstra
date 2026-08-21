#include <jni.h>
#include <dlfcn.h>
#include <cerrno>
#include <cstdlib>
#include <string>

namespace {

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring fromString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_probeLibrary(
        JNIEnv* env,
        jobject,
        jstring libraryPath,
        jstring requiredSymbol) {
    const std::string path = toString(env, libraryPath);
    const std::string symbol = toString(env, requiredSymbol);

    if (path.empty()) {
        return fromString(env, "ERROR|caminho da biblioteca vazio");
    }

    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        return fromString(env, std::string("ERROR|") + (error != nullptr ? error : "dlopen falhou"));
    }

    if (!symbol.empty()) {
        dlerror();
        void* address = dlsym(handle, symbol.c_str());
        const char* error = dlerror();
        if (address == nullptr || error != nullptr) {
            const std::string detail = error != nullptr ? error : "símbolo não encontrado";
            dlclose(handle);
            return fromString(env, "ERROR|" + detail);
        }
    }

    dlclose(handle);
    return fromString(env, "OK|biblioteca carregada");
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_setEnvironment(
        JNIEnv* env,
        jobject,
        jstring key,
        jstring value) {
    const std::string name = toString(env, key);
    const std::string data = toString(env, value);
    if (name.empty()) return EINVAL;
    return setenv(name.c_str(), data.c_str(), 1);
}
