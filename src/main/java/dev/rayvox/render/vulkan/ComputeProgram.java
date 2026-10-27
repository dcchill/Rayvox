package dev.rayvox.render.vulkan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

final class ComputeProgram implements AutoCloseable {
    final VulkanContext context;
    long pipeline;
    ComputeProgram(VulkanContext context,long layout,String asset) {
        this.context=context;
        ByteBuffer code=compile(asset);
        try(var stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);
            check(vkCreateShaderModule(context.device,VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),null,out),"create shader module");
            long module=out.get(0);
            try {
                var info=VkComputePipelineCreateInfo.calloc(1,stack).sType$Default().layout(layout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(context.device,VK_NULL_HANDLE,info,null,out),"create "+asset+" pipeline");pipeline=out.get(0);
            } finally {vkDestroyShaderModule(context.device,module,null);}
        } finally {org.lwjgl.system.MemoryUtil.memFree(code);}
    }
    static ByteBuffer compile(String asset) {
        String path="/assets/rayvox/shaders/vulkan/"+asset;
        String source;
        try(var in=ComputeProgram.class.getResourceAsStream(path)) {
            if(in==null)throw new IOException("Missing shader "+path);
            source=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException e){throw new IllegalStateException(e);}
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_3);
            shaderc_compile_options_set_target_spirv(options,shaderc_spirv_version_1_5);
            shaderc_compile_options_set_optimization_level(options,shaderc_optimization_level_performance);
            long result=shaderc_compile_into_spv(compiler,source,shaderc_compute_shader,asset,"main",options);
            if(result==0)throw new IllegalStateException("Shader compiler returned no result");
            try {
                if(shaderc_result_get_compilation_status(result)!=shaderc_compilation_status_success)
                    throw new IllegalStateException(asset+": "+shaderc_result_get_error_message(result));
                var bytes=shaderc_result_get_bytes(result);
                var owned=org.lwjgl.system.MemoryUtil.memAlloc(bytes.remaining());owned.put(bytes).flip();return owned;
            }finally{shaderc_result_release(result);}
        }finally{shaderc_compile_options_release(options);shaderc_compiler_release(compiler);}
    }
    @Override public void close(){if(pipeline!=0){vkDestroyPipeline(context.device,pipeline,null);pipeline=0;}}
}
