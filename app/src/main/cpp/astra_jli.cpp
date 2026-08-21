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

struct RuntimeLibrarySpec {
    const char* relativePath;
    bool required;
};

std::mutex gJliMutex;
bool gJliLaunchAttempted = false;
void* gJliHandle = nullptr;
std::vector<void*> gRuntimePreloadHandles;

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) return result;

    const jsize count = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        result.push_back(toString(env, value));
        if (value != nullptr) env->DeleteLocalRef(value);
    }
    return result;
}

jstring fromString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string errnoMessage(const char* stage) {
    return std::string(stage) + ": " + std::strerror(errno);
}

const char* environmentOrUnset(const char* name) {
    const char* value = std::getenv(name);
    return value != nullptr && value[0] != '\0' ? value : "<unset>";
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

bool prepareRuntime(
        const std::string& javaPath,
        const std::string& javaHome,
        const std::string& workingDirectory,
        struct stat& javaStat,
        std::string& ldLibraryPath,
        std::string& error) {
    if (javaPath.empty() || javaHome.empty() || workingDirectory.empty()) {
        error = "executável Java, JAVA_HOME ou diretório de trabalho não informado";
        return false;
    }

    if (stat(javaPath.c_str(), &javaStat) != 0) {
        error = "bin/java não existe: " + errnoMessage("stat");
        return false;
    }
    if (!S_ISREG(javaStat.st_mode)) {
        error = "bin/java existe mas não é arquivo regular";
        return false;
    }

    if (chdir(workingDirectory.c_str()) != 0) {
        error = errnoMessage("chdir do diretório de trabalho falhou");
        return false;
    }

    ldLibraryPath = runtimeLibraryPath(javaHome);
    if (setenv("JAVA_HOME", javaHome.c_str(), 1) != 0) {
        error = errnoMessage("setenv JAVA_HOME falhou");
        return false;
    }
    if (setenv("LD_LIBRARY_PATH", ldLibraryPath.c_str(), 1) != 0) {
        error = errnoMessage("setenv LD_LIBRARY_PATH falhou");
        return false;
    }
    return true;
}

JliLaunchFn loadJli(const std::string& jliPath, std::string& error) {
    if (jliPath.empty()) {
        error = "libjli não informada";
        return nullptr;
    }

    dlerror();
    gJliHandle = dlopen(jliPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (gJliHandle == nullptr) {
        const char* dlError = dlerror();
        error = "dlopen libjli falhou: " +
            std::string(dlError != nullptr ? dlError : "erro desconhecido");
        return nullptr;
    }

    dlerror();
    auto launch = reinterpret_cast<JliLaunchFn>(dlsym(gJliHandle, "JLI_Launch"));
    const char* symbolError = dlerror();
    if (launch == nullptr || symbolError != nullptr) {
        error = "JLI_Launch não resolvido: " +
            std::string(symbolError != nullptr ? symbolError : "símbolo ausente");
        return nullptr;
    }
    return launch;
}

bool preloadRuntimeLibraries(const std::string& javaHome, std::string& error) {
    // Android classloader namespaces do not necessarily honor an LD_LIBRARY_PATH
    // changed after process creation. Loading the runtime libraries by absolute
    // path in dependency order keeps their handles alive in the game process and
    // lets later OpenJDK System.loadLibrary calls reuse the already resolved ELF
    // objects instead of failing on sibling DT_NEEDED entries such as libnet.so.
    static const RuntimeLibrarySpec libraries[] = {
        {"lib/server/libjvm.so", true},
        {"lib/libjava.so", true},
        {"lib/libjimage.so", true},
        {"lib/libverify.so", true},
        {"lib/libnet.so", true},
        {"lib/libnio.so", true},
        {"lib/libzip.so", true},
        {"lib/libmanagement.so", false},
        {"lib/libmanagement_ext.so", false},
        {"lib/libprefs.so", false},
        {"lib/libinstrument.so", false},
        {"lib/libextnet.so", false},
        {"lib/libjsig.so", false}
    };

    std::fprintf(stderr, "[Project Astra alpha19] OpenJDK native preload\n");
    gRuntimePreloadHandles.reserve(
        gRuntimePreloadHandles.size() + sizeof(libraries) / sizeof(libraries[0])
    );

    for (const auto& spec : libraries) {
        const std::string path = javaHome + "/" + spec.relativePath;
        struct stat libraryStat {};
        if (stat(path.c_str(), &libraryStat) != 0 || !S_ISREG(libraryStat.st_mode)) {
            if (spec.required) {
                error = "biblioteca OpenJDK obrigatória ausente: " + path;
                std::fprintf(stderr, "preload FAIL required: %s (missing)\n", spec.relativePath);
                std::fflush(stderr);
                return false;
            }
            std::fprintf(stderr, "preload SKIP optional: %s (missing)\n", spec.relativePath);
            continue;
        }

        dlerror();
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        const char* dlError = dlerror();
        if (handle == nullptr || dlError != nullptr) {
            const std::string detail = dlError != nullptr ? dlError : "erro desconhecido";
            if (spec.required) {
                error = "preload OpenJDK falhou em " + std::string(spec.relativePath) + ": " + detail;
                std::fprintf(stderr, "preload FAIL required: %s -> %s\n", spec.relativePath, detail.c_str());
                std::fflush(stderr);
                return false;
            }
            std::fprintf(stderr, "preload WARN optional: %s -> %s\n", spec.relativePath, detail.c_str());
            continue;
        }

        gRuntimePreloadHandles.push_back(handle);
        std::fprintf(stderr, "preload OK: %s\n", spec.relativePath);
    }

    std::fprintf(stderr, "runtime_preload=ready handles=%zu\n\n", gRuntimePreloadHandles.size());
    std::fflush(stderr);
    return true;
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

std::vector<char*> buildArgv(std::vector<std::string>& storage, int& argc) {
    std::vector<char*> argv;
    argv.reserve(storage.size() + 1);
    for (std::string& argument : storage) {
        argv.push_back(argument.data());
    }
    argc = static_cast<int>(argv.size());
    argv.push_back(nullptr);
    return argv;
}

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
    const std::string javaHome = toString(env, workingDirectory);
    const std::string outputPath = toString(env, logPath);

    std::lock_guard<std::mutex> lock(gJliMutex);
    if (gJliLaunchAttempted) {
        return fromString(env, "ERROR|uma JVM já foi iniciada/tentada neste processo; reabra o teste para uma sessão limpa");
    }
    gJliLaunchAttempted = true;

    struct stat javaStat {};
    std::string ldLibraryPath;
    std::string error;
    if (!prepareRuntime(javaPath, javaHome, javaHome, javaStat, ldLibraryPath, error)) {
        return fromString(env, "ERROR|" + error);
    }

    auto launch = loadJli(jliPath, error);
    if (launch == nullptr) {
        return fromString(env, "ERROR|" + error);
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
    int argc = 0;
    auto argv = buildArgv(storage, argc);
    const bool executableBit = access(javaPath.c_str(), X_OK) == 0;

    std::fprintf(stderr, "[Project Astra alpha19] JLI_Launch JVM smoke test\n");
    std::fprintf(stderr, "libjli=%s\n", jliPath.c_str());
    std::fprintf(stderr, "java=%s\n", javaPath.c_str());
    std::fprintf(stderr, "JAVA_HOME=%s\n", javaHome.c_str());
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
        "Project Astra 0.1.0-alpha19",
        "0.1.0-alpha19",
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

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_astromg01_launcher_nativebridge_AstraNativeBridge_launchMinecraftPlan(
        JNIEnv* env,
        jobject,
        jstring jliLibraryPath,
        jstring javaExecutable,
        jstring javaHomePath,
        jstring workingDirectory,
        jstring logPath,
        jobjectArray jvmArguments,
        jstring mainClassValue,
        jobjectArray gameArguments) {
    const std::string jliPath = toString(env, jliLibraryPath);
    const std::string javaPath = toString(env, javaExecutable);
    const std::string javaHome = toString(env, javaHomePath);
    const std::string workDir = toString(env, workingDirectory);
    const std::string outputPath = toString(env, logPath);
    const std::string mainClass = toString(env, mainClassValue);
    const std::vector<std::string> jvmArgs = toStringVector(env, jvmArguments);
    const std::vector<std::string> gameArgs = toStringVector(env, gameArguments);

    std::lock_guard<std::mutex> lock(gJliMutex);
    if (gJliLaunchAttempted) {
        return fromString(env, "ERROR|uma JVM já foi iniciada/tentada neste processo; reabra o Bridge test antes do Minecraft boot");
    }
    gJliLaunchAttempted = true;

    if (mainClass.empty()) {
        return fromString(env, "ERROR|mainClass do Minecraft está vazia");
    }

    struct stat javaStat {};
    std::string ldLibraryPath;
    std::string error;
    if (!prepareRuntime(javaPath, javaHome, workDir, javaStat, ldLibraryPath, error)) {
        return fromString(env, "ERROR|" + error);
    }

    auto launch = loadJli(jliPath, error);
    if (launch == nullptr) {
        return fromString(env, "ERROR|" + error);
    }

    StdioCapture capture(outputPath);
    if (!capture.active()) {
        return fromString(env, "ERROR|não foi possível capturar stdout/stderr: " + capture.error());
    }

    if (!preloadRuntimeLibraries(javaHome, error)) {
        capture.restore();
        return fromString(env, "ERROR|" + error);
    }

    std::vector<std::string> storage;
    storage.reserve(1 + jvmArgs.size() + 1 + gameArgs.size());
    storage.push_back(javaPath);
    storage.insert(storage.end(), jvmArgs.begin(), jvmArgs.end());
    storage.push_back(mainClass);
    storage.insert(storage.end(), gameArgs.begin(), gameArgs.end());

    int argc = 0;
    auto argv = buildArgv(storage, argc);
    const bool executableBit = access(javaPath.c_str(), X_OK) == 0;

    std::fprintf(stderr, "[Project Astra alpha19] Minecraft LaunchPlan handoff\n");
    std::fprintf(stderr, "libjli=%s\n", jliPath.c_str());
    std::fprintf(stderr, "java=%s\n", javaPath.c_str());
    std::fprintf(stderr, "JAVA_HOME=%s\n", javaHome.c_str());
    std::fprintf(stderr, "working_directory=%s\n", workDir.c_str());
    std::fprintf(stderr, "main_class=%s\n", mainClass.c_str());
    std::fprintf(stderr, "jvm_args=%zu game_args=%zu\n", jvmArgs.size(), gameArgs.size());
    std::fprintf(stderr, "LD_LIBRARY_PATH=%s\n", ldLibraryPath.c_str());
    std::fprintf(stderr, "renderer_env=POJAV_RENDERER:%s AMETHYST_RENDERER:%s POJAV_LOAD_TURNIP:%s\n",
        environmentOrUnset("POJAV_RENDERER"),
        environmentOrUnset("AMETHYST_RENDERER"),
        environmentOrUnset("POJAV_LOAD_TURNIP"));
    std::fprintf(stderr, "java_mode=%04o X_OK=%s\n", javaStat.st_mode & 07777, executableBit ? "yes" : "no");
    std::fprintf(stderr, "argc=%d argv_null_terminated=%s\n", argc, argv[argc] == nullptr ? "yes" : "no");
    std::fprintf(stderr, "runtime_preload_handles=%zu\n", gRuntimePreloadHandles.size());
    std::fprintf(stderr, "Argument values intentionally omitted from this header to avoid exposing account tokens.\n\n");
    std::fflush(stderr);

    const int result = launch(
        argc,
        argv.data(),
        0,
        nullptr,
        0,
        nullptr,
        "Project Astra 0.1.0-alpha19",
        "0.1.0-alpha19",
        "java",
        "java",
        JNI_FALSE,
        JNI_FALSE,
        JNI_FALSE,
        0
    );

    capture.restore();
    if (result == 0) {
        return fromString(env, "OK|Minecraft main class retornou 0");
    }
    return fromString(env, "ERROR|Minecraft/JLI retornou código " + std::to_string(result));
}
