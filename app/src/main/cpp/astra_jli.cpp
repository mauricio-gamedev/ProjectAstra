#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

using JliLaunchFn = int (JNICALL *)(
    int argc,
    char** argv,
    int jargc,
    const char** jargv,
    int appclassc,
    const char** appclassv,
    const char* fullversion,
    const char* dotversion,
    const char* pname,
    const char* lname,
    jboolean javaargs,
    jboolean cpwildcard,
    jboolean javaw,
    jint ergo
);

std::mutex gJliMutex;
bool gJliLaunchAttempted = false;
void* gJliHandle = nullptr;

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

std::string errnoMessage(const char* stage) {
    return std::string(stage) + ": " + std::strerror(errno);
}

class StdioCapture {
public:
    explicit StdioCapture(const std::string& path) {
        if (path.empty()) {
            error_ = "caminho do log vazio";
            return;
        }

        logFd_ = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (logFd_ < 0) {
            error_ = errnoMessage("open do log falhou");
            return;
        }

        fflush(stdout);
        fflush(stderr);
        savedStdout_ = dup(STDOUT_FILENO);
        savedStderr_ = dup(STDERR_FILENO);
        if (savedStdout_ < 0 || savedStderr_ < 0) {
            error_ = errnoMessage("dup de stdout/stderr falhou");
            restore();
            return;
        }

        if (dup2(logFd_, STDOUT_FILENO) < 0 || dup2(logFd_, STDERR_FILENO) < 0) {
            error_ = errnoMessage("dup2 para o log falhou");
            restore();
            return;
        }
        active_ = true;
    }

    ~StdioCapture() {
        restore();
    }

    bool active() const { return active_; }
    const std::string& error() const { return error_; }

    void restore() {
        fflush(stdout);
        fflush(stderr);
        if (savedStdout_ >= 0) {
            dup2(savedStdout_, STDOUT_FILENO);
            close(savedStdout_);
            savedStdout_ = -1;
        }
        if (savedStderr_ >= 0) {
            dup2(savedStderr_, STDERR_FILENO);
            close(savedStderr_);
            savedStderr_ = -1;
        }
        if (logFd_ >= 0) {
            close(logFd_);
            logFd_ = -1;
        }
        active_ = false;
    }

private:
    int logFd_ = -1;
    int savedStdout_ = -1;
    int savedStderr_ = -1;
    bool active_ = false;
    std::string error_;
};

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_launchJavaVersionTest(
        JNIEnv* env,
        jobject,
        jstring jliLibraryPath,
        jstring javaExecutable,
        jstring workingDirectory,
        jstring logPath) {
    const std::string jliPath = toString(env, jliLibraryPath);
    const std::string javaPath = toString(env, javaExecutable);
    const std::string workDir = toString(env, workingDirectory);
    const std::string outputPath = toString(env, logPath);

    std::lock_guard<std::mutex> lock(gJliMutex);
    if (gJliLaunchAttempted) {
        return fromString(env, "ERROR|uma JVM já foi iniciada/tentada neste processo; reabra o teste para uma sessão limpa");
    }
    gJliLaunchAttempted = true;

    if (jliPath.empty() || javaPath.empty()) {
        return fromString(env, "ERROR|libjli ou executável Java não informado");
    }

    if (!workDir.empty() && chdir(workDir.c_str()) != 0) {
        return fromString(env, "ERROR|" + errnoMessage("chdir do JAVA_HOME falhou"));
    }

    dlerror();
    gJliHandle = dlopen(jliPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (gJliHandle == nullptr) {
        const char* error = dlerror();
        return fromString(
            env,
            "ERROR|dlopen libjli falhou: " + std::string(error != nullptr ? error : "erro desconhecido")
        );
    }

    dlerror();
    auto launch = reinterpret_cast<JliLaunchFn>(dlsym(gJliHandle, "JLI_Launch"));
    const char* symbolError = dlerror();
    if (launch == nullptr || symbolError != nullptr) {
        return fromString(
            env,
            "ERROR|JLI_Launch não resolvido: " +
                std::string(symbolError != nullptr ? symbolError : "símbolo ausente")
        );
    }

    StdioCapture capture(outputPath);
    if (!capture.active()) {
        return fromString(env, "ERROR|não foi possível capturar stdout/stderr: " + capture.error());
    }

    std::vector<std::string> storage = {
        javaPath,
        "-Djava.awt.headless=true",
        "-version"
    };
    std::vector<char*> argv;
    argv.reserve(storage.size());
    for (std::string& argument : storage) {
        argv.push_back(argument.data());
    }

    std::fprintf(stderr, "[Project Astra alpha10] JLI_Launch smoke test\n");
    std::fprintf(stderr, "libjli=%s\n", jliPath.c_str());
    std::fprintf(stderr, "java=%s\n\n", javaPath.c_str());
    std::fflush(stderr);

    const int result = launch(
        static_cast<int>(argv.size()),
        argv.data(),
        0,
        nullptr,
        0,
        nullptr,
        "Project Astra 0.1.0-alpha10",
        "0.1.0-alpha10",
        "java",
        "java",
        JNI_FALSE,
        JNI_FALSE,
        JNI_FALSE,
        0
    );

    capture.restore();
    if (result == 0) {
        return fromString(env, "OK|JLI_Launch executou Java -version e retornou 0");
    }
    return fromString(env, "ERROR|JLI_Launch retornou código " + std::to_string(result));
}
