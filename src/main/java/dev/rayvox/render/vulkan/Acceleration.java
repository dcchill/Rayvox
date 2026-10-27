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
    private Acceleration(VulkanContext context) {this.context=context;}
    static Acceleration bottom(VulkanContext context,VkCommandBuffer cmd,VulkanContext.Buffer vertices,int triangles) {
        try(var stack=MemoryStack.stackPush()) {
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
            geometry.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexData(a->a.deviceAddress(vertices.address())).vertexStride(Scene.VERTEX_BYTES)
                    .maxVertex(triangles*3-1).indexType(VK_INDEX_TYPE_NONE_KHR);
            return build(context,cmd,geometry,triangles,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        }
    }
    static Acceleration top(VulkanContext context,VkCommandBuffer cmd,VulkanContext.Buffer instances,int count) {
        try(var stack=MemoryStack.stackPush()) {
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.geometry().instances().sType$Default().arrayOfPointers(false).data(a->a.deviceAddress(instances.address()));
            return build(context,cmd,geometry,count,VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        }
    }
    private static Acceleration build(VulkanContext context,VkCommandBuffer cmd,VkAccelerationStructureGeometryKHR.Buffer geometry,int count,int type) {
        var result=new Acceleration(context);
        try(var stack=MemoryStack.stackPush()) {
            var info=VkAccelerationStructureBuildGeometryInfoKHR.calloc(1,stack).sType$Default().type(type)
                    .flags(type==VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geometry).geometryCount(geometry.remaining());
            var sizes=VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(context.device,VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,info.get(0),stack.ints(count),sizes);
            result.storage=context.buffer(sizes.accelerationStructureSize(),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,false);
            var properties=VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            vkGetPhysicalDeviceProperties2(context.physical,VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(properties.address()));
            int alignment=properties.minAccelerationStructureScratchOffsetAlignment();
            result.scratch=context.buffer(sizes.buildScratchSize()+alignment,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,false);
            var create=VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(result.storage.handle).size(sizes.accelerationStructureSize()).type(type);
            var out=stack.mallocLong(1);check(vkCreateAccelerationStructureKHR(context.device,create,null,out),"create acceleration structure");result.handle=out.get(0);
            info.dstAccelerationStructure(result.handle).scratchData(a->a.deviceAddress((result.scratch.address()+alignment-1)&~(long)(alignment-1)));
            var ranges=VkAccelerationStructureBuildRangeInfoKHR.calloc(1,stack).primitiveCount(count);
            vkCmdBuildAccelerationStructuresKHR(cmd,info,stack.pointers(ranges.address()));
            result.address=vkGetAccelerationStructureDeviceAddressKHR(context.device,VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(result.handle));
            return result;
        } catch(RuntimeException|Error failure) {result.close();throw failure;}
    }
    @Override public void close() {
        if(handle!=0){vkDestroyAccelerationStructureKHR(context.device,handle,null);handle=0;}
        if(storage!=null){storage.close();storage=null;}if(scratch!=null){scratch.close();scratch=null;}
    }
}
