package dev.rayvox.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

final class VulkanImage implements AutoCloseable {
    final VulkanContext context;
    final int width,height,format;
    final int aspect;
    final int levels;
    long image,memory,view,size;
    boolean initialized;
    VulkanImage(VulkanContext context,int width,int height,int format,int usage,boolean shared) {
        this(context,width,height,format,usage,shared,1);
    }
    VulkanImage(VulkanContext context,int width,int height,int format,int usage,boolean shared,int levels) {
        this.context=context;this.width=width;this.height=height;this.format=format;
        this.levels=levels;
        aspect=format==VK_FORMAT_D32_SFLOAT?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT;
        try(var stack=MemoryStack.stackPush()) {
            var external=VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var info=VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .extent(e->e.set(width,height,1)).mipLevels(levels).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            if(shared) info.pNext(external.address());
            var out=stack.mallocLong(1);check(vkCreateImage(context.device,info,null,out),"create image");image=out.get(0);
            var requirements=VkMemoryRequirements.calloc(stack);vkGetImageMemoryRequirements(context.device,image,requirements);
            size=requirements.size();
            if(context.allocated+size>context.budget)throw new IllegalStateException("Vulkan image exceeds configured GPU memory budget");
            var properties=VkPhysicalDeviceMemoryProperties.calloc(stack);vkGetPhysicalDeviceMemoryProperties(context.physical,properties);
            int type=-1;
            for(int i=0;i<properties.memoryTypeCount();i++) if((requirements.memoryTypeBits()&(1<<i))!=0
                    && (properties.memoryTypes(i).propertyFlags()&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)!=0) {type=i;break;}
            if(type<0)throw new IllegalStateException("No image memory type");
            var dedicated=VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
            var export=VkExportMemoryAllocateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            if(shared)dedicated.pNext(export.address());
            var allocation=VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(size).memoryTypeIndex(type).pNext(dedicated.address());
            check(vkAllocateMemory(context.device,allocation,null,out),"allocate image");memory=out.get(0);context.allocated+=size;
            check(vkBindImageMemory(context.device,image,memory,0),"bind image");
            var viewInfo=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(aspect).levelCount(levels).layerCount(1);
            check(vkCreateImageView(context.device,viewInfo,null,out),"create image view");view=out.get(0);
        } catch(RuntimeException|Error failure) {close();throw failure;}
    }
    long exportHandle() {try(var stack=MemoryStack.stackPush()) {
        var info=VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default().memory(memory).handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        var out=stack.mallocPointer(1);check(vkGetMemoryWin32HandleKHR(context.device,info,out),"export image memory");return out.get(0);
    } }
    void transition(VkCommandBuffer cmd,int oldLayout,int newLayout,int sourceStage,int sourceAccess,int targetStage,int targetAccess,int sourceFamily,int targetFamily) {
        try(var stack=MemoryStack.stackPush()) {
            var barriers=VkImageMemoryBarrier.calloc(1,stack).sType$Default().image(image).oldLayout(oldLayout).newLayout(newLayout)
                    .srcAccessMask(sourceAccess).dstAccessMask(targetAccess).srcQueueFamilyIndex(sourceFamily).dstQueueFamilyIndex(targetFamily);
            barriers.subresourceRange().aspectMask(aspect).levelCount(levels).layerCount(1);
            vkCmdPipelineBarrier(cmd,sourceStage,targetStage,0,null,null,barriers);
        }
    }
    void general(VkCommandBuffer cmd) {
        if(!initialized) {transition(cmd,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,0,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);initialized=true;}
    }
    @Override public void close() {
        if(view!=0){vkDestroyImageView(context.device,view,null);view=0;}
        if(image!=0){vkDestroyImage(context.device,image,null);image=0;}
        if(memory!=0){vkFreeMemory(context.device,memory,null);context.allocated-=size;memory=0;}
    }
}
