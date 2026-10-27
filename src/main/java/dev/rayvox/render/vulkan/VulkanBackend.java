package dev.rayvox.render.vulkan;

import dev.rayvox.render.*;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

/** Original Rayvox compute path tracer. All native state is confined to the render thread. */
public final class VulkanBackend implements RenderBackend {
    private static final int TEXTURES=1024;
    private final long memoryBudget;
    private final int referenceSamples;
    private VulkanContext context;
    private final Frame[] frames=new Frame[2];
    private final Map<Scene.Key,GpuMesh> meshes=new LinkedHashMap<>();
    private final Map<Scene.Key,Scene.Mesh> pending=new LinkedHashMap<>();
    private final Map<Integer,GpuTexture> textures=new LinkedHashMap<>();
    private final ArrayDeque<Integer> freeTextureSlots=new ArrayDeque<>();
    private int nextTextureSlot;
    private final ArrayDeque<Scene.Texture> pendingTextures=new ArrayDeque<>();
    private final RetirementQueue retired=new RetirementQueue();
    private final ResolutionController resolution=new ResolutionController();
    private long descriptorLayout,descriptorPool,pipelineLayout,sampler,serial;
    private long sceneRevision;
    private long textureRevision,imageRevision;
    private ComputeProgram trace,reconstruct,composite,exposureProgram;
    private VulkanContext.Buffer exposure;
    private VulkanImage radiance,geometry,normals,history,previousGeometry,resolved;
    private final VulkanImage[] upscale=new VulkanImage[2];
    private GlComposite presenter;
    private int width,height,internalWidth,internalHeight,index;
    private int buildLimit=4,effectiveRadius=Integer.MAX_VALUE;
    private double buildMillis;
    private double waitMillis,recordMillis,presentMillis;
    private boolean reset=true;
    private boolean memoryPressure;
    private double gpuMillis=Double.NaN;
    private Matrix4f previousVP=new Matrix4f();
    private double previousX,previousY,previousZ;
    private double originX,originY,originZ;
    private long previousWorld=Long.MIN_VALUE;
    private String reason="";
    private record Emitter(int triangle,float area){}
    private record GpuMesh(Scene.Mesh source,VulkanContext.Buffer vertices,Acceleration acceleration,List<Emitter> emitters) implements AutoCloseable {
        public void close(){acceleration.close();vertices.close();}
    }
    private record GpuTexture(long generation,int slot,VulkanImage image) implements AutoCloseable {
        public void close(){image.close();}
    }
    private final class Frame implements AutoCloseable {
        VkCommandBuffer command;
        int slot;
        long fence,set,query,submission;
        long sceneVersion=-1;
        long descriptorScene=-1,descriptorImages=-1,descriptorTextures=-1;
        int uploadOffset;
        VulkanContext.Buffer camera,table,instances,emitterTable;
        VulkanContext.Buffer uploads;
        Acceleration top;
        SharedSurface surface;
        final List<AutoCloseable> temporary=new ArrayList<>();
        void complete() {
            if(submission!=0) {
                try(var stack=MemoryStack.stackPush()) {
                    check(vkWaitForFences(context.device,fence,true,10_000_000_000L),"wait for frame");
                    if(query!=0) {
                        var stamps=stack.mallocLong(3);
                        if(vkGetQueryPoolResults(context.device,query,0,3,stamps,Long.BYTES,VK_QUERY_RESULT_64_BIT)==VK_SUCCESS) {
                            long mask=context.timestampBits==64 ? -1L : (1L<<context.timestampBits)-1;
                            gpuMillis=((stamps.get(2)-stamps.get(0))&mask)*context.timestampPeriod/1_000_000.0;
                            buildMillis=((stamps.get(1)-stamps.get(0))&mask)*context.timestampPeriod/1_000_000.0;
                            if(buildMillis>2.2)buildLimit=Math.max(1,buildLimit-1);else if(buildMillis<1)buildLimit=Math.min(4,buildLimit+1);
                            if(referenceSamples==1)resolution.sample(gpuMillis);
                        }
                    }
                }
                retired.complete(submission);
                submission=0;
            }
            for(var resource:temporary)try{resource.close();}catch(Exception e){throw new IllegalStateException(e);}
            temporary.clear();uploadOffset=0;
        }
        void releaseScene(){
            if(top!=null){top.close();top=null;}
            if(table!=null){table.close();table=null;}
            if(instances!=null){instances.close();instances=null;}
            if(emitterTable!=null){emitterTable.close();emitterTable=null;}
            sceneVersion=-1;
        }
        @Override public void close() {
            complete();releaseScene();if(surface!=null)surface.close();if(camera!=null)camera.close();if(uploads!=null)uploads.close();
            if(query!=0)vkDestroyQueryPool(context.device,query,null);
            if(fence!=0)vkDestroyFence(context.device,fence,null);
        }
    }
    public VulkanBackend(long memoryBudget) {this(memoryBudget,Integer.getInteger("rayvox.vulkan.referenceSamples",1));}
    public VulkanBackend(long memoryBudget,int referenceSamples) {this.memoryBudget=memoryBudget;this.referenceSamples=Math.clamp(referenceSamples,1,256);}
    @Override public void initialize() {
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))throw new IllegalStateException("Vulkan path tracing currently supports Windows only");
        try {
            context=new VulkanContext(SharedSurface.deviceUuid(),Boolean.getBoolean("rayvox.vulkan.validation"));
            context.budget=memoryBudget;
            descriptors();
            trace=new ComputeProgram(context,pipelineLayout,"trace.comp");
            reconstruct=new ComputeProgram(context,pipelineLayout,"reconstruct.comp");
            composite=new ComputeProgram(context,pipelineLayout,"composite.comp");
            exposureProgram=new ComputeProgram(context,pipelineLayout,"exposure.comp");
            exposure=context.buffer(16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);exposure.mapped.putFloat(0,1);exposure.flush();
            presenter=new GlComposite();
            for(int i=0;i<frames.length;i++){frames[i]=createFrame();frames[i].slot=i;}
            var white=ByteBuffer.allocateDirect(4);white.putInt(-1).flip();
            pendingTextures.add(new Scene.Texture(0,0,1,1,white));
        } catch(RuntimeException|Error failure){close();throw failure;}
    }
    private void descriptors() {try(var stack=MemoryStack.stackPush()) {
        var bindings=VkDescriptorSetLayoutBinding.calloc(16,stack);
        for(int i=0;i<16;i++)bindings.get(i).binding(i).descriptorCount(i==9?TEXTURES:1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                .descriptorType(i==0?VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR:i==4||i==12||i==13?VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:i==5?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:i==9?VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        var flags=stack.callocInt(16);flags.put(9,VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);
        var flagInfo=VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack).sType$Default().pBindingFlags(flags);
        var out=stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings).pNext(flagInfo.address()),null,out),"create descriptor layout");descriptorLayout=out.get(0);
        var sizes=VkDescriptorPoolSize.calloc(5,stack);
        int[] types={VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};
        int[] counts={2,6,2,20,TEXTURES*2};
        for(int i=0;i<5;i++)sizes.get(i).type(types[i]).descriptorCount(counts[i]);
        check(vkCreateDescriptorPool(context.device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(2).pPoolSizes(sizes),null,out),"create descriptor pool");descriptorPool=out.get(0);
        var push=VkPushConstantRange.calloc(1,stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(8);
        check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push),null,out),"create pipeline layout");pipelineLayout=out.get(0);
        check(vkCreateSampler(context.device,VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT).maxLod(0),null,out),"create sampler");sampler=out.get(0);
    } }
    private Frame createFrame() {var frame=new Frame();try(var stack=MemoryStack.stackPush()) {
        frame.command=context.commandBuffer();var out=stack.mallocLong(1);
        check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,out),"create frame fence");frame.fence=out.get(0);
        var allocate=VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout));
        check(vkAllocateDescriptorSets(context.device,allocate,out),"allocate descriptor set");frame.set=out.get(0);
        frame.camera=context.buffer(512,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,true);
        frame.uploads=context.buffer(16*1048576,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,true);
        if(context.timestampBits!=0){check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(3),null,out),"create timestamp pool");frame.query=out.get(0);}
        return frame;
    }catch(RuntimeException|Error e){frame.close();throw e;} }
    @Override public void update(Scene.Update update) {
        for(var key:update.removed()) {
            pending.remove(key);var old=meshes.remove(key);if(old!=null){sceneRevision++;retired.retire(serial,old::close);}
        }
        for(var mesh:update.meshes()) {
            var old=pending.get(mesh.key());var live=meshes.get(mesh.key());
            long generation=old==null?(live==null?-1:live.source().generation()):old.generation();
            if(mesh.generation()>generation) {
                if(!pending.containsKey(mesh.key())&&pending.size()>=384)throw new IllegalStateException("Vulkan mesh queue capacity exceeded");
                pending.put(mesh.key(),mesh);
            }
        }
        for(var texture:update.textures()) {
            var old=textures.get(texture.id());
            if(old==null||texture.generation()>old.generation())pendingTextures.add(texture);
        }
        reset|=update.resetHistory();
    }
    @Override public void resize(int width,int height) {
        if(width<=0||height<=0)return;
        if(this.width==width&&this.height==height)return;
        drain();
        // A resize is a lifecycle boundary; flush GL before deleting imported storage.
        org.lwjgl.opengl.GL11.glFinish();
        this.width=width;this.height=height;
        for(var frame:frames){if(frame.surface!=null){frame.surface.close();frame.surface=null;}frame.surface=new SharedSurface(context,width,height);}
        resizeInternal();
    }
    private void resizeInternal() {
        drain();releaseImages();internalWidth=resolution.dimension(width);internalHeight=resolution.dimension(height);
        long estimate=(long)internalWidth*internalHeight*80+(long)width*height*40;
        if(estimate>memoryBudget)throw new IllegalStateException("Path tracing image memory exceeds the configured budget");
        radiance=image(VK_FORMAT_R16G16B16A16_SFLOAT);geometry=image(VK_FORMAT_R32G32B32A32_SFLOAT);normals=image(VK_FORMAT_R16G16B16A16_SFLOAT);
        history=image(VK_FORMAT_R16G16B16A16_SFLOAT);previousGeometry=image(VK_FORMAT_R32G32B32A32_SFLOAT);resolved=image(VK_FORMAT_R16G16B16A16_SFLOAT);reset=true;imageRevision++;
        for(int i=0;i<2;i++)upscale[i]=new VulkanImage(context,width,height,VK_FORMAT_R16G16B16A16_SFLOAT,VK_IMAGE_USAGE_STORAGE_BIT,false);
    }
    private VulkanImage image(int format){return new VulkanImage(context,internalWidth,internalHeight,format,VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,false);}
    private void drain(){if(context!=null)for(var frame:frames)if(frame!=null)frame.complete();}
    private void releaseImages(){for(var image:new VulkanImage[]{radiance,geometry,normals,history,previousGeometry,resolved,upscale[0],upscale[1]})if(image!=null)image.close();radiance=geometry=normals=history=previousGeometry=resolved=null;upscale[0]=upscale[1]=null;}
    @Override public void render(Scene.Camera camera) {
        if(width<=0||height<=0)return;
        long cpuStart=System.nanoTime();Frame frame=frames[index];frame.complete();waitMillis=(System.nanoTime()-cpuStart)/1e6;
        long recordStart=System.nanoTime();
        double nextX=Math.floor(camera.x()/256)*256,nextY=Math.floor(camera.y()/256)*256,nextZ=Math.floor(camera.z()/256)*256;
        if(nextX!=originX||nextY!=originY||nextZ!=originZ){originX=nextX;originY=nextY;originZ=nextZ;sceneRevision++;reset=true;}
        if(internalWidth!=resolution.dimension(width)||internalHeight!=resolution.dimension(height))resizeInternal();
        context.begin(frame.command);
        if(frame.query!=0){vkCmdResetQueryPool(frame.command,frame.query,0,3);vkCmdWriteTimestamp(frame.command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,frame.query,0);}
        processTextures(frame);processMeshes(frame);
        prepareScene(frame,camera);
        if(frame.query!=0)vkCmdWriteTimestamp(frame.command,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,frame.query,1);
        for(var image:new VulkanImage[]{radiance,geometry,normals,history,previousGeometry,resolved,upscale[0],upscale[1]})image.general(frame.command);
        frame.surface.acquire(frame.command);
        writeCamera(frame,camera);writeDescriptors(frame);
        vkCmdBindDescriptorSets(frame.command,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,new long[]{frame.set},null);
        dispatch(frame,trace,internalWidth,internalHeight);
        computeBarrier(frame.command);
        if(referenceSamples==1)try(var stack=MemoryStack.stackPush()) {
            vkCmdPushConstants(frame.command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(0,1));
            dispatch(frame,reconstruct,internalWidth,internalHeight);computeBarrier(frame.command);
            copyImage(frame.command,resolved,history);
            vkCmdPushConstants(frame.command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(1,1));
            dispatch(frame,reconstruct,internalWidth,internalHeight);computeBarrier(frame.command);
            vkCmdPushConstants(frame.command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(2,2));
            dispatch(frame,reconstruct,internalWidth,internalHeight);computeBarrier(frame.command);
            vkCmdPushConstants(frame.command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(1,4));
            dispatch(frame,reconstruct,internalWidth,internalHeight);computeBarrier(frame.command);
            vkCmdPushConstants(frame.command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(2,8));
            dispatch(frame,reconstruct,internalWidth,internalHeight);computeBarrier(frame.command);
            copyImage(frame.command,resolved,radiance);
        }
        dispatch(frame,exposureProgram,1,1);computeBarrier(frame.command);
        dispatch(frame,composite,width,height);computeBarrier(frame.command);
        copyImage(frame.command,geometry,previousGeometry);
        frame.surface.release(frame.command);
        if(frame.query!=0)vkCmdWriteTimestamp(frame.command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,frame.query,2);
        check(vkEndCommandBuffer(frame.command),"end frame");
        try(var stack=MemoryStack.stackPush()) {
            check(vkResetFences(context.device,frame.fence),"reset frame fence");
            var submit=VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(frame.command.address())).pSignalSemaphores(stack.longs(frame.surface.ready));
            if(frame.surface.consumed)submit.pWaitSemaphores(stack.longs(frame.surface.complete)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT));
            check(vkQueueSubmit(context.queue,submit,frame.fence),"submit frame");frame.submission=++serial;
        }
        recordMillis=(System.nanoTime()-recordStart)/1e6;
        long presentStart=System.nanoTime();presenter.draw(frame.surface,width,height);frame.surface.signalComplete();presentMillis=(System.nanoTime()-presentStart)/1e6;
        previousVP.set(camera.viewProjection());previousX=camera.x();previousY=camera.y();previousZ=camera.z();previousWorld=camera.world();reset=false;index=(index+1)%frames.length;
    }
    private static void computeBarrier(VkCommandBuffer cmd){VulkanContext.memoryBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);}
    private void dispatch(Frame frame,ComputeProgram program,int width,int height){vkCmdBindPipeline(frame.command,VK_PIPELINE_BIND_POINT_COMPUTE,program.pipeline);vkCmdDispatch(frame.command,(width+7)/8,(height+7)/8,1);}
    private void processMeshes(Frame frame) {
        long start=System.nanoTime(),bytes=0;int count=0;
        for(var iterator=pending.entrySet().iterator();iterator.hasNext()&&count<buildLimit;) {
            if(count>0&&(System.nanoTime()-start>2_000_000||bytes>=8*1048576))break;
            var entry=iterator.next();var source=entry.getValue();
            if(context.allocated+source.vertices().remaining()*3L>memoryBudget){
                reason="Scene update deferred: GPU memory budget";memoryPressure=true;
                if(effectiveRadius<=2)throw new IllegalStateException("Scene mesh cannot fit the configured memory budget");
                break;
            }
            memoryPressure=false;reason="";
            iterator.remove();var old=meshes.remove(entry.getKey());
            if(old!=null){sceneRevision++;reset|=!source.dynamic();retired.retire(serial,old::close);}
            if(source.triangles()==0)continue;
            var vertices=context.buffer(source.vertices().remaining(),VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT|VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,false);
            upload(frame,vertices,source.vertices());
            VulkanContext.memoryBarrier(frame.command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_SHADER_READ_BIT);
            var acceleration=Acceleration.bottom(context,frame.command,vertices,source.triangles());
            frame.temporary.add(acceleration.scratch);acceleration.scratch=null;
            var emitters=new ArrayList<Emitter>();var data=source.vertices();
            for(int triangle=0;triangle<source.triangles();triangle++)if(data.getFloat(triangle*192+28)>.001f) {
                int k=triangle*192;
                var a=new org.joml.Vector3f(data.getFloat(k),data.getFloat(k+4),data.getFloat(k+8));
                var b=new org.joml.Vector3f(data.getFloat(k+64),data.getFloat(k+68),data.getFloat(k+72)).sub(a);
                var c=new org.joml.Vector3f(data.getFloat(k+128),data.getFloat(k+132),data.getFloat(k+136)).sub(a);
                emitters.add(new Emitter(triangle,b.cross(c).length()*.5f));
            }
            meshes.put(entry.getKey(),new GpuMesh(source,vertices,acceleration,emitters));sceneRevision++;count++;bytes+=source.vertices().remaining();reset|=!source.dynamic();
        }
    }
    private void upload(Frame frame,VulkanContext.Buffer destination,ByteBuffer source) {
        var staging=staging(frame,source);
        try(var stack=MemoryStack.stackPush()) {vkCmdCopyBuffer(frame.command,staging.buffer().handle,destination.handle,VkBufferCopy.calloc(1,stack).srcOffset(staging.offset()).size(source.remaining()));}
    }
    private record Upload(VulkanContext.Buffer buffer,int offset){}
    private Upload staging(Frame frame,ByteBuffer source) {
        int size=source.remaining(),offset=frame.uploadOffset;
        var buffer=frame.uploads;
        if(offset+size>buffer.mapped.capacity()){buffer=context.buffer(size,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,true);frame.temporary.add(buffer);offset=0;}
        else frame.uploadOffset=(offset+size+3)&~3;
        buffer.mapped.position(offset);buffer.mapped.put(source.duplicate());buffer.flush();return new Upload(buffer,offset);
    }
    private void processTextures(Frame frame) {
        for(var iterator=pendingTextures.iterator();iterator.hasNext();) {
            var source=iterator.next();var old=textures.get(source.id());
            if(source.width()==0) {
                if(old!=null){textures.remove(source.id());freeTextureSlots.add(old.slot());retired.retire(serial,old::close);sceneRevision++;textureRevision++;reset=true;}
                iterator.remove();continue;
            }
            if(old==null&&textures.size()>=TEXTURES)throw new IllegalStateException("Texture descriptor capacity exceeded");
            boolean replace=old==null||old.image().width!=source.fullWidth()||old.image().height!=source.fullHeight();
            var image=replace?new VulkanImage(context,source.fullWidth(),source.fullHeight(),VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,false):old.image();
            var staging=staging(frame,source.rgba());
            image.transition(frame.command,replace?VK_IMAGE_LAYOUT_UNDEFINED:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    replace?VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT:VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,replace?0:VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            try(var stack=MemoryStack.stackPush()) {
                var copy=VkBufferImageCopy.calloc(1,stack);copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);copy.imageExtent().set(source.width(),source.height(),1);
                copy.bufferOffset(staging.offset());
                copy.imageOffset().set(source.x(),source.y(),0);
                vkCmdCopyBufferToImage(frame.command,staging.buffer().handle,image.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy);
            }
            image.transition(frame.command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            int slot=old!=null?old.slot():freeTextureSlots.isEmpty()?nextTextureSlot++:freeTextureSlots.remove();
            textures.put(source.id(),new GpuTexture(source.generation(),slot,image));
            if(replace)textureRevision++;
            if(old==null)sceneRevision++;
            if(old!=null&&replace)retired.retire(serial,old::close);iterator.remove();reset|=replace;
        }
    }
    private void prepareScene(Frame frame,Scene.Camera camera) {
        if(frame.sceneVersion==sceneRevision)return;
        frame.releaseScene();
        int count=meshes.size();
        frame.instances=context.buffer(Math.max(1,count)*VkAccelerationStructureInstanceKHR.SIZEOF,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,true);
        frame.table=context.buffer(Math.max(1,count)*16L,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
        int emitterCount=meshes.values().stream().mapToInt(m->m.emitters().size()).sum();
        frame.emitterTable=context.buffer(16L+32L*emitterCount,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
        frame.emitterTable.mapped.putInt(emitterCount).putInt(0).putInt(0).putInt(0);
        int i=0;
        for(var entry:meshes.entrySet()) {
            var mesh=entry.getValue();var source=mesh.source();
            var instance=VkAccelerationStructureInstanceKHR.create(MemoryUtil.memAddress(frame.instances.mapped)+(long)i*VkAccelerationStructureInstanceKHR.SIZEOF);
            instance.clear();
            instance.transform().matrix(0,1).matrix(5,1).matrix(10,1).matrix(3,(float)(source.x()-originX)).matrix(7,(float)(source.y()-originY)).matrix(11,(float)(source.z()-originZ));
            instance.instanceCustomIndex(i).mask(255).instanceShaderBindingTableRecordOffset(0).flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR).accelerationStructureReference(mesh.acceleration().address);
            var texture=textures.get(source.texture());
            int identity=(entry.getKey().hashCode()&0x007fffff)|(source.dynamic()?0x00800000:0);
            frame.table.mapped.putLong(mesh.vertices().address()).putInt(texture==null?0:texture.slot()).putInt(identity);i++;
            for(var emitter:mesh.emitters()) {
                frame.emitterTable.mapped.putInt(i-1).putInt(emitter.triangle()).putInt(0).putInt(0).putFloat((float)(source.x()-originX)).putFloat((float)(source.y()-originY)).putFloat((float)(source.z()-originZ)).putFloat(emitter.area());
            }
        }
        frame.instances.flush();frame.table.flush();frame.emitterTable.flush();
        VulkanContext.memoryBarrier(frame.command,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        frame.top=Acceleration.top(context,frame.command,frame.instances,count);
        VulkanContext.memoryBarrier(frame.command,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        frame.sceneVersion=sceneRevision;
    }
    private void writeCamera(Frame frame,Scene.Camera camera) {
        var bytes=frame.camera.mapped;bytes.clear();camera.inverseViewProjection().get(0,bytes);
        new Matrix4f(previousVP).translate((float)(camera.x()-previousX),(float)(camera.y()-previousY),(float)(camera.z()-previousZ)).get(64,bytes);
        camera.viewProjection().get(128,bytes);bytes.position(192);
        bytes.putFloat((float)(camera.x()-originX)).putFloat((float)(camera.y()-originY)).putFloat((float)(camera.z()-originZ)).putFloat((float)serial);
        put(bytes,camera.sunDirection(),0);put(bytes,camera.sunRadiance(),0);
        boolean cut=reset||camera.cut()||camera.world()!=previousWorld||Math.abs(previousX-camera.x())+Math.abs(previousY-camera.y())+Math.abs(previousZ-camera.z())>8;
        put(bytes,camera.sky(),cut?1:0);bytes.putFloat(width).putFloat(height).putFloat(referenceSamples).putFloat(meshes.values().stream().anyMatch(m->m.source().dynamic())?1:0);
        for(int i=0;i<4;i++){var light=i<camera.lights().size()?camera.lights().get(i):null;bytes.putFloat(light==null?0:(float)(light.x()-originX)).putFloat(light==null?0:(float)(light.y()-originY)).putFloat(light==null?0:(float)(light.z()-originZ)).putFloat(0);}
        for(int i=0;i<4;i++){var light=i<camera.lights().size()?camera.lights().get(i):null;bytes.putFloat(light==null?0:light.red()).putFloat(light==null?0:light.green()).putFloat(light==null?0:light.blue()).putFloat(light==null?0:light.radius());}
        put(bytes,camera.fogColor(),camera.fogStart());bytes.putFloat(camera.fogEnd()).putFloat(0).putFloat(0).putFloat(0);
        frame.camera.flush();
    }
    private static void put(ByteBuffer bytes,org.joml.Vector3f value,float w){bytes.putFloat(value.x).putFloat(value.y).putFloat(value.z).putFloat(w);}
    private void writeDescriptors(Frame frame) {try(var stack=MemoryStack.stackPush()) {
        if(frame.descriptorScene!=frame.sceneVersion||frame.descriptorImages!=imageRevision) {
        var writes=VkWriteDescriptorSet.calloc(15,stack);
        for(int i=0;i<15;i++)writes.get(i).sType$Default().dstSet(frame.set).dstBinding(i<9?i:i+1).descriptorCount(1);
        var acceleration=VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(frame.top.handle));
        writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).pNext(acceleration.address());
        VulkanImage[] images={null,radiance,geometry,normals,null,null,history,previousGeometry,resolved,null,frame.surface.color,frame.surface.depth};
        for(int i=0;i<12;i++)if(images[i]!=null)writes.get(i<9?i:i-1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(images[i].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
        writes.get(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(frame.table.handle).range(VK_WHOLE_SIZE));
        writes.get(5).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(frame.camera.handle).range(VK_WHOLE_SIZE));
        writes.get(11).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(frame.emitterTable.handle).range(VK_WHOLE_SIZE));
        writes.get(12).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(exposure.handle).range(VK_WHOLE_SIZE));
        writes.get(13).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(upscale[1-frame.slot].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
        writes.get(14).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(upscale[frame.slot].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
        vkUpdateDescriptorSets(context.device,writes,null);frame.descriptorScene=frame.sceneVersion;frame.descriptorImages=imageRevision;
        }
        if(frame.descriptorTextures==textureRevision)return;
        var textureInfo=VkDescriptorImageInfo.calloc(TEXTURES,stack);
        var white=textures.get(0);
        for(int i=0;i<TEXTURES;i++)textureInfo.get(i).imageView(white.image().view).sampler(sampler).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        for(var texture:textures.values())textureInfo.get(texture.slot()).imageView(texture.image().view).sampler(sampler).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        var writes=VkWriteDescriptorSet.calloc(1,stack).sType$Default().dstSet(frame.set).dstBinding(9).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(textureInfo).descriptorCount(textureInfo.remaining());
        vkUpdateDescriptorSets(context.device,writes,null);
        frame.descriptorTextures=textureRevision;
    } }
    private void copyImage(VkCommandBuffer cmd,VulkanImage source,VulkanImage destination) {try(var stack=MemoryStack.stackPush()) {
        VulkanContext.memoryBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT);
        var region=VkImageCopy.calloc(1,stack);region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);region.extent().set(internalWidth,internalHeight,1);
        vkCmdCopyImage(cmd,source.image,VK_IMAGE_LAYOUT_GENERAL,destination.image,VK_IMAGE_LAYOUT_GENERAL,region);
        VulkanContext.memoryBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
    } }
    public void clearScene() {drain();for(var frame:frames)if(frame!=null)frame.releaseScene();for(var mesh:meshes.values())mesh.close();meshes.clear();pending.clear();sceneRevision++;reset=true;}
    public void clearTextures(){
        drain();for(var frame:frames)if(frame!=null)frame.releaseScene();
        for(var texture:textures.values())texture.close();textures.clear();pendingTextures.clear();freeTextureSlots.clear();nextTextureSlot=0;
        var white=ByteBuffer.allocateDirect(4).putInt(-1);white.flip();pendingTextures.add(new Scene.Texture(0,0,1,1,white));sceneRevision++;textureRevision++;reset=true;
    }
    public int sceneRadius(int requested) {
        effectiveRadius=Math.min(requested,effectiveRadius);
        if((memoryPressure||context.allocated>memoryBudget*.85)&&serial%60==0)effectiveRadius=Math.max(2,effectiveRadius-1);
        return effectiveRadius;
    }
    public int availableMeshSlots(){return Math.max(0,256-pending.size());}
    public int dynamicMeshCount(){return (int)meshes.values().stream().filter(mesh->mesh.source().dynamic()).count();}
    @Override public Diagnostics diagnostics(){return new Diagnostics("Vulkan path tracing",context==null?"uninitialized":context.name,reason,internalWidth,internalHeight,gpuMillis,context==null?0:context.allocated,meshes.size(),pending.size());}
    public List<String> validationMessages(){return context==null?List.of():List.copyOf(context.validationMessages);}
    public record Timings(double fenceWaitMs,double recordingMs,double presentationMs,double gpuBuildMs){}
    public Timings timings(){return new Timings(waitMillis,recordMillis,presentMillis,buildMillis);}
    @Override public void close() {
        if(context==null)return;
        drain();org.lwjgl.opengl.GL11.glFinish();retired.complete(Long.MAX_VALUE);
        for(var frame:frames)if(frame!=null)frame.close();releaseImages();
        for(var mesh:meshes.values())mesh.close();meshes.clear();
        for(var texture:textures.values())texture.close();textures.clear();
        if(presenter!=null)presenter.close();if(trace!=null)trace.close();if(reconstruct!=null)reconstruct.close();if(composite!=null)composite.close();if(exposureProgram!=null)exposureProgram.close();if(exposure!=null)exposure.close();
        if(sampler!=0)vkDestroySampler(context.device,sampler,null);if(pipelineLayout!=0)vkDestroyPipelineLayout(context.device,pipelineLayout,null);
        if(descriptorPool!=0)vkDestroyDescriptorPool(context.device,descriptorPool,null);if(descriptorLayout!=0)vkDestroyDescriptorSetLayout(context.device,descriptorLayout,null);
        context.close();context=null;
    }
}
