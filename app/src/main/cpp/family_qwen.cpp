#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <sched.h>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"
#include "ggml-backend.h"

namespace {
using Clock = std::chrono::steady_clock;
struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<int64_t> epoch{0};
    int64_t request_epoch = 0;
    Clock::time_point deadline;
    int prefix_count = 0;
    bool cache_prefix = false;
    int cpu_strategy = 0;
    int64_t request_number = 0;
    int64_t prompt_tokens = 0, generated_tokens = 0, latency_ms = 0;
    ~Engine() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};
std::once_flag backend_once;
void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string utf8(JNIEnv * env, jstring value) {
    const char * p = env->GetStringUTFChars(value, nullptr);
    if (!p) throw std::runtime_error("Unable to read JNI string");
    std::string result(p);
    env->ReleaseStringUTFChars(value, p);
    return result;
}
Engine & get(jlong pointer) {
    if (!pointer) throw std::runtime_error("Qwen engine is closed");
    return *reinterpret_cast<Engine *>(pointer);
}
bool aborted(void * opaque) {
    auto & e = *static_cast<Engine *>(opaque);
    return e.epoch.load() != e.request_epoch || Clock::now() > e.deadline;
}
void check_request(Engine & e) {
    if (aborted(&e)) throw std::runtime_error("Qwen request cancelled or exceeded 20 seconds");
}
// Apply affinity only to this JNI worker; newly created GGML workers inherit it.
// Restore the caller's original mask on success, cancellation and exceptions.
struct AffinityScope {
    cpu_set_t original{};
    bool available = false;
    explicit AffinityScope(int strategy) {
        if (strategy && sched_getaffinity(0, sizeof(original), &original) == 0) {
            available = true;
            for (int i = 0; i < 8; ++i) available = available && CPU_ISSET(i, &original);
        }
    }
    bool use_fast(bool fast) {
        if (!available) return false;
        cpu_set_t mask;
        CPU_ZERO(&mask);
        for (int i = fast ? 4 : 0; i < 8; ++i) CPU_SET(i, &mask);
        return sched_setaffinity(0, sizeof(mask), &mask) == 0;
    }
    ~AffinityScope() { if (available) sched_setaffinity(0, sizeof(original), &original); }
};
struct Batch {
    llama_batch value;
    explicit Batch(int count) : value(llama_batch_init(count, 0, 1)) {
        if (!value.token || !value.pos || !value.seq_id || !value.logits) {
            llama_batch_free(value);
            throw std::runtime_error("Cannot allocate llama batch");
        }
    }
    ~Batch() { llama_batch_free(value); }
    void set(int i, llama_token token, int pos, bool logits) {
        value.token[i] = token;
        value.pos[i] = pos;
        value.n_seq_id[i] = 1;
        value.seq_id[i][0] = 0;
        value.logits[i] = logits;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_create(JNIEnv * env, jobject, jstring library_dir, jstring model_path) {
    try {
        const auto dir = utf8(env, library_dir);
        const auto path = utf8(env, model_path);
        std::call_once(backend_once, [&] {
            // Only CPU GGML variants are packaged; no accelerator backend is loaded.
            ggml_backend_load_all_from_path(dir.c_str());
            llama_backend_init();
        });
        if (ggml_backend_dev_count() == 0) throw std::runtime_error("No CPU GGML backend");
        auto engine = std::make_unique<Engine>();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        engine->model = llama_model_load_from_file(path.c_str(), mp);
        if (!engine->model) throw std::runtime_error("Cannot load Qwen GGUF");
        auto cp = llama_context_default_params();
        cp.n_ctx = 512;
        cp.n_batch = 256;
        cp.n_ubatch = 128;
        cp.n_threads = 4;
        cp.n_threads_batch = 8;
        engine->context = llama_init_from_model(engine->model, cp);
        if (!engine->context) throw std::runtime_error("Cannot initialize Qwen context");
        llama_set_abort_callback(engine->context, aborted, engine.get());
        __android_log_print(ANDROID_LOG_INFO, "FamilyVoiceLab",
            "qwenNativeReady cpuOnly=true context=512 promptThreads=8 generationThreads=4");
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_preparePrefix(JNIEnv * env, jobject, jlong handle, jbyteArray input, jboolean cache) {
    try {
        auto & e = get(handle);
        int size = env->GetArrayLength(input);
        std::string text(size, '\0');
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(text.data()));
        std::vector<llama_token> tokens(size + 16);
        e.prefix_count = llama_tokenize(llama_model_get_vocab(e.model), text.data(), size,
            tokens.data(), tokens.size(), true, true);
        if (e.prefix_count <= 0 || e.prefix_count > 400) throw std::runtime_error("Invalid constant prefix");
        e.cache_prefix = cache;
        llama_memory_clear(llama_get_memory(e.context), true);
        if (e.cache_prefix) {
            const auto start = Clock::now();
            e.request_epoch = e.epoch.load();
            e.deadline = start + std::chrono::seconds(20);
            Batch batch(256);
            for (int offset = 0; offset < e.prefix_count; offset += 256) {
                check_request(e);
                int n = std::min(256, e.prefix_count - offset);
                batch.value.n_tokens = n;
                for (int i = 0; i < n; ++i) batch.set(i, tokens[offset+i], offset+i, false);
                if (llama_decode(e.context, batch.value) != 0)
                    throw std::runtime_error("Qwen prefix initialization failed");
            }
            check_request(e);
            __android_log_print(ANDROID_LOG_INFO, "FamilyVoiceLab",
                "QWEN_PREFIX constantPrefixTokens=%d prefixPromptEvalMs=%.3f evaluatedOnce=true",
                e.prefix_count, std::chrono::duration<double,std::milli>(Clock::now()-start).count());
        }
    } catch (const std::exception & error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_configureCpu(JNIEnv * env, jobject, jlong handle, jint strategy) {
    try {
        if (strategy < 0 || strategy > 2) throw std::runtime_error("Unknown CPU strategy");
        get(handle).cpu_strategy = strategy;
    } catch (const std::exception & error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_generate(JNIEnv * env, jobject, jlong handle,
        jbyteArray input, jint max_tokens, jlong ticket) {
    try {
        auto & e = get(handle);
        if (max_tokens < 1 || max_tokens > 16) throw std::runtime_error("Invalid output token limit");
        const auto start = Clock::now();
        AffinityScope affinity(e.cpu_strategy);
        bool prompt_affinity = affinity.use_fast(e.cpu_strategy == 2);
        llama_set_n_threads(e.context, 4, e.cpu_strategy == 2 && prompt_affinity ? 4 : 8);
        e.deadline = start + std::chrono::seconds(20);
        e.request_epoch = ticket;
        check_request(e);
        const int size = env->GetArrayLength(input);
        if (size < 1 || size > 8192) throw std::runtime_error("Invalid prompt size");
        std::string prompt(size, '\0');
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(prompt.data()));
        const auto * vocab = llama_model_get_vocab(e.model);
        std::vector<llama_token> tokens(size + 16);
        int token_count = llama_tokenize(vocab, prompt.data(), size, tokens.data(), tokens.size(), !e.cache_prefix, true);
        int base = e.cache_prefix ? e.prefix_count : 0;
        int count = base + token_count;
        if (token_count <= 0 || count <= e.prefix_count || count + max_tokens > static_cast<int>(llama_n_ctx(e.context)))
            throw std::runtime_error("Prompt exceeds Qwen context");
        auto tokenized = Clock::now();
        ++e.request_number;
        // Positions [0, prefix_count) remain immutable. Remove the previous utterance
        // and generation only; there is no conversation history or growing sequence.
        if (e.cache_prefix) {
            if (!llama_memory_seq_rm(llama_get_memory(e.context), 0, e.prefix_count, -1))
                throw std::runtime_error("Qwen cannot reset utterance KV tail");
        } else {
            llama_memory_clear(llama_get_memory(e.context), true); // Benchmark control.
        }
        e.prompt_tokens = count;
        e.generated_tokens = 0;
        Batch batch(256);
        auto eval_start = Clock::now();
        auto prefix_end = eval_start;
        for (int offset = base; offset < count;) {
            check_request(e);
            int boundary = offset < e.prefix_count ? e.prefix_count : count;
            int n = std::min(256, boundary - offset);
            batch.value.n_tokens = n;
            for (int i = 0; i < n; ++i)
                batch.set(i, tokens[offset + i - base], offset + i, offset + i == count - 1);
            if (llama_decode(e.context, batch.value) != 0) throw std::runtime_error("Qwen prompt decode failed/cancelled");
            offset += n;
            if (offset == e.prefix_count) prefix_end = Clock::now();
        }
        auto generation_start = Clock::now();
        bool generation_affinity = affinity.use_fast(true);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Cannot initialize greedy sampler");
        std::string output;
        bool complete = false;
        Batch next(1);
        for (int i = 0; i < max_tokens; ++i) {
            check_request(e);
            auto token = llama_sampler_sample(sampler.get(), e.context, -1);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            std::vector<char> piece(128);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) {
                piece.resize(-n);
                n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            }
            if (n < 0) throw std::runtime_error("Qwen token conversion failed");
            output.append(piece.data(), n);
            ++e.generated_tokens;
            if (output.find('\n') != std::string::npos && output.find_first_not_of(" \r\n") != std::string::npos) {
                complete = true; break;
            }
            next.value.n_tokens = 1;
            next.set(0, token, count + i, true);
            if (llama_decode(e.context, next.value) != 0) throw std::runtime_error("Qwen generation failed/cancelled");
        }
        check_request(e);
        if (!complete) throw std::runtime_error("Qwen output token limit reached; incomplete answer rejected");
        e.latency_ms = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
        auto end = Clock::now();
        auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b-a).count(); };
        __android_log_print(ANDROID_LOG_INFO, "FamilyVoiceLab",
            "QWEN_PROFILE request=%lld cachePrefix=%d cpuStrategy=%d promptAffinity=%d generationAffinity=%d tokenizationMs=%.3f prefixPromptEvalMs=%.3f userPromptEvalMs=%.3f generationMs=%.3f resetMs=%.3f totalNativeMs=%.3f totalPromptTokens=%d constantPrefixTokens=%d prefixEvaluatedTokens=%d newUserTokens=%d generatedTokens=%lld promptThreads=%d generationThreads=%d",
            (long long)e.request_number, e.cache_prefix, e.cpu_strategy, prompt_affinity, generation_affinity, ms(start,tokenized), ms(eval_start,prefix_end),
            ms(prefix_end,generation_start), ms(generation_start,end), ms(tokenized,eval_start), ms(start,end), count,
            e.prefix_count,e.cache_prefix ? 0 : e.prefix_count,count-e.prefix_count,(long long)e.generated_tokens,
            llama_n_threads_batch(e.context),llama_n_threads(e.context));
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_ticket(JNIEnv *, jobject, jlong handle) {
    return reinterpret_cast<Engine *>(handle)->epoch.load();
}
extern "C" JNIEXPORT void JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_cancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Engine *>(handle)->epoch.fetch_add(1);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_metrics(JNIEnv * env, jobject, jlong handle) {
    auto & e = *reinterpret_cast<Engine *>(handle);
    jlong values[] = {e.prompt_tokens, e.generated_tokens, e.latency_ms};
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_ru_familytv_voicelab_llm_NativeQwen_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
