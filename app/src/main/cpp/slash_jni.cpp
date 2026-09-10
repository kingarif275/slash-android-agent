#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char * TAG = "SlashNative";
std::mutex runtime_mutex;
std::atomic<bool> cancel_requested{false};
llama_model * model = nullptr;
llama_context * context = nullptr;
llama_sampler * sampler = nullptr;
const llama_vocab * vocab = nullptr;
std::vector<llama_token> cached_sequence;
int configured_batch = 256;
bool backend_initialized = false;

void log_info(const std::string & message) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
}

void log_error(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

std::string from_java(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void destroy_runtime_locked() {
    if (sampler != nullptr) {
        llama_sampler_free(sampler);
        sampler = nullptr;
    }
    if (context != nullptr) {
        llama_free(context);
        context = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
    vocab = nullptr;
    cached_sequence.clear();
}

void destroy_backend_locked() {
    destroy_runtime_locked();
    if (backend_initialized) {
        llama_backend_free();
        backend_initialized = false;
    }
}

std::vector<llama_token> tokenize(const std::string & prompt) {
    int32_t count = -llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                    nullptr, 0, true, true);
    if (count <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    int32_t actual = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                    tokens.data(), count, true, true);
    if (actual < 0) return {};
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

std::string token_piece(llama_token token) {
    std::vector<char> buffer(256);
    int32_t size = llama_token_to_piece(vocab, token, buffer.data(),
                                        static_cast<int32_t>(buffer.size()), 0, true);
    if (size < 0) {
        buffer.resize(static_cast<size_t>(-size));
        size = llama_token_to_piece(vocab, token, buffer.data(),
                                    static_cast<int32_t>(buffer.size()), 0, true);
    }
    return size > 0 ? std::string(buffer.data(), static_cast<size_t>(size)) : std::string();
}

bool decode_tokens(const std::vector<llama_token> & tokens, size_t start) {
    size_t offset = start;
    while (offset < tokens.size()) {
        if (cancel_requested.load()) return false;
        int32_t count = static_cast<int32_t>(std::min<size_t>(
                static_cast<size_t>(configured_batch), tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(
                const_cast<llama_token *>(tokens.data() + offset), count);
        int32_t result = llama_decode(context, batch);
        if (result != 0) {
            log_error("PROMPT_DECODE_FAILED code=" + std::to_string(result));
            return false;
        }
        cached_sequence.insert(cached_sequence.end(), tokens.begin() + static_cast<long>(offset),
                               tokens.begin() + static_cast<long>(offset + count));
        offset += static_cast<size_t>(count);
    }
    return true;
}

size_t prepare_prefix(const std::vector<llama_token> & prompt_tokens) {
    size_t prefix = 0;
    size_t comparable = std::min(cached_sequence.size(), prompt_tokens.size());
    while (prefix < comparable && cached_sequence[prefix] == prompt_tokens[prefix]) prefix++;

    // Re-evaluate the final prompt token even for an exact match. llama.cpp does
    // not retain usable next-token logits after we prune a previous completion.
    if (prefix == prompt_tokens.size() && prefix > 0) prefix--;

    llama_memory_t memory = llama_get_memory(context);
    if (prefix < cached_sequence.size()) {
        bool removed = llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(prefix), -1);
        if (!removed) {
            llama_memory_clear(memory, true);
            prefix = 0;
        }
        cached_sequence.resize(prefix);
    }
    return prefix;
}

std::string metrics_json(size_t prompt_total, size_t reused, size_t generated,
                         long long prefill_ms, long long generation_ms) {
    std::ostringstream out;
    out << "{\"kvCacheReuseEnabled\":true"
        << ",\"promptTotalTokens\":" << prompt_total
        << ",\"prefixMatchTokens\":" << reused
        << ",\"promptReusedTokens\":" << reused
        << ",\"promptNewTokens\":" << (prompt_total - reused)
        << ",\"generatedTokens\":" << generated
        << ",\"promptDecodeMs\":" << prefill_ms
        << ",\"generationMs\":" << generation_ms << "}";
    return out.str();
}

bool call_token(JNIEnv * env, jobject callback, jmethodID method, const std::string & piece) {
    jbyteArray value = env->NewByteArray(static_cast<jsize>(piece.size()));
    if (value == nullptr) return false;
    if (!piece.empty()) {
        env->SetByteArrayRegion(value, 0, static_cast<jsize>(piece.size()),
                                reinterpret_cast<const jbyte *>(piece.data()));
    }
    env->CallVoidMethod(callback, method, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

void call_error(JNIEnv * env, jobject callback, jmethodID method, const std::string & message) {
    jstring value = env->NewStringUTF(message.c_str());
    if (value == nullptr) return;
    env->CallVoidMethod(callback, method, value);
    env->DeleteLocalRef(value);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_slash_agent_EmbeddedLlamaRuntime_nativeProbeModel(
        JNIEnv * env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    bool initialized_here = false;
    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
        initialized_here = true;
    }
    std::string model_path = from_java(env, path);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model * candidate = llama_model_load_from_file(model_path.c_str(), params);
    bool success = candidate != nullptr;
    if (candidate != nullptr) llama_model_free(candidate);
    if (initialized_here) {
        llama_backend_free();
        backend_initialized = false;
    }
    log_info(std::string("NATIVE_MODEL_PROBE ") + (success ? "success" : "failed")
             + " path=" + model_path);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_slash_agent_EmbeddedLlamaRuntime_nativeLoad(
        JNIEnv * env, jobject, jstring path, jint context_length, jint batch_size, jint threads) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    log_info("NATIVE_LOAD_START");
    destroy_backend_locked();
    cancel_requested.store(false);
    llama_backend_init();
    backend_initialized = true;

    std::string model_path = from_java(env, path);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        log_error("NATIVE_MODEL_LOAD_FAILED path=" + model_path);
        destroy_backend_locked();
        return JNI_FALSE;
    }

    configured_batch = std::max(32, static_cast<int>(batch_size));
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(std::max(1024, static_cast<int>(context_length)));
    context_params.n_batch = static_cast<uint32_t>(configured_batch);
    context_params.n_ubatch = static_cast<uint32_t>(std::min(configured_batch, 128));
    context_params.n_threads = std::max(1, static_cast<int>(threads));
    context_params.n_threads_batch = std::max(1, static_cast<int>(threads));
    context_params.no_perf = false;
    context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        log_error("NATIVE_CONTEXT_CREATE_FAILED");
        destroy_backend_locked();
        return JNI_FALSE;
    }

    vocab = llama_model_get_vocab(model);
    sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    log_info("JNI_BRIDGE_READY NATIVE_MODEL_LOAD_SUCCESS KV_CACHE_REUSE_ENABLED n_ctx="
             + std::to_string(context_params.n_ctx) + " n_batch=" + std::to_string(configured_batch));
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_slash_agent_EmbeddedLlamaRuntime_nativeGenerateStreaming(
        JNIEnv * env, jobject, jstring prompt_value, jint max_tokens, jobject callback) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (model == nullptr || context == nullptr || sampler == nullptr || callback == nullptr) return;
    cancel_requested.store(false);

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "([B)V");
    jmethodID on_complete = env->GetMethodID(callback_class, "onComplete", "(Ljava/lang/String;)V");
    jmethodID on_error = env->GetMethodID(callback_class, "onError", "(Ljava/lang/String;)V");
    if (on_token == nullptr || on_complete == nullptr || on_error == nullptr) {
        env->ExceptionClear();
        return;
    }

    std::string prompt = from_java(env, prompt_value);
    log_info("NATIVE_INFERENCE_START prompt_bytes=" + std::to_string(prompt.size())
             + " max_tokens=" + std::to_string(max_tokens));
    std::vector<llama_token> prompt_tokens = tokenize(prompt);
    if (prompt_tokens.empty()) {
        call_error(env, callback, on_error, "Tokenization failed.");
        return;
    }
    if (prompt_tokens.size() + static_cast<size_t>(max_tokens) > llama_n_ctx(context)) {
        call_error(env, callback, on_error, "The compact prompt exceeds the selected model context.");
        return;
    }

    auto prefill_start = std::chrono::steady_clock::now();
    size_t reused = prepare_prefix(prompt_tokens);
    if (!decode_tokens(prompt_tokens, reused)) {
        call_error(env, callback, on_error, cancel_requested.load() ? "Generation cancelled." : "Prompt decoding failed.");
        return;
    }
    auto prefill_end = std::chrono::steady_clock::now();
    llama_sampler_reset(sampler);

    size_t generated = 0;
    auto generation_start = std::chrono::steady_clock::now();
    while (generated < static_cast<size_t>(std::max(1, static_cast<int>(max_tokens))) && !cancel_requested.load()) {
        llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        llama_sampler_accept(sampler, token);
        std::string piece = token_piece(token);

        std::vector<llama_token> one{token};
        if (!decode_tokens(one, 0)) {
            call_error(env, callback, on_error, "Token decoding failed.");
            return;
        }
        generated++;
        if (!piece.empty() && !call_token(env, callback, on_token, piece)) {
            cancel_requested.store(true);
            return;
        }
    }
    auto generation_end = std::chrono::steady_clock::now();
    long long prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(prefill_end - prefill_start).count();
    long long generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(generation_end - generation_start).count();
    std::string metrics = metrics_json(prompt_tokens.size(), reused, generated, prefill_ms, generation_ms);
    log_info("KV_CACHE_REUSE_ENABLED PREFIX_MATCH_TOKENS=" + std::to_string(reused)
             + " PROMPT_TOTAL_TOKENS=" + std::to_string(prompt_tokens.size())
             + " PROMPT_REUSED_TOKENS=" + std::to_string(reused)
             + " PROMPT_NEW_TOKENS=" + std::to_string(prompt_tokens.size() - reused)
             + " PROMPT_DECODE_MS=" + std::to_string(prefill_ms));
    jstring result = env->NewStringUTF(metrics.c_str());
    if (result != nullptr) {
        env->CallVoidMethod(callback, on_complete, result);
        env->DeleteLocalRef(result);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_slash_agent_EmbeddedLlamaRuntime_nativeCancel(JNIEnv *, jobject) {
    cancel_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_slash_agent_EmbeddedLlamaRuntime_nativeUnload(JNIEnv *, jobject) {
    cancel_requested.store(true);
    std::lock_guard<std::mutex> lock(runtime_mutex);
    destroy_backend_locked();
    log_info("NATIVE_MODEL_UNLOADED");
}
