package dev.rayvox.render.vulkan;

import dev.rayvox.Rayvox;
import dev.rayvox.render.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

/** Raster shadow map and one screen-space lighting pass; no acceleration structures or ray queries. */
final class ShaderLighting implements AutoCloseable {
    private static final int SHADOW_SIZE=2048;
    private final VulkanContext context;
    private final RetirementQueue retired;
    private final Map<Scene.Key,Mesh> meshes=new LinkedHashMap<>();
    private final List<DynamicMesh> dynamic=new ArrayList<>();
    private record Mesh(Scene.Mesh source,VulkanContext.Buffer vertices){}
    private record DynamicMesh(VulkanRasterBackend.Slice vertices,int texture){}
    private long shadowDescriptors,shadowLayout,shadowPipeline,computeDescriptors,computeLayout,sampler;
    private ComputeProgram lighting;
    private VulkanImage shadow,color;
    VulkanImage surfaces;
    private boolean clearSurfaces;
    private Scene.Camera camera;

    ShaderLighting(VulkanContext context,RetirementQueue retired) {
        this.context=context;this.retired=retired;
        try(var stack=MemoryStack.stackPush()) {
            if(!context.independentBlend)throw new IllegalStateException("Shader lighting requires independent attachment blending");
            var out=stack.mallocLong(1);
            var binding=VkDescriptorSetLayoutBinding.calloc(1,stack).binding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding),null,out),"create shadow descriptors");shadowDescriptors=out.get(0);
            var push=VkPushConstantRange.calloc(1,stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).size(80);
            check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(shadowDescriptors)).pPushConstantRanges(push),null,out),"create shadow layout");shadowLayout=out.get(0);
            var bindings=VkDescriptorSetLayoutBinding.calloc(6,stack);
            for(int i=0;i<6;i++)bindings.get(i).binding(i).descriptorCount(1).descriptorType(i==5?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:i==4?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,out),"create shader lighting descriptors");computeDescriptors=out.get(0);
            check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(computeDescriptors)),null,out),"create shader lighting layout");computeLayout=out.get(0);
            check(vkCreateSampler(context.device,VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0),null,out),"create lighting sampler");sampler=out.get(0);
            shadow=new VulkanImage(context,SHADOW_SIZE,SHADOW_SIZE,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,false);
            lighting=new ComputeProgram(context,computeLayout,"shader_lighting.comp");
            shadowPipeline=createShadowPipeline();
        } catch(RuntimeException|Error failure){close();throw failure;}
    }
    void resize(int width,int height) {
        if(color!=null&&color.width==width&&color.height==height)return;
        if(color!=null)color.close();if(surfaces!=null)surfaces.close();color=surfaces=null;
        color=new VulkanImage(context,width,height,VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,false);
        surfaces=new VulkanImage(context,width,height,VK_FORMAT_R32G32B32A32_SFLOAT,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,false);
    }
    void beginFrame(){clearSurfaces=true;camera=null;dynamic.clear();}
    void camera(Scene.Camera camera){this.camera=camera;}
    void capture(RasterMesh mesh,net.minecraft.client.renderer.ShaderInstance shader,int texture,VulkanRasterBackend.Frame frame) {
        if(camera==null||!Rayvox.ENTITY_SHADOWS.get()||!shader.getName().startsWith("rendertype_entity")||shader.getName().contains("translucent"))return;
        var model=shader.getUniform("ModelViewMat");var projection=shader.getUniform("ProjMat");
        if(model==null||projection==null)return;
        var transform=new Matrix4f(camera.inverseViewProjection()).mul(new Matrix4f().set(projection.getFloatBuffer())).mul(new Matrix4f().set(model.getFloatBuffer()));
        var decoded=MeshDecoder.decode(mesh,1,transform);
        if(decoded.hasRemaining())dynamic.add(new DynamicMesh(frame.copy(decoded,4),texture));
    }
    void update(Scene.Update update,long serial) {
        for(var key:update.removed()){var old=meshes.remove(key);if(old!=null)retired.retire(serial,old.vertices()::close);}
        for(var source:update.meshes()) {
            var old=meshes.get(source.key());if(old!=null&&old.source().generation()>=source.generation())continue;
            if(source.triangles()==0){meshes.remove(source.key());if(old!=null)retired.retire(serial,old.vertices()::close);continue;}
            var buffer=context.buffer(source.vertices().remaining(),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,true);
            try{buffer.mapped.put(source.vertices().duplicate());buffer.flush();}catch(RuntimeException|Error failure){buffer.close();throw failure;}
            meshes.put(source.key(),new Mesh(source,buffer));if(old!=null)retired.retire(serial,old.vertices()::close);
        }
    }
    void prepareSurfaces(VkCommandBuffer command) {
        if(!clearSurfaces)return;
        surfaces.transition(command,surfaces.initialized?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,surfaces.initialized?VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT:0,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
        try(var stack=MemoryStack.stackPush()) {
            var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdClearColorImage(command,surfaces.image,VK_IMAGE_LAYOUT_GENERAL,VkClearColorValue.calloc(stack),range);
        }
        VulkanContext.memoryBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        surfaces.initialized=true;clearSurfaces=false;
    }
    boolean ready(){return camera!=null;}
    void record(VkCommandBuffer command,VulkanRasterBackend.Frame frame,RasterTextures textures,SharedSurface surface,int width,int height,int uniformAlignment) {
        prepareSurfaces(command);
        float range=Rayvox.SUN_SHADOW_DISTANCE.get();
        var sun=camera.sunDirection();
        var lightVP=new Matrix4f().ortho(-range,range,-range,range,0.1f,range*4,true)
                .lookAt(new Vector3f(sun).mul(range*2),new Vector3f(),Math.abs(sun.y)>.99f?new Vector3f(0,0,1):new Vector3f(0,1,0));
        shadow.transition(command,shadow.initialized?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,shadow.initialized?VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT:0,VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
        try(var stack=MemoryStack.stackPush()) {
            var depth=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(shadow.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);depth.clearValue().depthStencil().depth(1);
            var rendering=VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pDepthAttachment(depth);rendering.renderArea().extent().set(SHADOW_SIZE,SHADOW_SIZE);
            vkCmdBeginRendering(command,rendering);
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,shadowPipeline);
            if(Rayvox.SHADOWS.get()&&Rayvox.SUNLIGHT.get()&&camera.sunRadiance().lengthSquared()>0) {
                var sets=new HashMap<Integer,Long>();
                for(var mesh:meshes.values())try(var drawStack=MemoryStack.stackPush()) {
                    var source=mesh.source();var atlas=textures.images.get(source.texture());if(atlas==null)continue;
                    long set=sets.computeIfAbsent(source.texture(),id->textureSet(frame,atlas));
                    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,shadowLayout,0,drawStack.longs(set),null);
                    var push=drawStack.malloc(80);lightVP.get(0,push);push.putFloat(64,(float)(source.x()-camera.x())).putFloat(68,(float)(source.y()-camera.y())).putFloat(72,(float)(source.z()-camera.z())).putFloat(76,0);
                    vkCmdPushConstants(command,shadowLayout,VK_SHADER_STAGE_VERTEX_BIT,0,push);
                    vkCmdBindVertexBuffers(command,0,drawStack.longs(mesh.vertices().handle),drawStack.longs(0));
                    vkCmdDraw(command,source.triangles()*3,1,0,0);
                }
                for(var mesh:dynamic)try(var drawStack=MemoryStack.stackPush()) {
                    var atlas=textures.images.get(mesh.texture());if(atlas==null)continue;
                    long set=sets.computeIfAbsent(mesh.texture(),id->textureSet(frame,atlas));
                    vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,shadowLayout,0,drawStack.longs(set),null);
                    var push=drawStack.calloc(80);lightVP.get(0,push);vkCmdPushConstants(command,shadowLayout,VK_SHADER_STAGE_VERTEX_BIT,0,push);
                    vkCmdBindVertexBuffers(command,0,drawStack.longs(mesh.vertices().buffer().handle),drawStack.longs(mesh.vertices().offset()));
                    vkCmdDraw(command,mesh.vertices().size()/Scene.VERTEX_BYTES,1,0,0);
                }
            }
            vkCmdEndRendering(command);shadow.initialized=true;
            VulkanContext.memoryBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT);
            color.transition(command,color.initialized?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,color.initialized?VK_ACCESS_SHADER_READ_BIT:0,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            var copy=VkImageCopy.calloc(1,stack);copy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);copy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);copy.extent().set(width,height,1);
            vkCmdCopyImage(command,surface.color.image,VK_IMAGE_LAYOUT_GENERAL,color.image,VK_IMAGE_LAYOUT_GENERAL,copy);color.initialized=true;
            var uniforms=frame.allocate(256,uniformAlignment);var data=uniforms.buffer().mapped;int offset=uniforms.offset();
            camera.inverseViewProjection().get(offset,data);camera.viewProjection().get(offset+64,data);lightVP.get(offset+128,data);
            data.putFloat(offset+192,sun.x).putFloat(offset+196,sun.y).putFloat(offset+200,sun.z).putFloat(offset+204,range);
            camera.sunRadiance().get(offset+208,data);data.putFloat(offset+220,0);camera.sky().get(offset+224,data);data.putFloat(offset+236,0);
            data.putFloat(offset+240,width).putFloat(offset+244,height).putFloat(offset+248,Rayvox.SHADOWS.get()&&Rayvox.SUNLIGHT.get()?1:0).putFloat(offset+252,Rayvox.SHADER_REFLECTIONS.get()?1:0);
            uniforms.buffer().flush();
            long set=allocate(frame,computeDescriptors);
            var writes=VkWriteDescriptorSet.calloc(6,stack);
            var inputs=new VulkanImage[]{color,surface.depth,surfaces,shadow,surface.color};
            for(int i=0;i<5;i++)writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(i==4?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(i==4?0:sampler).imageView(inputs[i].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            writes.get(5).sType$Default().dstSet(set).dstBinding(5).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.buffer().handle).offset(offset).range(256));
            vkUpdateDescriptorSets(context.device,writes,null);
            VulkanContext.memoryBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_HOST_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_UNIFORM_READ_BIT);
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,lighting.pipeline);
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,computeLayout,0,stack.longs(set),null);
            vkCmdDispatch(command,(width+7)/8,(height+7)/8,1);
            VulkanContext.memoryBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
        }
    }
    private long allocate(VulkanRasterBackend.Frame frame,long layout) {
        try(var stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);check(vkAllocateDescriptorSets(context.device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(frame.pool).pSetLayouts(stack.longs(layout)),out),"allocate lighting descriptors");return out.get(0);
        }
    }
    private long textureSet(VulkanRasterBackend.Frame frame,VulkanImage atlas) {
        long set=allocate(frame,shadowDescriptors);
        try(var stack=MemoryStack.stackPush()) {
            var write=VkWriteDescriptorSet.calloc(1,stack).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(sampler).imageView(atlas.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));vkUpdateDescriptorSets(context.device,write,null);
        }return set;
    }
    static String source(String asset) {
        try(var stream=ShaderLighting.class.getResourceAsStream("/assets/rayvox/shaders/vulkan/"+asset)) {
            if(stream==null)throw new IllegalStateException("Missing shader "+asset);return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException failure){throw new IllegalStateException("Read "+asset,failure);}
    }
    private long module(String asset,int kind) {
        var code=ComputeProgram.compile(asset,source(asset),kind);
        try(var stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);check(vkCreateShaderModule(context.device,VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),null,out),"create shadow shader");return out.get(0);
        }finally{org.lwjgl.system.MemoryUtil.memFree(code);}
    }
    private long createShadowPipeline() {
        long vertex=0,fragment=0;
        try(var stack=MemoryStack.stackPush()) {
            vertex=module("shader_shadow.vert",shaderc_vertex_shader);fragment=module("shader_shadow.frag",shaderc_fragment_shader);
            var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            var attributes=VkVertexInputAttributeDescription.calloc(3,stack);attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(32);attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(48);
            var input=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default().pVertexAttributeDescriptions(attributes).pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1,stack).binding(0).stride(Scene.VERTEX_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX));
            var assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            var viewport=VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1).pViewports(VkViewport.calloc(1,stack).width(SHADOW_SIZE).height(SHADOW_SIZE).minDepth(0).maxDepth(1));
            var scissor=VkRect2D.calloc(1,stack);scissor.extent().set(SHADOW_SIZE,SHADOW_SIZE);viewport.pScissors(scissor);
            var raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1).depthBiasEnable(true).depthBiasConstantFactor(1.25f).depthBiasSlopeFactor(1.75f);
            var multisample=VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            var depth=VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
            var rendering=VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);
            var info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(input).pInputAssemblyState(assembly).pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()).layout(shadowLayout);
            var out=stack.mallocLong(1);check(vkCreateGraphicsPipelines(context.device,0,info,null,out),"create raster shadow pipeline");return out.get(0);
        }finally{if(vertex!=0)vkDestroyShaderModule(context.device,vertex,null);if(fragment!=0)vkDestroyShaderModule(context.device,fragment,null);}
    }
    void clear(){for(var mesh:meshes.values())mesh.vertices().close();meshes.clear();dynamic.clear();camera=null;}
    public void close() {
        clear();if(lighting!=null)lighting.close();if(shadow!=null)shadow.close();if(color!=null)color.close();if(surfaces!=null)surfaces.close();
        if(shadowPipeline!=0)vkDestroyPipeline(context.device,shadowPipeline,null);if(shadowLayout!=0)vkDestroyPipelineLayout(context.device,shadowLayout,null);if(computeLayout!=0)vkDestroyPipelineLayout(context.device,computeLayout,null);if(shadowDescriptors!=0)vkDestroyDescriptorSetLayout(context.device,shadowDescriptors,null);if(computeDescriptors!=0)vkDestroyDescriptorSetLayout(context.device,computeDescriptors,null);if(sampler!=0)vkDestroySampler(context.device,sampler,null);
    }
}
