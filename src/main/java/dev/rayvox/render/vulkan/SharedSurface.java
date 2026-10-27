package dev.rayvox.render.vulkan;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

/** One Vulkan producer and one OpenGL consumer. Each slot has its own semaphore pair. */
final class SharedSurface implements AutoCloseable {
    final VulkanContext context;
    VulkanImage color,depth;
    long ready,complete;
    int colorMemory,depthMemory,colorTexture,depthTexture,glReady,glComplete;
    boolean consumed;
    SharedSurface(VulkanContext context,int width,int height) {
        this.context=context;
        try {
            color=new VulkanImage(context,width,height,VK_FORMAT_R16G16B16A16_SFLOAT,VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,true);
            depth=new VulkanImage(context,width,height,VK_FORMAT_R32_SFLOAT,VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,true);
            colorMemory=importMemory(color);depthMemory=importMemory(depth);
            colorTexture=texture(color,colorMemory,GL_RGBA16F);depthTexture=texture(depth,depthMemory,GL_R32F);
            ready=semaphore();complete=semaphore();glReady=importSemaphore(ready);glComplete=importSemaphore(complete);
        } catch(RuntimeException|Error failure) {close();throw failure;}
    }
    static byte[] deviceUuid() {
        var caps=GL.getCapabilities();
        if(!caps.GL_EXT_memory_object || !caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_semaphore || !caps.GL_EXT_semaphore_win32)
            throw new IllegalStateException("OpenGL Windows memory/semaphore sharing is unavailable");
        try(var stack=MemoryStack.stackPush()) {
            if(glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT)!=1)
                throw new IllegalStateException("OpenGL must expose exactly one GPU UUID");
            var uuid=stack.malloc(16);EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT,0,uuid);
            byte[] result=new byte[16];uuid.get(result);return result;
        }
    }
    private int importMemory(VulkanImage image) {
        int object=EXTMemoryObject.glCreateMemoryObjectsEXT();long handle=0;
        try {
            EXTMemoryObject.glMemoryObjectParameteriEXT(object,EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT,GL_TRUE);
            handle=image.exportHandle();
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(object,image.size,EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,handle);
            glCheck("import memory");
            return object;
        } catch(RuntimeException|Error e) {EXTMemoryObject.glDeleteMemoryObjectsEXT(object);throw e;}
        finally {if(handle!=0)closeHandle(handle);}
    }
    private int texture(VulkanImage image,int memory,int format) {
        int old=glGetInteger(GL_TEXTURE_BINDING_2D),texture=glGenTextures();
        glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,EXTMemoryObject.GL_TEXTURE_TILING_EXT,EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
        EXTMemoryObject.glTexStorageMem2DEXT(GL_TEXTURE_2D,1,format,image.width,image.height,memory,0);
        glCheck("bind shared texture "+format);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D,old);return texture;
    }
    private long semaphore() {try(var stack=MemoryStack.stackPush()) {
        var export=VkExportSemaphoreCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        var info=VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address());var out=stack.mallocLong(1);
        check(vkCreateSemaphore(context.device,info,null,out),"create external semaphore");return out.get(0);
    } }
    private int importSemaphore(long semaphore) {try(var stack=MemoryStack.stackPush()) {
        int object=EXTSemaphore.glGenSemaphoresEXT();long handle=0;
        try {
            var info=VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default().semaphore(semaphore).handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var out=stack.mallocPointer(1);check(vkGetSemaphoreWin32HandleKHR(context.device,info,out),"export semaphore");handle=out.get(0);
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(object,EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,handle);return object;
        } catch(RuntimeException|Error e) {EXTSemaphore.glDeleteSemaphoresEXT(object);throw e;}
        finally {if(handle!=0)closeHandle(handle);}
    } }
    void acquire(VkCommandBuffer cmd) {
        for(var image:new VulkanImage[]{color,depth}) {
            if(consumed) image.transition(cmd,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,0,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_QUEUE_FAMILY_EXTERNAL,context.family);
            else image.general(cmd);
        }
    }
    private static final class WindowsHandles {
        static final org.lwjgl.system.SharedLibrary LIBRARY=org.lwjgl.system.Library.loadNative(SharedSurface.class,"org.lwjgl","kernel32");
        static final long CLOSE=LIBRARY.getFunctionAddress("CloseHandle");
    }
    private static void closeHandle(long handle) {org.lwjgl.system.JNI.invokePI(handle,WindowsHandles.CLOSE);}
    private static void glCheck(String action){int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException(action+" GL error "+error);}
    void release(VkCommandBuffer cmd) {
        for(var image:new VulkanImage[]{color,depth}) image.transition(cmd,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,context.family,VK_QUEUE_FAMILY_EXTERNAL);
    }
    void waitForVulkan() {EXTSemaphore.glWaitSemaphoreEXT(glReady,new int[0],new int[]{colorTexture,depthTexture},new int[]{EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT});}
    void signalComplete() {
        EXTSemaphore.glSignalSemaphoreEXT(glComplete,new int[0],new int[]{colorTexture,depthTexture},new int[]{EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT});
        glFlush();consumed=true;
    }
    @Override public void close() {
        if(colorTexture!=0){glDeleteTextures(colorTexture);colorTexture=0;}if(depthTexture!=0){glDeleteTextures(depthTexture);depthTexture=0;}
        if(colorMemory!=0){EXTMemoryObject.glDeleteMemoryObjectsEXT(colorMemory);colorMemory=0;}if(depthMemory!=0){EXTMemoryObject.glDeleteMemoryObjectsEXT(depthMemory);depthMemory=0;}
        if(glReady!=0){EXTSemaphore.glDeleteSemaphoresEXT(glReady);glReady=0;}if(glComplete!=0){EXTSemaphore.glDeleteSemaphoresEXT(glComplete);glComplete=0;}
        if(ready!=0){vkDestroySemaphore(context.device,ready,null);ready=0;}if(complete!=0){vkDestroySemaphore(context.device,complete,null);complete=0;}
        if(color!=null)color.close();if(depth!=null)depth.close();
    }
}
