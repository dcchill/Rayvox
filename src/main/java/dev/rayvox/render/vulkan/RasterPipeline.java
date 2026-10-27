package dev.rayvox.render.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK13.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

final class RasterPipeline implements AutoCloseable {
    final VulkanContext context;
    final RasterShader shader;
    long pipeline;
    RasterPipeline(VulkanContext context,long layout,RasterShader shader,VertexFormat format,RasterState state) {
        this.context=context;this.shader=shader;
        long vertex=0,fragment=0;
        try(var stack=MemoryStack.stackPush()) {
            vertex=module(shader.vertex,org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader);
            fragment=module(shader.fragment,org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader);
            var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            var attributes=VkVertexInputAttributeDescription.calloc(format.getElements().size(),stack);
            for(int i=0;i<format.getElements().size();i++) {
                var element=format.getElements().get(i);
                attributes.get(i).location(i).binding(0).format(vertexFormat(element)).offset(format.getOffset(element));
            }
            var bindings=VkVertexInputBindingDescription.calloc(1,stack).binding(0).stride(format.getVertexSize()).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            var input=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default().pVertexAttributeDescriptions(attributes).pVertexBindingDescriptions(bindings);
            var assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            var viewport=VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
            var raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1)
                    .cullMode(!state.cull()?VK_CULL_MODE_NONE:state.cullFace()==org.lwjgl.opengl.GL11.GL_FRONT?VK_CULL_MODE_FRONT_BIT:state.cullFace()==org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK?VK_CULL_MODE_FRONT_AND_BACK:VK_CULL_MODE_BACK_BIT).frontFace(state.front()==org.lwjgl.opengl.GL11.GL_CCW?VK_FRONT_FACE_CLOCKWISE:VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(state.offset()).depthBiasConstantFactor(state.offsetUnits()).depthBiasSlopeFactor(state.offsetFactor());
            var multisample=VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            var depth=VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(state.depth()).depthWriteEnable(state.depth()&&state.depthWrite()).depthCompareOp(state.depthFunction()-org.lwjgl.opengl.GL11.GL_NEVER);
            var attachment=VkPipelineColorBlendAttachmentState.calloc(1,stack).blendEnable(state.blend()).colorWriteMask(state.colorMask())
                    .srcColorBlendFactor(RasterState.factor(state.sourceRgb())).dstColorBlendFactor(RasterState.factor(state.destinationRgb())).colorBlendOp(RasterState.operation(state.rgbOp()))
                    .srcAlphaBlendFactor(RasterState.factor(state.sourceAlpha())).dstAlphaBlendFactor(RasterState.factor(state.destinationAlpha())).alphaBlendOp(RasterState.operation(state.alphaOp()));
            var blend=VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(attachment);
            var dynamic=VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR,VK_DYNAMIC_STATE_BLEND_CONSTANTS));
            var rendering=VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(VK_FORMAT_R8G8B8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);
            var info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(input).pInputAssemblyState(assembly)
                    .pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(blend).pDynamicState(dynamic).layout(layout);
            var out=stack.mallocLong(1);check(vkCreateGraphicsPipelines(context.device,0,info,null,out),"create vanilla raster pipeline");pipeline=out.get(0);
        } finally {if(vertex!=0)vkDestroyShaderModule(context.device,vertex,null);if(fragment!=0)vkDestroyShaderModule(context.device,fragment,null);}
    }
    private long module(String source,int kind) {
        java.nio.ByteBuffer bytes;
        try{bytes=ComputeProgram.compile("vanilla",source,kind);}catch(IllegalStateException failure){throw new IllegalArgumentException("Shader cannot be translated: "+failure.getMessage(),failure);}
        try(var stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);check(vkCreateShaderModule(context.device,VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(bytes),null,out),"create vanilla shader");return out.get(0);
        }finally{org.lwjgl.system.MemoryUtil.memFree(bytes);}
    }
    static int vertexFormat(VertexFormatElement e) {
        if(e.equals(VertexFormatElement.POSITION))return VK_FORMAT_R32G32B32_SFLOAT;
        if(e.equals(VertexFormatElement.COLOR))return VK_FORMAT_R8G8B8A8_UNORM;
        if(e.equals(VertexFormatElement.UV0))return VK_FORMAT_R32G32_SFLOAT;
        if(e.equals(VertexFormatElement.UV1)||e.equals(VertexFormatElement.UV2))return VK_FORMAT_R16G16_SINT;
        if(e.equals(VertexFormatElement.NORMAL))return VK_FORMAT_R8G8B8A8_SNORM;
        throw new IllegalArgumentException("Unsupported vanilla attribute "+e);
    }
    public void close(){if(pipeline!=0){vkDestroyPipeline(context.device,pipeline,null);pipeline=0;}}
}
