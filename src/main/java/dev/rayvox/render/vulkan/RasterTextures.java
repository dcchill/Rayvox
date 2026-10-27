package dev.rayvox.render.vulkan;

import dev.rayvox.render.*;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK13.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;

final class RasterTextures implements AutoCloseable {
    final VulkanContext context;
    final RetirementQueue retired;
    final Map<Integer,VulkanImage> images=new HashMap<>();
    final Map<RasterSampler,Long> samplers=new HashMap<>();
    RasterTextures(VulkanContext context,RetirementQueue retired){this.context=context;this.retired=retired;}
    long sampler(RasterSampler state) {
        return samplers.computeIfAbsent(state,s->{try(var stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);
            var info=VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(s.mag()==org.lwjgl.opengl.GL11.GL_LINEAR?VK_FILTER_LINEAR:VK_FILTER_NEAREST).minFilter(s.minFilter())
                    .mipmapMode(s.mipMode()).addressModeU(RasterSampler.wrap(s.s())).addressModeV(RasterSampler.wrap(s.t())).addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .minLod(Math.max(0,s.minLod())).maxLod(s.mipmapped()?Math.max(Math.max(0,s.minLod()),Math.min(s.maxLevel(),s.maxLod())):0).mipLodBias(s.bias());
            check(vkCreateSampler(context.device,info,null,out),"create vanilla sampler");return out.get(0);
        }});
    }
    void prepare() {
        for(var entry:images.entrySet())if(TextureSnapshots.levels(entry.getKey())!=entry.getValue().levels)TextureSnapshots.dirty(entry.getKey());
    }
    void upload(VkCommandBuffer command,List<Scene.Texture> updates,VulkanRasterBackend.Frame frame,long serial) {
        for(var source:updates) {
            if(source.width()==0){var old=images.remove(source.id());if(old!=null)retired.retire(serial,old::close);continue;}
            var image=images.get(source.id());int levels=Math.max(1,TextureSnapshots.levels(source.id()));
            if(image==null||image.levels!=levels||source.mipLevel()==0&&(image.width!=source.fullWidth()||image.height!=source.fullHeight())) {
                if(source.mipLevel()!=0)throw new IllegalStateException("Missing base texture upload");
                if(image!=null){var old=image;retired.retire(serial,old::close);}
                image=new VulkanImage(context,source.fullWidth(),source.fullHeight(),VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,false,levels);
                images.put(source.id(),image);
            }
            var staging=frame.copy(source.rgba(),4);
            image.transition(command,image.initialized?VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    image.initialized?VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT:VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,image.initialized?VK_ACCESS_SHADER_READ_BIT:0,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            try(var stack=MemoryStack.stackPush()) {
                var copy=VkBufferImageCopy.calloc(1,stack).bufferOffset(staging.offset());copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(source.mipLevel()).layerCount(1);
                copy.imageOffset().set(source.x(),source.y(),0);copy.imageExtent().set(source.width(),source.height(),1);
                vkCmdCopyBufferToImage(command,staging.buffer().handle,image.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy);
            }
            image.transition(command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,VK_ACCESS_SHADER_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            image.initialized=true;
        }
    }
    public void close(){for(var image:images.values())image.close();images.clear();for(long sampler:samplers.values())vkDestroySampler(context.device,sampler,null);samplers.clear();}
}
