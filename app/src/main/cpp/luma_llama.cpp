#include <jni.h>
#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <string>
#include <vector>
#include <stdexcept>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <new>

// Kotlin serializes load/generate/free. Cancellation alone can run concurrently.
static std::atomic<bool> cancelled{false};
static bool abort_decode(void *) { return cancelled.load(); }
static bool load_progress(float, void *) { return !cancelled.load(); }
static std::string bytes(JNIEnv * e, jbyteArray a) {
    if (!a) return "";
    auto n=e->GetArrayLength(a); std::string s(n,'\0');
    e->GetByteArrayRegion(a,0,n,reinterpret_cast<jbyte *>(s.data())); return s;
}
static void fail(JNIEnv * e,const std::string & message) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),message.c_str()); }
// llama.cpp returns nullptr after catching loader exceptions. Preserve the failure
// category before it is discarded; never expose paths, tensor dumps or prompts in UI.
static thread_local bool loading=false;
static thread_local bool unsupported_arch=false;
static thread_local bool allocation_failed=false;
static thread_local bool incompatible_cache=false;
static void native_log(ggml_log_level level,const char * text,void *) {
    if(loading && level==GGML_LOG_LEVEL_ERROR && text) {
        if(std::strstr(text,"unknown model architecture") || std::strstr(text,"unsupported model architecture")) unsupported_arch=true;
        if(std::strstr(text,"cache type") || std::strstr(text,"quantized V cache") || std::strstr(text,"Flash Attention")) incompatible_cache=true;
        if(std::strstr(text,"failed to allocate") || std::strstr(text,"cannot allocate memory") || std::strstr(text,"bad_alloc")) allocation_failed=true;
    }
    if(text) std::fputs(text,stderr);
}
struct LoadDiagnostics {
    LoadDiagnostics() {unsupported_arch=false;allocation_failed=false;incompatible_cache=false;loading=true;}
    ~LoadDiagnostics() {loading=false;}
};
extern "C" JNIEXPORT void JNICALL Java_dev_luma_agent_core_LlamaNative_resetCancel(JNIEnv *,jobject) {cancelled=false;}
extern "C" JNIEXPORT void JNICALL Java_dev_luma_agent_core_LlamaNative_cancel(JNIEnv *,jobject) {cancelled=true;}
extern "C" JNIEXPORT jlong JNICALL Java_dev_luma_agent_core_LlamaNative_load(JNIEnv * e,jobject,jbyteArray path) {
    try {
        llama_log_set(native_log,nullptr);
        LoadDiagnostics diagnostics;
        llama_backend_init(); auto p=llama_model_default_params();p.n_gpu_layers=0;
        p.progress_callback=load_progress;
        auto model=llama_model_load_from_file(bytes(e,path).c_str(),p);
        if (!model) throw std::runtime_error(cancelled ? "LUMA_CANCELLED" : unsupported_arch ? "LUMA_ARCH" : allocation_failed ? "LUMA_MEMORY" : "LUMA_MODEL");
        if(!llama_model_has_decoder(model) || llama_model_has_encoder(model)) {llama_model_free(model);throw std::runtime_error("LUMA_MODEL_TYPE");}
        return reinterpret_cast<jlong>(model);
    } catch(const std::bad_alloc &) {fail(e,"LUMA_MEMORY");return 0;}
      catch(const std::exception & ex) {fail(e,ex.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_luma_agent_core_LlamaNative_free(JNIEnv *,jobject,jlong h) {if(h) llama_model_free(reinterpret_cast<llama_model *>(h));}

static ggml_type cache_type(int code) {
    switch(code) {
        case 0: return GGML_TYPE_F16;
        case 1: return GGML_TYPE_Q8_0;
        case 2: return GGML_TYPE_Q5_0;
        case 3: return GGML_TYPE_Q4_0;
        case 4: return GGML_TYPE_IQ4_NL;
        default: throw std::runtime_error("LUMA_KV_CACHE");
    }
}

extern "C" JNIEXPORT jobject JNICALL Java_dev_luma_agent_core_LlamaNative_generate(JNIEnv * e,jobject,jlong h,jobjectArray role_array,jobjectArray content_array,jint context_size,jint max_output,jfloat temp,jfloat top_p,jint threads,jint cache_k,jint cache_v,jobject progress) {
    try {
        auto started=std::chrono::steady_clock::now();
        auto elapsed=[&](){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count();};
        auto progress_type=e->GetObjectClass(progress);
        auto progress_method=e->GetMethodID(progress_type,"onProgress","(IIJJ)V");
        auto model=reinterpret_cast<llama_model *>(h);
        if (!model) throw std::runtime_error("LUMA_MODEL");
        std::vector<std::string> roles,contents;
        auto count=e->GetArrayLength(role_array);roles.reserve(count);contents.reserve(count);
        for (int i=0;i<count;++i) {
            auto role=(jbyteArray)e->GetObjectArrayElement(role_array,i);auto text=(jbyteArray)e->GetObjectArrayElement(content_array,i);
            roles.push_back(bytes(e,role));contents.push_back(bytes(e,text));e->DeleteLocalRef(role);e->DeleteLocalRef(text);
        }
        std::vector<llama_chat_message> messages;
        for(int i=0;i<count;++i) messages.push_back({roles[i].c_str(),contents[i].c_str()});
        auto tmpl=llama_model_chat_template(model,nullptr);
        std::vector<char> formatted(4096);
        int length=llama_chat_apply_template(tmpl,messages.data(),messages.size(),true,formatted.data(),formatted.size());
        if(length<0) throw std::runtime_error("LUMA_TEMPLATE");
        if(length>=(int)formatted.size()) {formatted.resize(length+1);length=llama_chat_apply_template(tmpl,messages.data(),messages.size(),true,formatted.data(),formatted.size());}
        const auto vocab=llama_model_get_vocab(model);
        const int n=-llama_tokenize(vocab,formatted.data(),length,nullptr,0,true,true);
        const int n_ctx=std::min((int)context_size,llama_model_n_ctx_train(model));
        if(n<=0) throw std::runtime_error("LUMA_TOKENIZE");
        if(n+32>=n_ctx) throw std::runtime_error("LUMA_CONTEXT:"+std::to_string(n)+":"+std::to_string(n_ctx));
        std::vector<llama_token> tokens(n);
        if(llama_tokenize(vocab,formatted.data(),length,tokens.data(),tokens.size(),true,true)<0) throw std::runtime_error("LUMA_TOKENIZE");
        auto cp=llama_context_default_params();cp.n_ctx=n_ctx;cp.n_batch=128;cp.n_ubatch=128;
        cp.n_threads=threads;cp.n_threads_batch=threads;cp.abort_callback=abort_decode;
        cp.type_k=cache_type(cache_k);cp.type_v=cache_type(cache_v);
        // Quantized V requires Flash Attention, including on the CPU backend.
        if(ggml_is_quantized(cp.type_v)) cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_ENABLED;
        std::unique_ptr<llama_context,decltype(&llama_free)> ctx(nullptr,llama_free);
        {
            LoadDiagnostics diagnostics;
            ctx.reset(llama_init_from_model(model,cp));
            if(!ctx) throw std::runtime_error(incompatible_cache ? "LUMA_KV_CACHE" : "LUMA_MEMORY");
        }
        if(llama_pooling_type(ctx.get())!=LLAMA_POOLING_TYPE_NONE) throw std::runtime_error("LUMA_MODEL_TYPE");
        for(int i=0;i<n;i+=128) {
            if(cancelled) throw std::runtime_error("LUMA_CANCELLED");
            auto batch=llama_batch_get_one(tokens.data()+i,std::min(128,n-i));
            if(llama_decode(ctx.get(),batch)!=0) throw std::runtime_error(cancelled ? "LUMA_CANCELLED" : "LUMA_DECODE");
            e->CallVoidMethod(progress,progress_method,std::min(i+128,n),0,(jlong)0,(jlong)elapsed());
        }
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()),llama_sampler_free);
        if(temp<=0) llama_sampler_chain_add(sampler.get(),llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_k(40));
            llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_p(top_p>0 ? top_p : .95f,1));
            llama_sampler_chain_add(sampler.get(),llama_sampler_init_temp(temp));
            llama_sampler_chain_add(sampler.get(),llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        std::string answer;int generated=0;long first=0;bool finished=false;
        const int budget=std::min((int)max_output,n_ctx-n-1);
        while(generated<budget) {
            if(cancelled) throw std::runtime_error("LUMA_CANCELLED");
            auto token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
            if(llama_vocab_is_eog(vocab,token)) {finished=true;break;}
            std::vector<char> piece(256);int size=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);
            if(size<0) {piece.resize(-size);size=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);}
            if(size>0) answer.append(piece.data(),size);
            ++generated;if(first==0) first=elapsed();
            if(generated==1 || generated%4==0) e->CallVoidMethod(progress,progress_method,n,generated,(jlong)first,(jlong)elapsed());
            auto batch=llama_batch_get_one(&token,1);
            if(llama_decode(ctx.get(),batch)!=0) throw std::runtime_error(cancelled ? "LUMA_CANCELLED" : "LUMA_DECODE");
        }
        auto array=e->NewByteArray(answer.size());e->SetByteArrayRegion(array,0,answer.size(),reinterpret_cast<const jbyte *>(answer.data()));
        auto type=e->FindClass("dev/luma/agent/core/NativeReply");
        auto ctor=e->GetMethodID(type,"<init>","([BIIJJZ)V");
        auto result=e->NewObject(type,ctor,array,n,generated,(jlong)first,(jlong)elapsed(),(jboolean)!finished);e->DeleteLocalRef(array);return result;
    } catch(const std::bad_alloc &) {fail(e,"LUMA_MEMORY");return nullptr;}
      catch(const std::exception & ex) {fail(e,ex.what());return nullptr;}
}
