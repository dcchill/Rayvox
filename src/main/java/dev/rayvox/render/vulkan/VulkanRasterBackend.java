package dev.rayvox.render.vulkan;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.*;
import dev.rayvox.mixin.RenderTargetAccess;
import dev.rayvox.render.*;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.vulkan.VK13.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

/** Native vanilla draws, batched between GL boundaries against shared framebuffer attachments. */
public final class VulkanRasterBackend implements RenderBackend {
    private static final int MAX_DRAWS=8192,MAX_BATCHES=1024;
    private final long memoryBudget;
    private final boolean shaderLighting;
    private ShaderLighting lighting;
    private long lightingPasses;
    private VulkanContext context;
    private SharedSurface surface;
    private int framebuffer;
    private RenderTarget target;
    private int originalFramebuffer,originalColor,originalDepth;
    private int originalWidth,originalHeight,originalViewWidth,originalViewHeight,outputWidth,outputHeight;
    private int internalWidth,internalHeight;
    private GlWorldResolve worldResolve;
    private long descriptorLayout,pipelineLayout,serial;
    private int uniformAlignment=256,slot;
    private final Frame[] frames=new Frame[2];
    private Frame frame;
    private final RetirementQueue retired=new RetirementQueue();
    private RasterTextures textures;
    private GlDepthCopy depthCopy;
    private final Map<PipelineKey,RasterPipeline> pipelines=new HashMap<>();
    private final Map<Integer,RasterShader> shaders=new HashMap<>();
    private final Map<Integer,int[]> samplerUnits=new HashMap<>();
    private final Set<Integer> unsupported=new HashSet<>();
    private final Map<Integer,RasterSampler> samplerStates=new HashMap<>();
    private final Map<VertexBuffer,Geometry> geometry=new IdentityHashMap<>();
    private final List<Draw> draws=new ArrayList<>();
    private final List<Geometry> uploads=new ArrayList<>();
    private double gpuMillis=Double.NaN,waitMillis;
    private int lastDraws,lastBatches;
    private int lastEntityDraws,lastParticleDraws;
    private String reason="";
    public boolean internal;
    private record PipelineKey(int program,VertexFormat format,RasterState state){}
    record Slice(VulkanContext.Buffer buffer,int offset,int size){}
    private record Geometry(long generation,Slice vertices,Slice indices,int count,int indexType,RasterMesh source){}
    private record Draw(RasterPipeline pipeline,Geometry geometry,Slice uniforms,int[] textures,RasterSampler[] samplers,int[] viewport,int[] scissor,float[] blend){}

    final class Frame implements AutoCloseable {
        final List<VkCommandBuffer> commands=new ArrayList<>();
        final List<VulkanContext.Buffer> buffers=new ArrayList<>();
        VulkanContext.Buffer upload;
        int offset,used,batches,drawCount,entityDraws,particleDraws;
        long fence,pool,query,submission;
        Frame() {
            try(var stack=MemoryStack.stackPush()) {
                var out=stack.mallocLong(1);
                check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,out),"create raster fence");fence=out.get(0);
                var sizes=VkDescriptorPoolSize.calloc(shaderLighting?3:2,stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_DRAWS+1);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_DRAWS*3+1024);
                if(shaderLighting)sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                check(vkCreateDescriptorPool(context.device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(MAX_DRAWS+1024).pPoolSizes(sizes),null,out),"create raster descriptors");pool=out.get(0);
                upload=context.buffer(32L*1048576,VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT|VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,true);
                if(context.timestampBits!=0){check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(MAX_BATCHES*2),null,out),"create raster timestamps");query=out.get(0);}
            }catch(RuntimeException|Error failure){close();throw failure;}
        }
        void complete() {
            if(submission!=0) {
                try(var stack=MemoryStack.stackPush()) {
                    check(vkWaitForFences(context.device,fence,true,10_000_000_000L),"wait for raster frame");
                    if(query!=0&&batches>0) {
                        var stamps=stack.mallocLong(batches*2);
                        if(vkGetQueryPoolResults(context.device,query,0,batches*2,stamps,8,VK_QUERY_RESULT_64_BIT)==VK_SUCCESS) {
                            long mask=context.timestampBits==64?-1L:(1L<<context.timestampBits)-1;long total=0;
                            for(int i=0;i<batches;i++)total+=(stamps.get(i*2+1)-stamps.get(i*2))&mask;
                            gpuMillis=total*context.timestampPeriod/1_000_000.0;
                        }
                    }
                }
                retired.complete(submission);submission=0;
            }
            for(var buffer:buffers)buffer.close();buffers.clear();offset=used=batches=drawCount=entityDraws=particleDraws=0;
            if(pool!=0)check(vkResetDescriptorPool(context.device,pool,0),"reset raster descriptors");
        }
        Slice allocate(int bytes,int alignment) {
            int start=(offset+alignment-1)&-alignment;
            if((long)start+bytes<=upload.mapped.capacity()){offset=start+bytes;return new Slice(upload,start,bytes);}
            var extra=context.buffer(Math.max(16,bytes),VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT|VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,true);
            buffers.add(extra);return new Slice(extra,0,bytes);
        }
        Slice copy(ByteBuffer bytes,int alignment) {
            var slice=allocate(bytes.remaining(),alignment);var mapped=slice.buffer().mapped.duplicate();mapped.position(slice.offset());mapped.put(bytes.duplicate());return slice;
        }
        VkCommandBuffer command() {
            if(used==commands.size())commands.add(context.commandBuffer());return commands.get(used++);
        }
        public void close() {
            complete();if(upload!=null){upload.close();upload=null;}
            if(pool!=0){vkDestroyDescriptorPool(context.device,pool,null);pool=0;}
            if(query!=0){vkDestroyQueryPool(context.device,query,null);query=0;}
            if(fence!=0){vkDestroyFence(context.device,fence,null);fence=0;}
        }
    }
    public VulkanRasterBackend(long memoryBudget){this(memoryBudget,false);}
    public VulkanRasterBackend(long memoryBudget,boolean shaderLighting){this.memoryBudget=memoryBudget;this.shaderLighting=shaderLighting;}
    public boolean shaderLighting(){return shaderLighting;}
    public long lightingPasses(){return lightingPasses;}
    public void initialize() {
        try {
            context=new VulkanContext(SharedSurface.deviceUuid(),Boolean.getBoolean("rayvox.vulkan.validation"),false);context.budget=memoryBudget;
            if(shaderLighting)lighting=new ShaderLighting(context,retired);
            try(var stack=MemoryStack.stackPush()) {
                var props=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(context.physical,props);uniformAlignment=(int)props.limits().minUniformBufferOffsetAlignment();
                var bindings=VkDescriptorSetLayoutBinding.calloc(4,stack);
                for(int i=0;i<4;i++)bindings.get(i).binding(i).descriptorCount(1).descriptorType(i==0?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
                var out=stack.mallocLong(1);check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,out),"create vanilla descriptor layout");descriptorLayout=out.get(0);
                check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)),null,out),"create vanilla pipeline layout");pipelineLayout=out.get(0);
            }
            textures=new RasterTextures(context,retired);
            depthCopy=new GlDepthCopy();
            worldResolve=new GlWorldResolve();
            for(int i=0;i<frames.length;i++)frames[i]=new Frame();
            var main=Minecraft.getInstance().getMainRenderTarget();
            if(main.isStencilEnabled())throw new IllegalStateException("Vulkan vanilla requires a main target without stencil");
            resize(main.width,main.height);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    public void beginFrame() {
        if(frame!=null)endFrame();
        var main=Minecraft.getInstance().getMainRenderTarget();
        if(main.isStencilEnabled())throw new IllegalStateException("Vulkan vanilla requires a main target without stencil");
        // Resolve/detach restores native dimensions before any next-frame sizing decisions.
        detach();resize(main.width,main.height);
        long start=System.nanoTime();frame=frames[slot];frame.complete();waitMillis=(System.nanoTime()-start)/1e6;serial++;
        if(lighting!=null)lighting.beginFrame();
        attach(main);
    }
    public void endFrame() {
        if(frame==null)return;
        flush();
        try(var stack=MemoryStack.stackPush()) {
            check(vkResetFences(context.device,frame.fence),"reset raster frame fence");
            check(vkQueueSubmit(context.queue,VkSubmitInfo.calloc(stack).sType$Default(),frame.fence),"finish raster frame");frame.submission=serial;
        }
        lastDraws=frame.drawCount;lastBatches=frame.batches;lastEntityDraws=frame.entityDraws;lastParticleDraws=frame.particleDraws;frame=null;slot=(slot+1)%frames.length;
    }
    /** Native auxiliary targets (notably outlines) change the viewport before bindWrite(false). */
    public void boundTarget(RenderTarget bound) {
        if(!internal&&bound==target)glViewport(0,0,internalWidth,internalHeight);
    }
    public static boolean supports(String name) {
        return Set.of("rendertype_solid","rendertype_cutout","rendertype_cutout_mipped","rendertype_translucent","rendertype_tripwire","rendertype_eyes","particle",
                "rendertype_entity_solid","rendertype_entity_cutout","rendertype_entity_cutout_no_cull","rendertype_entity_cutout_no_cull_z_offset","rendertype_entity_smooth_cutout",
                "rendertype_entity_translucent","rendertype_entity_translucent_cull","rendertype_entity_translucent_emissive","rendertype_entity_decal","rendertype_entity_no_outline").contains(name);
    }
    public boolean draw(VertexBuffer owner,RasterMesh mesh,ShaderInstance shader,boolean persistent) {
        if(frame==null||internal||target==null||mesh==null||!mesh.supported()||shader==null||!supports(shader.getName())||unsupported.contains(shader.getId())
                ||frame.drawCount>=MAX_DRAWS||frame.batches>=MAX_BATCHES||glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=framebuffer||glIsEnabled(GL_STENCIL_TEST)||glIsEnabled(GL_COLOR_LOGIC_OP))return false;
        try {
            var state=RasterState.capture();if(!state.fill())return false;
            var key=new PipelineKey(shader.getId(),mesh.state().format(),state);
            var pipeline=pipelines.get(key);
            if(pipeline==null) {
                var port=shaders.computeIfAbsent(shader.getId(),id->new RasterShader(shaderSource(id,GL_VERTEX_SHADER),shaderSource(id,GL_FRAGMENT_SHADER),mesh.state().format().getElementAttributeNames(),shaderLighting,shader.getName().equals("rendertype_translucent")?.8f:shader.getName().equals("rendertype_solid")?.12f:0));
                pipeline=new RasterPipeline(context,pipelineLayout,port,mesh.state().format(),state);pipelines.put(key,pipeline);
            }
            int[] ids=new int[3];var samplers=new RasterSampler[3];int active=glGetInteger(GL_ACTIVE_TEXTURE);
            int[] units=samplerUnits.computeIfAbsent(shader.getId(),program->{int[] result=new int[3];for(int i=0;i<3;i++){int location=glGetUniformLocation(program,"Sampler"+i);result[i]=location<0?-1:glGetUniformi(program,location);}return result;});
            try {
                for(int i=0;i<3;i++) {
                    if(units[i]<0)continue;
                    glActiveTexture(GL_TEXTURE0+units[i]);int id=glGetInteger(GL_TEXTURE_BINDING_2D);ids[i]=id;
                    if(id!=0) {
                        samplers[i]=samplerStates.computeIfAbsent(id,ignored->RasterSampler.capture());
                        if(samplers[i].mipmapped())TextureSnapshots.requestMipmaps(id);else TextureSnapshots.request(id);
                    }
                }
            } finally {glActiveTexture(active);}
            // Unused samplers are valid descriptors too; bind the base texture to them.
            if(ids[0]==0)return false;
            for(int i=1;i<3;i++)if(ids[i]==0){ids[i]=ids[0];samplers[i]=samplers[0];}
            Geometry gpu=persistent?geometry.get(owner):null;
            if(gpu==null||gpu.generation()!=mesh.generation()) {
                var indices=mesh.triangleIndices();int type=mesh.indices()==null?VK_INDEX_TYPE_UINT32:mesh.state().indexType()==VertexFormat.IndexType.SHORT?VK_INDEX_TYPE_UINT16:VK_INDEX_TYPE_UINT32;
                if(persistent) {
                    if(gpu!=null)retire(gpu);
                    var vertices=context.buffer(mesh.vertices().remaining(),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,false);
                    VulkanContext.Buffer index;
                    try{index=context.buffer(indices.remaining(),VK_BUFFER_USAGE_INDEX_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,false);}catch(RuntimeException|Error failure){vertices.close();throw failure;}
                    gpu=new Geometry(mesh.generation(),new Slice(vertices,0,mesh.vertices().remaining()),new Slice(index,0,indices.remaining()),mesh.state().indexCount(),type,mesh);
                    geometry.put(owner,gpu);uploads.add(gpu);
                } else gpu=new Geometry(mesh.generation(),frame.copy(mesh.vertices(),4),frame.copy(indices,4),mesh.state().indexCount(),type,null);
            }
            var uniforms=frame.allocate(pipeline.shader.bytes,uniformAlignment);pipeline.shader.write(shader,uniforms.buffer().mapped,uniforms.offset());
            int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);int[] scissor=viewport.clone();
            if(glIsEnabled(GL_SCISSOR_TEST))glGetIntegerv(GL_SCISSOR_BOX,scissor);
            float[] blend=new float[4];glGetFloatv(org.lwjgl.opengl.EXTBlendColor.GL_BLEND_COLOR_EXT,blend);
            draws.add(new Draw(pipeline,gpu,uniforms,ids,samplers,viewport,scissor,blend));frame.drawCount++;
            if(lighting!=null)lighting.capture(mesh,shader,ids[0],frame);
            if(shader.getName().startsWith("rendertype_entity")||shader.getName().equals("rendertype_eyes"))frame.entityDraws++;
            if(shader.getName().equals("particle"))frame.particleDraws++;
            return true;
        }catch(IllegalArgumentException failure) {
            unsupported.add(shader.getId());reason="Native shader fallback: "+shader.getName()+" ("+failure.getMessage()+")";
            com.mojang.logging.LogUtils.getLogger().warn(reason);return false;
        }
    }
    private static String shaderSource(int program,int kind) {
        try(var stack=MemoryStack.stackPush()) {
            var attached=stack.mallocInt(glGetProgrami(program,GL_ATTACHED_SHADERS));var count=stack.mallocInt(1);glGetAttachedShaders(program,count,attached);
            for(int i=0;i<count.get(0);i++)if(glGetShaderi(attached.get(i),GL_SHADER_TYPE)==kind)return glGetShaderSource(attached.get(i));
            throw new IllegalArgumentException("Missing attached vanilla shader");
        }
    }
    public void samplerChanged(){samplerStates.clear();}
    public void discardPending(){draws.clear();uploads.clear();}
    public void remove(VertexBuffer owner){var old=geometry.remove(owner);if(old!=null)retire(old);}
    private void retire(Geometry old){retired.retire(serial,()->{old.vertices().buffer().close();old.indices().buffer().close();});}
    public void flush() {
        if(internal||draws.isEmpty())return;
        internal=true;
        try {
            var command=frame.command();context.begin(command);int query=frame.batches*2;
            if(frame.query!=0){vkCmdResetQueryPool(command,frame.query,query,2);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,frame.query,query);}
            textures.prepare();textures.upload(command,TextureSnapshots.drain(),frame,serial);
            for(var upload:uploads) {
                copy(command,frame.copy(upload.source().vertices(),4),upload.vertices());
                copy(command,frame.copy(upload.source().triangleIndices(),4),upload.indices());
            }
            uploads.clear();frame.upload.flush();for(var buffer:frame.buffers)buffer.flush();
            VulkanContext.memoryBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_HOST_WRITE_BIT,VK_PIPELINE_STAGE_VERTEX_INPUT_BIT|VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT|VK_ACCESS_INDEX_READ_BIT|VK_ACCESS_UNIFORM_READ_BIT|VK_ACCESS_SHADER_READ_BIT);
            surface.signalComplete();surface.acquire(command);
            if(lighting!=null)lighting.prepareSurfaces(command);
            try(var stack=MemoryStack.stackPush()) {
                var color=VkRenderingAttachmentInfo.calloc(shaderLighting?2:1,stack);
                color.get(0).sType$Default().imageView(surface.color.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                if(lighting!=null)color.get(1).sType$Default().imageView(lighting.surfaces.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                var depth=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(surface.depth.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                var rendering=VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth);rendering.renderArea().extent().set(surface.color.width,surface.color.height);
                vkCmdBeginRendering(command,rendering);
            }
            for(var draw:draws)record(command,draw);
            vkCmdEndRendering(command);surface.release(command);
            if(frame.query!=0)vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,frame.query,query+1);
            check(vkEndCommandBuffer(command),"end vanilla draw batch");
            try(var stack=MemoryStack.stackPush()) {
                var submit=VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command.address())).pWaitSemaphores(stack.longs(surface.complete)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)).pSignalSemaphores(stack.longs(surface.ready));
                check(vkQueueSubmit(context.queue,submit,0),"submit vanilla draws");
            }
            surface.waitForVulkan();draws.clear();frame.batches++;
        }finally{internal=false;}
    }
    private void copy(VkCommandBuffer command,Slice source,Slice destination){try(var stack=MemoryStack.stackPush()){vkCmdCopyBuffer(command,source.buffer().handle,destination.buffer().handle,VkBufferCopy.calloc(1,stack).srcOffset(source.offset()).dstOffset(destination.offset()).size(source.size()));}}
    private void record(VkCommandBuffer command,Draw draw) {try(var stack=MemoryStack.stackPush()) {
        var out=stack.mallocLong(1);check(vkAllocateDescriptorSets(context.device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(frame.pool).pSetLayouts(stack.longs(descriptorLayout)),out),"allocate vanilla draw descriptors");long set=out.get(0);
        var writes=VkWriteDescriptorSet.calloc(4,stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(draw.uniforms().buffer().handle).offset(draw.uniforms().offset()).range(draw.uniforms().size()));
        for(int i=0;i<3;i++) {
            var image=textures.images.get(draw.textures()[i]);if(image==null)throw new IllegalStateException("Missing mirrored vanilla texture "+draw.textures()[i]);
            writes.get(i+1).sType$Default().dstSet(set).dstBinding(i+1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(textures.sampler(draw.samplers()[i])).imageView(image.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        }
        vkUpdateDescriptorSets(context.device,writes,null);
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,draw.pipeline().pipeline);
        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLayout,0,stack.longs(set),null);
        var v=draw.viewport();vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).x(v[0]).y(v[1]).width(v[2]).height(v[3]).minDepth(0).maxDepth(1));
        var s=draw.scissor();var scissor=VkRect2D.calloc(1,stack);int x=Math.max(0,s[0]),y=Math.max(0,s[1]);scissor.offset().set(x,y);scissor.extent().set(Math.max(0,Math.min(surface.color.width,s[0]+s[2])-x),Math.max(0,Math.min(surface.color.height,s[1]+s[3])-y));vkCmdSetScissor(command,0,scissor);
        vkCmdSetBlendConstants(command,stack.floats(draw.blend()));
        vkCmdBindVertexBuffers(command,0,stack.longs(draw.geometry().vertices().buffer().handle),stack.longs(draw.geometry().vertices().offset()));
        vkCmdBindIndexBuffer(command,draw.geometry().indices().buffer().handle,draw.geometry().indices().offset(),draw.geometry().indexType());
        vkCmdDrawIndexed(command,draw.geometry().count(),1,0,0,0);
    }}
    public void resize(int width,int height) {
        var mode=dev.rayvox.Rayvox.UPSCALING.get();
        int nextWidth=mode.dimension(width),nextHeight=mode.dimension(height);
        if(width<1||height<1||surface!=null&&internalWidth==nextWidth&&internalHeight==nextHeight&&outputWidth==width&&outputHeight==height)return;
        detach();drain();internal=true;
        try {
            if(surface!=null){surface.close();surface=null;}if(framebuffer!=0){glDeleteFramebuffers(framebuffer);framebuffer=0;}
            outputWidth=width;outputHeight=height;
            internalWidth=nextWidth;internalHeight=nextHeight;
            // Keep external-image tile row pitches identical across the two drivers. Logical
            // viewports stay exact; padding is excluded from every color/depth sample.
            surface=new SharedSurface(context,(internalWidth+63)&~63,(internalHeight+63)&~63,true);surface.initializeRaster();framebuffer=glGenFramebuffers();
            if(lighting!=null)lighting.resize(surface.color.width,surface.color.height);
            int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,surface.colorTexture,0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,surface.depthTexture,0);
            int status=glCheckFramebufferStatus(GL_FRAMEBUFFER);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
            if(status!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Shared vanilla framebuffer incomplete: "+status);
        }finally{internal=false;}
    }
    private void attach(RenderTarget main) {
        if(target==main)return;
        target=main;originalFramebuffer=main.frameBufferId;originalColor=main.getColorTextureId();originalDepth=main.getDepthTextureId();
        originalWidth=main.width;originalHeight=main.height;originalViewWidth=main.viewWidth;originalViewHeight=main.viewHeight;
        internal=true;
        try {
            blit(originalFramebuffer,framebuffer,originalDepth,originalWidth,originalHeight,internalWidth,internalHeight,false);
            main.frameBufferId=framebuffer;((RenderTargetAccess)main).rayvox$color(surface.colorTexture);((RenderTargetAccess)main).rayvox$depth(surface.depthTexture);
            main.width=main.viewWidth=internalWidth;main.height=main.viewHeight=internalHeight;main.bindWrite(true);
        }finally{internal=false;}
    }
    public void beforeTargetChange(RenderTarget changed){if(changed==target)detach();}
    public void resolveWorld() {
        if(lighting!=null&&lighting.ready()&&frame!=null&&target!=null) {
            flush();internal=true;
            try {
                var command=frame.command();context.begin(command);
                int query=frame.query!=0&&frame.batches<MAX_BATCHES?frame.batches*2:-1;
                if(query>=0){vkCmdResetQueryPool(command,frame.query,query,2);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,frame.query,query);}
                surface.signalComplete();surface.acquire(command);
                VulkanContext.memoryBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
                lighting.record(command,frame,textures,surface,internalWidth,internalHeight,uniformAlignment);
                surface.release(command);
                if(query>=0){vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,frame.query,query+1);frame.batches++;}
                check(vkEndCommandBuffer(command),"end shader lighting");
                try(var stack=MemoryStack.stackPush()) {
                    var submit=VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command.address())).pWaitSemaphores(stack.longs(surface.complete)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)).pSignalSemaphores(stack.longs(surface.ready));
                    check(vkQueueSubmit(context.queue,submit,0),"submit shader lighting");
                    lightingPasses++;
                }
                surface.waitForVulkan();
            }finally{internal=false;}
        }
        detach();
    }
    public void detach() {
        if(target==null)return;
        flush();internal=true;
        try {
            var main=target;target=null;
            main.frameBufferId=originalFramebuffer;((RenderTargetAccess)main).rayvox$color(originalColor);((RenderTargetAccess)main).rayvox$depth(originalDepth);main.bindWrite(false);
            main.width=originalWidth;main.height=originalHeight;main.viewWidth=originalViewWidth;main.viewHeight=originalViewHeight;
            blit(framebuffer,originalFramebuffer,surface.depthTexture,internalWidth,internalHeight,originalWidth,originalHeight,true);
            main.bindWrite(true);
        }finally{internal=false;}
    }
    private void blit(int source,int destination,int sourceDepth,int sourceWidth,int sourceHeight,int destinationWidth,int destinationHeight,boolean resolve) {
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);boolean scissor=glIsEnabled(GL_SCISSOR_TEST);glDisable(GL_SCISSOR_TEST);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,destination);
            boolean scaled=sourceWidth!=destinationWidth||sourceHeight!=destinationHeight;
            boolean fxaa=dev.rayvox.Rayvox.ANTI_ALIASING.get()==AntiAliasing.FXAA;
            if(resolve&&(scaled||fxaa))worldResolve.resolve(surface.colorTexture,destination,destinationWidth,destinationHeight,scaled,fxaa,dev.rayvox.Rayvox.UPSCALE_SHARPNESS.get()/100f,internalWidth,internalHeight);
            else glBlitFramebuffer(0,0,sourceWidth,sourceHeight,0,0,destinationWidth,destinationHeight,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            depthCopy.draw(sourceDepth,destinationWidth,destinationHeight,resolve?(float)internalWidth/surface.depth.width:1,resolve?(float)internalHeight/surface.depth.height:1);
        } finally {glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);if(scissor)glEnable(GL_SCISSOR_TEST);}
    }
    public boolean copyDepth(RenderTarget destination,RenderTarget source) {
        if(internal||target==null||source!=target&&destination!=target)return false;
        flush();internal=true;int old=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try{glBindFramebuffer(GL_DRAW_FRAMEBUFFER,destination.frameBufferId);depthCopy.draw(source.getDepthTextureId(),destination.width,destination.height,source==target?(float)internalWidth/surface.depth.width:1,source==target?(float)internalHeight/surface.depth.height:1);return true;}
        finally{glBindFramebuffer(GL_DRAW_FRAMEBUFFER,old);internal=false;}
    }
    private void drain(){if(context==null)return;if(frame!=null)endFrame();for(var slot:frames)if(slot!=null)slot.complete();glFinish();}
    public void reload() {
        detach();drain();for(var mesh:geometry.values()){mesh.vertices().buffer().close();mesh.indices().buffer().close();}geometry.clear();
        retired.complete(Long.MAX_VALUE);for(var pipeline:pipelines.values())pipeline.close();pipelines.clear();shaders.clear();samplerUnits.clear();unsupported.clear();samplerStates.clear();textures.close();TextureSnapshots.clear();
        if(lighting!=null)lighting.clear();
    }
    public record Timings(double gpuMs,double fenceWaitMs,int draws,int batches,int entityDraws,int particleDraws){public int terrainDraws(){return draws-entityDraws-particleDraws;}}
    public Timings timings(){return new Timings(gpuMillis,waitMillis,lastDraws,lastBatches,lastEntityDraws,lastParticleDraws);}
    public boolean rayTracingSupported(){return context.rayTracingSupported;}
    public List<String> validationMessages(){synchronized(context.validationMessages){return List.copyOf(context.validationMessages);}}
    public void update(Scene.Update update){if(lighting!=null)lighting.update(update,serial);}
    public void render(Scene.Camera camera){if(lighting!=null)lighting.camera(camera);}
    public Diagnostics diagnostics(){return new Diagnostics(shaderLighting?"Vulkan shader lighting":"Vulkan vanilla",context==null?"uninitialized":context.name,reason,surface==null?0:internalWidth,surface==null?0:internalHeight,gpuMillis,context==null?0:context.allocated,geometry.size(),draws.size());}
    public void close() {
        if(context==null)return;
        detach();drain();retired.complete(Long.MAX_VALUE);
        for(var gpu:geometry.values()){gpu.vertices().buffer().close();gpu.indices().buffer().close();}geometry.clear();
        for(var slot:frames)if(slot!=null)slot.close();for(var pipeline:pipelines.values())pipeline.close();pipelines.clear();
        if(lighting!=null){lighting.close();lighting=null;}
        if(textures!=null)textures.close();if(depthCopy!=null)depthCopy.close();if(worldResolve!=null)worldResolve.close();if(framebuffer!=0){glDeleteFramebuffers(framebuffer);framebuffer=0;}if(surface!=null){surface.close();surface=null;}
        if(pipelineLayout!=0)vkDestroyPipelineLayout(context.device,pipelineLayout,null);if(descriptorLayout!=0)vkDestroyDescriptorSetLayout(context.device,descriptorLayout,null);
        context.close();context=null;
    }
}
