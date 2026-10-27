#include <jni.h>
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_helpers.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <cstdio>

namespace {
constexpr const char* projectId="92c5dcd9-7c6b-42f4-ae01-e412ed8025d7";
void check(NVSDK_NGX_Result result,const char* action) {
    if(NVSDK_NGX_FAILED(result)) {
        char code[32];std::snprintf(code,sizeof(code)," (NGX 0x%08x)",static_cast<unsigned>(result));
        throw std::runtime_error(std::string(action)+code);
    }
}
void error(JNIEnv* env,const std::exception& failure){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),failure.what());}
std::wstring wide(JNIEnv* env,jstring value) {
    if(!value)throw std::runtime_error("Missing NGX path");
    const jchar* chars=env->GetStringChars(value,nullptr);
    if(!chars)throw std::runtime_error("Cannot read NGX path");
    std::wstring result(reinterpret_cast<const wchar_t*>(chars),env->GetStringLength(value));
    env->ReleaseStringChars(value,chars);return result;
}
struct Session {
    VkDevice device=nullptr;
    NVSDK_NGX_Parameter* parameters=nullptr;
    NVSDK_NGX_Handle* feature=nullptr;
    bool initialized=false;
    ~Session(){release();if(parameters)NVSDK_NGX_VULKAN_DestroyParameters(parameters);if(initialized)NVSDK_NGX_VULKAN_Shutdown1(device);}
    void release(){if(feature){NVSDK_NGX_VULKAN_ReleaseFeature(feature);feature=nullptr;}}
};
Session& session(jlong handle){if(!handle)throw std::runtime_error("Missing NGX session");return *reinterpret_cast<Session*>(handle);}
NVSDK_NGX_FeatureDiscoveryInfo discovery(const NVSDK_NGX_FeatureCommonInfo* common) {
    NVSDK_NGX_FeatureDiscoveryInfo info{};
    info.SDKVersion=NVSDK_NGX_Version_API;info.FeatureID=NVSDK_NGX_Feature_SuperSampling;
    info.Identifier.IdentifierType=NVSDK_NGX_Application_Identifier_Type_Project_Id;
    info.Identifier.v.ProjectDesc={projectId,NVSDK_NGX_ENGINE_TYPE_CUSTOM,"0.1.0"};
    info.FeatureInfo=common;return info;
}
NVSDK_NGX_Resource_VK image(jlong image,jlong view,VkFormat format,unsigned width,unsigned height,bool writable) {
    VkImageSubresourceRange range{};range.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;range.levelCount=range.layerCount=1;
    return NVSDK_NGX_Create_ImageView_Resource_VK(reinterpret_cast<VkImageView>(view),reinterpret_cast<VkImage>(image),range,format,width,height,writable);
}
}

extern "C" {
JNIEXPORT jint JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_abiVersion(JNIEnv*,jclass){return 1;}

JNIEXPORT jobjectArray JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_extensions(JNIEnv* env,jclass,jlong instance,jlong physical,jstring directory) {
    try {
        auto path=wide(env,directory);const wchar_t* paths[]={path.c_str()};
        NVSDK_NGX_FeatureCommonInfo common{};common.PathListInfo={paths,1};auto info=discovery(&common);info.ApplicationDataPath=L".";
        uint32_t count=0;VkExtensionProperties* properties=nullptr;
        if(instance)check(NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(reinterpret_cast<VkInstance>(instance),reinterpret_cast<VkPhysicalDevice>(physical),&info,&count,&properties),"Query DLSS device extensions");
        else check(NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info,&count,&properties),"Query DLSS instance extensions");
        auto result=env->NewObjectArray(count,env->FindClass("java/lang/String"),nullptr);
        if(!result)return nullptr;
        for(uint32_t i=0;i<count;i++){auto name=env->NewStringUTF(properties[i].extensionName);env->SetObjectArrayElement(result,i,name);env->DeleteLocalRef(name);}
        return result;
    }catch(const std::exception& failure){error(env,failure);return nullptr;}
}

JNIEXPORT jlong JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_initialize(JNIEnv* env,jclass,jlong instance,jlong physical,jlong device,jlong gipa,jlong gdpa,jstring directory,jstring logs) {
    try {
        auto state=std::make_unique<Session>();state->device=reinterpret_cast<VkDevice>(device);
        auto path=wide(env,directory),logPath=wide(env,logs);const wchar_t* paths[]={path.c_str()};
        NVSDK_NGX_FeatureCommonInfo common{};common.PathListInfo={paths,1};
        check(NVSDK_NGX_VULKAN_Init_with_ProjectID(projectId,NVSDK_NGX_ENGINE_TYPE_CUSTOM,"0.1.0",logPath.c_str(),reinterpret_cast<VkInstance>(instance),reinterpret_cast<VkPhysicalDevice>(physical),state->device,reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa),reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa),&common),"Initialize NGX");
        state->initialized=true;
        check(NVSDK_NGX_VULKAN_GetCapabilityParameters(&state->parameters),"Query NGX capabilities");
        int available=0;check(state->parameters->Get(NVSDK_NGX_Parameter_SuperSampling_Available,&available),"Query DLSS support");
        if(!available)throw std::runtime_error("DLSS Super Resolution is unsupported by this GPU/driver");
        return reinterpret_cast<jlong>(state.release());
    }catch(const std::exception& failure){error(env,failure);return 0;}
}

JNIEXPORT void JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_createFeature(JNIEnv* env,jclass,jlong handle,jlong command,jint width,jint height,jint outputWidth,jint outputHeight,jint quality) {
    try {
        auto& state=session(handle);
        if(state.feature||width<1||height<1||outputWidth<width||outputHeight<height||quality<0||quality>5)throw std::runtime_error("Invalid DLSS feature dimensions or lifecycle");
        NVSDK_NGX_DLSS_Create_Params create{};
        create.Feature={static_cast<unsigned>(width),static_cast<unsigned>(height),static_cast<unsigned>(outputWidth),static_cast<unsigned>(outputHeight),static_cast<NVSDK_NGX_PerfQuality_Value>(quality)};
        create.InFeatureCreateFlags=NVSDK_NGX_DLSS_Feature_Flags_IsHDR|NVSDK_NGX_DLSS_Feature_Flags_MVLowRes|NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
        check(NGX_VULKAN_CREATE_DLSS_EXT1(state.device,reinterpret_cast<VkCommandBuffer>(command),1,1,&state.feature,state.parameters,&create),"Create DLSS feature");
    }catch(const std::exception& failure){error(env,failure);}
}

JNIEXPORT void JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_evaluateFeature(JNIEnv* env,jclass,jlong handle,jlong command,jlongArray resources,jint width,jint height,jint outputWidth,jint outputHeight,jfloat jitterX,jfloat jitterY,jboolean reset,jfloat frameMillis) {
    try {
        auto& state=session(handle);
        if(!state.feature||!resources||env->GetArrayLength(resources)!=10)throw std::runtime_error("Invalid DLSS frame resources");
        jlong handles[10];env->GetLongArrayRegion(resources,0,10,handles);if(env->ExceptionCheck())return;
        for(auto value:handles)if(!value)throw std::runtime_error("Empty DLSS image/view");
        auto color=image(handles[0],handles[1],VK_FORMAT_R16G16B16A16_SFLOAT,width,height,false);
        auto depth=image(handles[2],handles[3],VK_FORMAT_R32_SFLOAT,width,height,false);
        auto motion=image(handles[4],handles[5],VK_FORMAT_R16G16_SFLOAT,width,height,false);
        auto output=image(handles[6],handles[7],VK_FORMAT_R16G16B16A16_SFLOAT,outputWidth,outputHeight,true);
        auto bias=image(handles[8],handles[9],VK_FORMAT_R32_SFLOAT,width,height,false);
        NVSDK_NGX_VK_DLSS_Eval_Params frame{};
        frame.Feature.pInColor=&color;frame.Feature.pInOutput=&output;frame.pInDepth=&depth;frame.pInMotionVectors=&motion;frame.pInBiasCurrentColorMask=&bias;
        frame.InJitterOffsetX=jitterX;frame.InJitterOffsetY=jitterY;frame.InRenderSubrectDimensions={static_cast<unsigned>(width),static_cast<unsigned>(height)};
        frame.InMVScaleX=frame.InMVScaleY=1;frame.InReset=reset?1:0;frame.InPreExposure=frame.InExposureScale=1;frame.InFrameTimeDeltaInMsec=frameMillis;
        check(NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(command),state.feature,state.parameters,&frame),"Evaluate DLSS");
    }catch(const std::exception& failure){error(env,failure);}
}

JNIEXPORT void JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_releaseFeature(JNIEnv* env,jclass,jlong handle) {
    try{session(handle).release();}catch(const std::exception& failure){error(env,failure);}
}
JNIEXPORT jlong JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_allocatedBytes(JNIEnv* env,jclass,jlong handle) {
    try {
        unsigned long long bytes=0;
        check(NGX_DLSS_GET_STATS(session(handle).parameters,&bytes),"Query DLSS memory usage");
        return static_cast<jlong>(bytes);
    }catch(const std::exception& failure){error(env,failure);return 0;}
}
JNIEXPORT void JNICALL Java_dev_rayvox_render_vulkan_DlssUpscaler_shutdown(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<Session*>(handle);}
}
