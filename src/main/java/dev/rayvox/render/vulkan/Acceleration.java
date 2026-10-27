package dev.rayvox.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static dev.rayvox.render.vulkan.VulkanContext.check;
import dev.rayvox.render.Scene;

final class Acceleration implements AutoCloseable {
    final VulkanContext context;
    VulkanContext.Buffer storage,scratch;
    long handle,address;
    int primitives,type;
    boolean updateable;
    private Acceleration(VulkanContext context) {this.context=context;}
    static Acceleration bottom(VulkanContext context,VkCommandBuffer cmd,VulkanContext.Buffer vertices,int triangles) {
        return bottom(context,cmd,vertices,triangles,null,false);
    }
    static Acceleration bottom(VulkanContext context,VkCommandBuffer cmd,VulkanContext.Buffer vertices,int triangles,Acceleration previous,boolean dynamic) {
        try(var stack=MemoryStack.stackPush()) {
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
            geometry.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexData(a->a.deviceAddress(vertices.address())).vertexStride(Scene.VERTEX_BYTES)
                    .maxVertex(triangles*3-1).indexType(VK_INDEX_TYPE_NONE_KHR);
            return build(context,cmd,geometry,triangles,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,previous,dynamic);
        }
    }
    static Acceleration top(VulkanContext context,VkCommandBuffer cmd,VulkanContext.Buffer instances,int count) {
        return top(context,cmd,instances,count,null);
    }
    static Acceleration top(VulkanContext context,VkCommandBuffer cmd,VulkanContext.Buffer instances,int count,Acceleration previous) {
        try(var stack=MemoryStack.stackPush()) {
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.geometry().instances().sType$Default().arrayOfPointers(false).data(a->a.deviceAddress(instances.address()));
            return build(context,cmd,geometry,count,VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,previous,true);
        }
    }
    static boolean canRefit(boolean updateable,int previousCount,int count){return updateable&&previousCount==count;}
    private static Acceleration build(VulkanContext context,VkCommandBuffer cmd,VkAccelerationStructureGeometryKHR.Buffer geometry,int count,int type,Acceleration previous,boolean updateable) {
        boolean update=previous!=null&&previous.type==type&&canRefit(previous.updateable,previous.primitives,count);
        var result=update?previous:new Acceleration(context);
        try(var stack=MemoryStack.stackPush()) {
            int flags=type==VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR&&!updateable?VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR:VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
            if(updateable)flags|=VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
            var info=VkAccelerationStructureBuildGeometryInfoKHR.calloc(1,stack).sType$Default().type(type)
                    .flags(flags).mode(update?VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR:VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geometry).geometryCount(geometry.remaining());
            if(update)info.srcAccelerationStructure(result.handle);
            int alignment=context.scratchAlignment;
            if(!update) {
            var sizes=VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(context.device,VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,info.get(0),stack.ints(count),sizes);
            result.storage=context.buffer(sizes.accelerationStructureSize(),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,false);
            result.scratch=context.buffer(Math.max(sizes.buildScratchSize(),updateable?sizes.updateScratchSize():0)+alignment,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,false);
            var create=VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(result.storage.handle).size(sizes.accelerationStructureSize()).type(type);
            var out=stack.mallocLong(1);check(vkCreateAccelerationStructureKHR(context.device,create,null,out),"create acceleration structure");result.handle=out.get(0);
            result.primitives=count;result.type=type;result.updateable=updateable;
            }
            info.dstAccelerationStructure(result.handle).scratchData(a->a.deviceAddress((result.scratch.address()+alignment-1)&~(long)(alignment-1)));
            var ranges=VkAccelerationStructureBuildRangeInfoKHR.calloc(1,stack).primitiveCount(count);
            vkCmdBuildAccelerationStructuresKHR(cmd,info,stack.pointers(ranges.address()));
            result.address=vkGetAccelerationStructureDeviceAddressKHR(context.device,VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(result.handle));
            if(!update&&previous!=null)previous.close();
            return result;
        } catch(RuntimeException|Error failure) {if(!update)result.close();throw failure;}
    }
    @Override public void close() {
        if(handle!=0){vkDestroyAccelerationStructureKHR(context.device,handle,null);handle=0;}
        if(storage!=null){storage.close();storage=null;}if(scratch!=null){scratch.close();scratch=null;}
    }
}
