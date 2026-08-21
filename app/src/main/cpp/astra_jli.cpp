#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
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

std::string runtimeLibraryPath(const std::string& javaHome) {
    const std::string server = javaHome + "/lib/server";
    const std::string lib = javaHome + "/lib";
    const char* existing = std::getenv("LD_LIBRARY_PATH");

    std::string value = server + ":" + lib;
    if (existing != nullptr && existing[0] != '\0') {
        value += ":";
        value += existing;
    }
    return value;
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

    if (jliPath.empty() || javaPath.empty() || workDir.empty()) {
        return fromString(env, "ERROR|libjli, executável Java ou JAVA_HOME não informado");
    }

    struct stat javaStat {};
    if (stat(javaPath.c_str(), &javaStat) != 0) {
        return fromString(env, "ERROR|bin/java não existe: " + errnoMessage("stat"));
    }
    if (!S_ISREG(javaStat.st_mode)) {
        return fromString(env, "ERROR|bin/java existe mas não é arquivo regular");
    }

    if (chdir(workDir.c_str()) != 0) {
        return fromString(env, "ERROR|" + errnoMessage("chdir do JAVA_HOME falhou"));
    }

    const std::string ldLibraryPath = runtimeLibraryPath(workDir);
    if (setenv("JAVA_HOME", workDir.c_str(), 1) != 0) {
        return fromString(env, "ERROR|" + errnoMessage("setenv JAVA_HOME falhou"));
    }
    if (setenv("LD_LIBRARY_PATH", ldLibraryPath.c_str(), 1) != 0) {
        return fromString(env, "ERROR|" + errnoMessage("setenv LD_LIBRARY_PATH falhou"));
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

    // JLI/exec follow the normal C argv contract: argc counts real arguments and
    // argv[argc] MUST be a null pointer. Alpha10 omitted this sentinel, which can
    // surface as EFAULT / "Bad address" when the launcher prepares a re-exec.
    std::vector<char*> argv;
    argv.reserve(storage.size() + 1);
    for (std::string& argument : storage) {
        argv.push_back(argument.data());
    }
    const int argc = static_cast<int>(argv.size());
    argv.push_back(nullptr);

    const bool executableBit = access(javaPath.c_str(), X_OK) == 0;

    std::fprintf(stderr, "[Project Astra alpha11] JLI_Launch smoke test\n");
    std::fprintf(stderr, "libjli=%s\n", jliPath.c_str());
    std::fprintf(stderr, "java=%s\n", javaPath.c_str());
    std::fprintf(stderr, "JAVA_HOME=%s\n", workDir.c_str());
    std::fprintf(stderr, "LD_LIBRARY_PATH=%s\n", ldLibraryPath.c_str());
    std::fprintf(stderr, "java_mode=%04o X_OK=%s\n", javaStat.st_mode & 07777, executableBit ? "yes" : "no");
    std::fprintf(stderr, "argc=%d argv_null_terminated=%s\n\n", argc, argv[argc] == nullptr ? "yes" : "no");
    std::fflush(stderr);

    const int result = launch(
        argc,
        argv.data(),
        0,
        nullptr,
        0,
        nullptr,
        "Project Astra 0.1.0-alpha11",
        "0.1.0-alpha11",
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
