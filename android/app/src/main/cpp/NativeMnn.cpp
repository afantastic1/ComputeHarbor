/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#include "NativeMnn.h"

#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <llm/llm.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <exception>
#include <iomanip>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <streambuf>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

constexpr jint kSuccess = 0;
constexpr jint kCancelled = 1;
constexpr jint kLength = 7;
constexpr jint kUnsupported = 2;
constexpr jint kInvalidConfig = 3;
constexpr jint kLoadFailed = 4;
constexpr jint kGenerationFailed = 5;
constexpr jint kUnavailable = 6;
constexpr size_t kMaxChunkBytes = 64 * 1024;
constexpr size_t kMaxResponseBytes = 4 * 1024 * 1024;

struct Session {
    std::mutex lifecycleMutex;
    std::mutex generationMutex;
    std::atomic<jlong> activeRequestId{0};
    std::atomic<jlong> cancelledRequestId{0};
    MNN::Transformer::Llm* llm = nullptr;
    jint backend = 0;
    jlong promptTokens = 0;
    jlong generatedTokens = 0;
    jlong durationMillis = 0;
};

std::string Utf16ToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);
    for (jsize index = 0; index < length; ++index) {
        uint32_t codePoint = chars[index];
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF && index + 1 < length) {
            const uint32_t low = chars[index + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                ++index;
            }
        }
        if (codePoint <= 0x7F) {
            result.push_back(static_cast<char>(codePoint));
        } else if (codePoint <= 0x7FF) {
            result.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else if (codePoint <= 0xFFFF) {
            result.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else {
            result.push_back(static_cast<char>(0xF0 | (codePoint >> 18)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

std::string EscapeJsonString(const std::string& value) {
    std::ostringstream escaped;
    for (const unsigned char character : value) {
        switch (character) {
            case '"': escaped << "\\\""; break;
            case '\\': escaped << "\\\\"; break;
            case '\b': escaped << "\\b"; break;
            case '\f': escaped << "\\f"; break;
            case '\n': escaped << "\\n"; break;
            case '\r': escaped << "\\r"; break;
            case '\t': escaped << "\\t"; break;
            default:
                if (character < 0x20) {
                    escaped << "\\u00" << std::hex << std::setw(2) << std::setfill('0')
                            << static_cast<int>(character) << std::dec << std::setfill(' ');
                } else {
                    escaped << static_cast<char>(character);
                }
        }
    }
    return escaped.str();
}

jstring Utf8ToJString(JNIEnv* env, const std::string& value) {
    std::vector<jchar> output;
    output.reserve(value.size());
    size_t index = 0;
    while (index < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[index]);
        uint32_t codePoint = 0xFFFD;
        size_t length = 1;
        if (first <= 0x7F) {
            codePoint = first;
        } else if ((first & 0xE0) == 0xC0 && index + 1 < value.size()) {
            codePoint = ((first & 0x1F) << 6) | (static_cast<uint8_t>(value[index + 1]) & 0x3F);
            length = 2;
        } else if ((first & 0xF0) == 0xE0 && index + 2 < value.size()) {
            codePoint = ((first & 0x0F) << 12) |
                        ((static_cast<uint8_t>(value[index + 1]) & 0x3F) << 6) |
                        (static_cast<uint8_t>(value[index + 2]) & 0x3F);
            length = 3;
        } else if ((first & 0xF8) == 0xF0 && index + 3 < value.size()) {
            codePoint = ((first & 0x07) << 18) |
                        ((static_cast<uint8_t>(value[index + 1]) & 0x3F) << 12) |
                        ((static_cast<uint8_t>(value[index + 2]) & 0x3F) << 6) |
                        (static_cast<uint8_t>(value[index + 3]) & 0x3F);
            length = 4;
        }
        index += length;
        if (codePoint <= 0xFFFF) {
            output.push_back(static_cast<jchar>(codePoint));
        } else {
            codePoint -= 0x10000;
            output.push_back(static_cast<jchar>(0xD800 + (codePoint >> 10)));
            output.push_back(static_cast<jchar>(0xDC00 + (codePoint & 0x3FF)));
        }
    }
    return env->NewString(output.data(), static_cast<jsize>(output.size()));
}

class AttachedEnv {
public:
    explicit AttachedEnv(JavaVM* vm) : vm_(vm) {
        if (vm_ != nullptr && vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }
    ~AttachedEnv() {
        if (attached_) {
            vm_->DetachCurrentThread();
        }
    }
    JNIEnv* get() const { return env_; }

private:
    JavaVM* vm_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

class CallbackStreamBuffer final : public std::streambuf {
public:
    CallbackStreamBuffer(JNIEnv* env, jobject callback, jmethodID method)
        : env_(env), callback_(callback), method_(method) {
        pending_.reserve(1024);
    }

    bool flush() {
        if (pending_.empty()) {
            return true;
        }
        if (pending_.size() > kMaxChunkBytes) {
            failed_ = true;
            return false;
        }
        if (flushedBytes_ + pending_.size() > kMaxResponseBytes) {
            failed_ = true;
            return false;
        }
        jstring chunk = Utf8ToJString(env_, pending_);
        if (chunk == nullptr) {
            failed_ = true;
            return false;
        }
        env_->CallVoidMethod(callback_, method_, chunk);
        env_->DeleteLocalRef(chunk);
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            failed_ = true;
            return false;
        }
        flushedBytes_ += pending_.size();
        pending_.clear();
        return true;
    }

    bool failed() const { return failed_; }

protected:
    std::streamsize xsputn(const char* source, std::streamsize count) override {
        if (count < 0 || pending_.size() + static_cast<size_t>(count) > kMaxChunkBytes) {
            failed_ = true;
            return 0;
        }
        pending_.append(source, static_cast<size_t>(count));
        return count;
    }

    int_type overflow(int_type character) override {
        if (traits_type::eq_int_type(character, traits_type::eof())) {
            return traits_type::not_eof(character);
        }
        if (pending_.size() >= kMaxChunkBytes) {
            failed_ = true;
            return traits_type::eof();
        }
        pending_.push_back(traits_type::to_char_type(character));
        return character;
    }

private:
    JNIEnv* env_;
    jobject callback_;
    jmethodID method_;
    std::string pending_;
    size_t flushedBytes_ = 0;
    bool failed_ = false;
};

Session* ToSession(jlong handle) {
    return reinterpret_cast<Session*>(static_cast<uintptr_t>(handle));
}

#if PAIR_MNN_HAS_OPENCL
int ProbeOpenClRuntime() {
    try {
        MNN::ScheduleConfig config;
        config.type = MNN_FORWARD_OPENCL;
        config.numThread = 64 | 512;
        const std::vector<MNN::ScheduleConfig> configs{config};
        const MNN::RuntimeInfo runtime = MNN::Interpreter::createRuntime(configs);
        const auto openClRuntime = runtime.first.find(MNN_FORWARD_OPENCL);
        return openClRuntime != runtime.first.end() && openClRuntime->second != nullptr ? 0 : 2;
    } catch (...) {
        return 3;
    }
}
#endif

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("3.6.1");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeProbeOpenCl(JNIEnv*, jobject) {
#if !PAIR_MNN_HAS_OPENCL
    return 1;
#else
    try {
        return ProbeOpenClRuntime();
    } catch (...) {
        return 3;
    }
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeCreateSession(JNIEnv*, jobject) {
    auto* session = new (std::nothrow) Session();
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(session));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeLoadModel(JNIEnv* env, jobject, jlong handle, jstring configPath, jint backend) {
    Session* session = ToSession(handle);
    if (session == nullptr || configPath == nullptr) {
        return kInvalidConfig;
    }
    if (backend != 0 && backend != 1) {
        return kUnsupported;
    }
#if !PAIR_MNN_HAS_OPENCL
    if (backend == 1) {
        return kUnsupported;
    }
#else
    if (backend == 1 && ProbeOpenClRuntime() != 0) {
        return kUnsupported;
    }
#endif
    const std::string path = Utf16ToUtf8(env, configPath);
    if (path.empty()) {
        return kInvalidConfig;
    }

    std::lock_guard<std::mutex> guard(session->lifecycleMutex);
    if (session->activeRequestId.load() != 0) {
        return kLoadFailed;
    }
    if (session->llm != nullptr) {
        MNN::Transformer::Llm::destroy(session->llm);
        session->llm = nullptr;
    }
    session->promptTokens = 0;
    session->generatedTokens = 0;
    session->durationMillis = 0;

    try {
        using LlmPtr = std::unique_ptr<MNN::Transformer::Llm, void (*)(MNN::Transformer::Llm*)>;
        LlmPtr candidate(
            MNN::Transformer::Llm::createLLM(path),
            &MNN::Transformer::Llm::destroy
        );
        if (candidate == nullptr) {
            return kLoadFailed;
        }
        const char* backendName = backend == 0 ? "cpu" : "opencl";
        std::ostringstream runtimeConfig;
        runtimeConfig << "{\"backend_type\":\"" << backendName << "\"";
        if (backend == 1) {
            // Bound OpenCL prefill shapes so long agent prompts do not create one
            // very large allocation on memory-constrained Android devices. MNN
            // carries the KV state across chunks before decoding begins.
            runtimeConfig << ",\"chunk\":128";
            const size_t directoryEnd = path.find_last_of('/');
            if (directoryEnd != std::string::npos && directoryEnd > 0) {
                // OpenCL persists compiled kernels in tmp_path. Model directories
                // live in PAIR's app-private storage and are writable; the process
                // working directory is not a reliable Android cache location.
                runtimeConfig << ",\"tmp_path\":\""
                              << EscapeJsonString(path.substr(0, directoryEnd))
                              << "\"";
            }
        }
        runtimeConfig << "}";
        if (!candidate->set_config(runtimeConfig.str())) {
            __android_log_print(ANDROID_LOG_ERROR, "PAIR-MNN", "MNN rejected runtime backend configuration.");
            return kLoadFailed;
        }
        if (!candidate->load()) {
            __android_log_print(ANDROID_LOG_ERROR, "PAIR-MNN", "MNN model loading failed.");
            return kLoadFailed;
        }
        session->llm = candidate.release();
        session->backend = backend;
        session->promptTokens = 0;
        session->generatedTokens = 0;
        session->durationMillis = 0;
        return kSuccess;
    } catch (const std::exception&) {
        return kLoadFailed;
    } catch (...) {
        return kLoadFailed;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeGenerateChat(
    JNIEnv* callerEnv,
    jobject,
    jlong handle,
    jlong requestId,
    jobjectArray roles,
    jobjectArray contents,
    jstring toolsJson,
    jstring toolChoice,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jlong seed,
    jobject callback) {
    __android_log_print(ANDROID_LOG_INFO, "PAIR-MNN", "generation request=%lld entered native code", static_cast<long long>(requestId));
    Session* session = ToSession(handle);
    if (session == nullptr || roles == nullptr || contents == nullptr || toolsJson == nullptr || toolChoice == nullptr || callback == nullptr || requestId <= 0 || maxTokens <= 0) {
        return kGenerationFailed;
    }
    const jsize messageCount = callerEnv->GetArrayLength(roles);
    if (messageCount <= 0 || callerEnv->GetArrayLength(contents) != messageCount) {
        return kGenerationFailed;
    }
    MNN::Transformer::ChatMessages chatMessages;
    chatMessages.reserve(static_cast<size_t>(messageCount));
    for (jsize index = 0; index < messageCount; ++index) {
        auto role = static_cast<jstring>(callerEnv->GetObjectArrayElement(roles, index));
        auto content = static_cast<jstring>(callerEnv->GetObjectArrayElement(contents, index));
        if (role == nullptr || content == nullptr) {
            if (role != nullptr) callerEnv->DeleteLocalRef(role);
            if (content != nullptr) callerEnv->DeleteLocalRef(content);
            return kGenerationFailed;
        }
        std::string roleUtf8 = Utf16ToUtf8(callerEnv, role);
        std::string contentUtf8 = Utf16ToUtf8(callerEnv, content);
        callerEnv->DeleteLocalRef(role);
        callerEnv->DeleteLocalRef(content);
        if (roleUtf8.empty() || contentUtf8.empty()) {
            return kGenerationFailed;
        }
        chatMessages.emplace_back(std::move(roleUtf8), std::move(contentUtf8));
    }
    const std::string toolsJsonUtf8 = Utf16ToUtf8(callerEnv, toolsJson);
    const std::string toolChoiceUtf8 = Utf16ToUtf8(callerEnv, toolChoice);
    if (toolChoiceUtf8 == "required" || toolChoiceUtf8.rfind("function:", 0) == 0) {
        std::string directive = "You must call one of the provided tools and return a tool call only.";
        if (toolChoiceUtf8.rfind("function:", 0) == 0) {
            directive += " You must call the function named " + toolChoiceUtf8.substr(9) + ".";
        }
        chatMessages.insert(chatMessages.begin(), {"system", directive});
    }
    std::unique_lock<std::mutex> generationGuard(session->generationMutex, std::try_to_lock);
    if (!generationGuard.owns_lock() || session->llm == nullptr) {
        return kGenerationFailed;
    }
    jlong expected = 0;
    if (!session->activeRequestId.compare_exchange_strong(expected, requestId)) {
        return kGenerationFailed;
    }
    session->cancelledRequestId.store(0);
    struct ActiveRequestReset {
        Session* session;
        ~ActiveRequestReset() { session->activeRequestId.store(0); }
    } activeReset{session};

    JavaVM* vm = nullptr;
    if (callerEnv->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
        return kUnavailable;
    }
    jobject callbackGlobal = callerEnv->NewGlobalRef(callback);
    if (callbackGlobal == nullptr) {
        return kGenerationFailed;
    }
    std::atomic<jint> result{kGenerationFailed};
    std::thread worker([&]() {
        AttachedEnv attached(vm);
        JNIEnv* env = attached.get();
        if (env == nullptr) {
            result.store(kUnavailable);
            return;
        }
        jclass callbackClass = env->GetObjectClass(callbackGlobal);
        jmethodID onToken = callbackClass == nullptr ? nullptr : env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
        if (callbackClass != nullptr) {
            env->DeleteLocalRef(callbackClass);
        }
        if (onToken == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            result.store(kGenerationFailed);
            return;
        }

        auto started = std::chrono::steady_clock::now();
        const auto logStage = [&](const char* stage) {
            const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - started).count();
            __android_log_print(
                ANDROID_LOG_INFO,
                "PAIR-MNN",
                "generation request=%lld stage=%s elapsed_ms=%lld backend=%s",
                static_cast<long long>(requestId),
                stage,
                static_cast<long long>(elapsed),
                session->backend == 1 ? "opencl" : "cpu"
            );
        };
        CallbackStreamBuffer streamBuffer(env, callbackGlobal, onToken);
        std::ostream output(&streamBuffer);
        MNN::Transformer::Llm* llm = session->llm;
        try {
            std::ostringstream config;
            config << "{\"max_new_tokens\":" << maxTokens
                   << ",\"sampler_type\":\"" << (temperature <= 0.0f ? "greedy" : "topP") << "\""
                   << ",\"temperature\":" << temperature
                   << ",\"top_p\":" << topP
                   << ",\"jinja\":{\"context\":{\"enable_thinking\":false}}";
            if (seed >= 0) {
                config << ",\"seed\":" << seed;
            }
            config << "}";
            if (!llm->set_config(config.str())) {
                result.store(kGenerationFailed);
                return;
            }
            logStage("config-ready");
            llm->reset_sampler(seed);
            const std::string effectiveTools = toolChoiceUtf8 == "none" ? "[]" : toolsJsonUtf8;
            const std::string formattedPrompt = llm->apply_chat_template(chatMessages, effectiveTools);
            logStage("template-ready");
            const std::vector<int> inputIds = llm->tokenizer_encode(formattedPrompt);
            if (inputIds.empty()) {
                result.store(kGenerationFailed);
                return;
            }
            __android_log_print(
                    ANDROID_LOG_INFO,
                    "PAIR-MNN",
                    "request=%lld input_tokens=%zu max_new_tokens=%d backend=%s",
                    static_cast<long long>(requestId),
                    inputIds.size(),
                    static_cast<int>(maxTokens),
                    session->backend == 1 ? "opencl" : "cpu");
            logStage("tokenization-ready");

            llm->generate_init(&output);
            logStage("prefill-start");
            try {
                llm->generate(inputIds, 0);
            } catch (const std::exception&) {
                result.store(session->backend == 1 ? kUnsupported : kGenerationFailed);
                return;
            } catch (...) {
                result.store(session->backend == 1 ? kUnsupported : kGenerationFailed);
                return;
            }
            logStage("prefill-finished");
            if (streamBuffer.failed()) {
                result.store(kGenerationFailed);
                return;
            }
            for (jint tokenIndex = 0; tokenIndex < maxTokens; ++tokenIndex) {
                if (session->cancelledRequestId.load() == requestId) {
                    result.store(kCancelled);
                    break;
                }
                try {
                    if (tokenIndex == 0) logStage("first-token-start");
                    llm->generate(1);
                } catch (const std::exception&) {
                    result.store(session->backend == 1 ? kUnsupported : kGenerationFailed);
                    break;
                } catch (...) {
                    result.store(session->backend == 1 ? kUnsupported : kGenerationFailed);
                    break;
                }
                if (tokenIndex == 0) logStage("first-token-finished");
                if (!streamBuffer.flush() || streamBuffer.failed()) {
                    result.store(kGenerationFailed);
                    break;
                }
                const MNN::Transformer::LlmContext* context = llm->getContext();
                if (context == nullptr || context->status == MNN::Transformer::LlmStatus::TIMEOUT) {
                    result.store(kGenerationFailed);
                    break;
                }
                if (context->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR) {
                    result.store(session->backend == 1 ? kUnsupported : kGenerationFailed);
                    break;
                }
                if (llm->stoped() || context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
                    result.store(kSuccess);
                    break;
                }
                // generate(1) sets MAX_TOKENS_FINISHED after each incremental call.
                // That status is not terminal: the next call is allowed by MNN's
                // CHECK_LLM_RUNNING guard and produces the next token.
                if (context->status != MNN::Transformer::LlmStatus::RUNNING &&
                    context->status != MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED) {
                    result.store(kGenerationFailed);
                    break;
                }
                if (tokenIndex + 1 == maxTokens) {
                    result.store(kLength);
                }
            }
            if (session->cancelledRequestId.load() == requestId) {
                result.store(kCancelled);
            }
            if (streamBuffer.failed()) {
                result.store(kGenerationFailed);
            }
            const auto* context = llm->getContext();
            if (context != nullptr) {
                session->promptTokens = context->prompt_len > 0 ? context->prompt_len : static_cast<jlong>(inputIds.size());
                session->generatedTokens = static_cast<jlong>(context->output_tokens.size());
            }
            session->durationMillis = std::chrono::duration_cast<std::chrono::milliseconds>(
                                          std::chrono::steady_clock::now() - started)
                                          .count();
            if (result.load() == kGenerationFailed && session->generatedTokens > 0) {
                const auto* finalContext = llm->getContext();
                if (finalContext != nullptr && finalContext->status == MNN::Transformer::LlmStatus::RUNNING &&
                    session->generatedTokens >= maxTokens) {
                    result.store(kLength);
                }
            }
        } catch (const std::exception&) {
            result.store(kGenerationFailed);
        } catch (...) {
            result.store(kGenerationFailed);
        }
        logStage("generation-finished");
    });
    worker.join();
    callerEnv->DeleteGlobalRef(callbackGlobal);
    return result.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeCancel(JNIEnv*, jobject, jlong handle, jlong requestId) {
    Session* session = ToSession(handle);
    if (session != nullptr && session->activeRequestId.load() == requestId) {
        session->cancelledRequestId.store(requestId);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeUnloadModel(JNIEnv*, jobject, jlong handle) {
    Session* session = ToSession(handle);
    if (session == nullptr) {
        return kUnavailable;
    }
    std::lock_guard<std::mutex> guard(session->lifecycleMutex);
    if (session->activeRequestId.load() != 0) {
        return kLoadFailed;
    }
    if (session->llm != nullptr) {
        MNN::Transformer::Llm::destroy(session->llm);
        session->llm = nullptr;
    }
    session->promptTokens = 0;
    session->generatedTokens = 0;
    session->durationMillis = 0;
    session->backend = 0;
    return kSuccess;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeGetMetrics(JNIEnv* env, jobject, jlong handle) {
    Session* session = ToSession(handle);
    const jlong values[3] = {
        session == nullptr ? 0 : session->promptTokens,
        session == nullptr ? 0 : session->generatedTokens,
        session == nullptr ? 0 : session->durationMillis,
    };
    jlongArray result = env->NewLongArray(3);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 3, values);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nv_pair_mnn_NativeMnn_nativeDestroySession(JNIEnv*, jobject, jlong handle) {
    Session* session = ToSession(handle);
    if (session == nullptr || session->activeRequestId.load() != 0) {
        return;
    }
    {
        std::lock_guard<std::mutex> guard(session->lifecycleMutex);
        if (session->llm != nullptr) {
            MNN::Transformer::Llm::destroy(session->llm);
            session->llm = nullptr;
        }
    }
    delete session;
}
