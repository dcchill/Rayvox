package dev.rayvox.render.vulkan;

import java.nio.*;
import java.util.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayQuery.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.*;
import static org.lwjgl.util.vma.Vma.*;

/** Single graphics/compute queue; Vulkan never owns Minecraft's GLFW window. */
public final class VulkanContext implements AutoCloseable {
    public static final List<String> EXTENSIONS=List.of(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME,VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
            VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    VkQueue queue;
    long allocator, pool, allocated;
    long budget=Long.MAX_VALUE;
    int family, timestampBits;
    int scratchAlignment=256;
    boolean rayTracingSupported;
    boolean independentBlend;
    boolean dlssExtensions;
    String dlssUnavailable="DLSS was not enabled on this Vulkan device";
    float timestampPeriod;
    String name="";
    private long debugMessenger;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    public final List<String> validationMessages=new ArrayList<>();

    public static void check(int result, String action) {
        if (result != VK_SUCCESS) throw new IllegalStateException(action+" failed (VkResult "+result+")");
    }
    public VulkanContext(byte[] glDeviceUuid, boolean validation) {
        this(glDeviceUuid, validation, true);
    }
    public VulkanContext(byte[] glDeviceUuid, boolean validation, boolean rayTracing) {
        // LWJGL 3.3.3 enumerates all driver extensions on its thread-local stack.
        // Modern drivers exceed its 64 KiB default. Bootstrap on a fresh thread so
        // an already-created Minecraft render-thread stack cannot constrain it.
        var result=new java.util.concurrent.CompletableFuture<Void>();
        var bootstrap=new Thread(()->{try{bootstrapStack();initialize(glDeviceUuid,validation,rayTracing);result.complete(null);}catch(Throwable failure){result.completeExceptionally(failure);}},"Rayvox Vulkan bootstrap");
        bootstrap.setDaemon(true);bootstrap.start();
        try { result.join(); }
        catch(java.util.concurrent.CompletionException failure) {
            close();
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw failure;
        }
        catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    @SuppressWarnings("unchecked")
    private static void bootstrapStack() throws ReflectiveOperationException {
        // Compatibility shim for the pinned 3.3.3 library: its stack size is fixed
        // at class initialization, before mods load. Only this private, fresh
        // bootstrap thread receives a larger stack; Minecraft's stack is untouched.
        var field=MemoryStack.class.getDeclaredField("TLS");field.setAccessible(true);
        var local=(ThreadLocal<MemoryStack>)field.get(null);local.set(MemoryStack.create(1024*1024));
    }
    private void initialize(byte[] uuid, boolean validation, boolean rayTracing) {
        var required=new LinkedHashSet<>(rayTracing?EXTENSIONS:List.of(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME));
        try (var stack=MemoryStack.stackPush()) {
            var app=VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Rayvox"))
                    .apiVersion(VK_API_VERSION_1_3);
            var info=VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            var instanceExtensions=new LinkedHashSet<String>();
            boolean dlssCandidate=false;
            if(rayTracing&&DlssUpscaler.load()) {
                try {
                    var requested=DlssUpscaler.instanceExtensions();
                    var count=stack.ints(0);check(vkEnumerateInstanceExtensionProperties((ByteBuffer)null,count,null),"enumerate instance extensions");
                    var properties=VkExtensionProperties.calloc(count.get(0),stack);
                    check(vkEnumerateInstanceExtensionProperties((ByteBuffer)null,count,properties),"enumerate instance extensions");
                    var supported=new HashSet<String>();for(var property:properties)supported.add(property.extensionNameString());
                    if(!supported.containsAll(requested))dlssUnavailable="DLSS instance extensions are unavailable";
                    else {instanceExtensions.addAll(requested);dlssCandidate=true;}
                }catch(RuntimeException|LinkageError failure){dlssUnavailable="DLSS extension query: "+failure.getMessage();}
            }else if(rayTracing)dlssUnavailable=DlssUpscaler.loadFailure();
            if (validation) {
                var count=stack.ints(0); check(vkEnumerateInstanceLayerProperties(count,null),"enumerate layers");
                var layers=VkLayerProperties.calloc(count.get(0),stack);
                check(vkEnumerateInstanceLayerProperties(count,layers),"enumerate layers");
                boolean found=false;
                for(var layer:layers) if(layer.layerNameString().equals("VK_LAYER_KHRONOS_validation")) found=true;
                if(!found) throw new IllegalStateException("VK_LAYER_KHRONOS_validation is not installed");
                info.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")));
                instanceExtensions.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            }
            if(!instanceExtensions.isEmpty()) {
                var names=stack.mallocPointer(instanceExtensions.size());for(var extension:instanceExtensions)names.put(stack.UTF8(extension));names.flip();
                info.ppEnabledExtensionNames(names);
            }
            var out=stack.mallocPointer(1); check(vkCreateInstance(info,null,out),"create instance");
            instance=new VkInstance(out.get(0),info);
            if(validation) {
                debugCallback=VkDebugUtilsMessengerCallbackEXT.create((severity,type,data,user)-> {
                    String message=VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
                    synchronized(validationMessages) { validationMessages.add(message); }
                    System.err.println("Vulkan validation: "+message); return VK_FALSE;
                });
                var debug=VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                        .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                |EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
                                |EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT).pfnUserCallback(debugCallback);
                var h=stack.mallocLong(1); check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance,debug,null,h),"debug messenger");
                debugMessenger=h.get(0);
            }
            var count=stack.ints(0); check(vkEnumeratePhysicalDevices(instance,count,null),"enumerate GPUs");
            var devices=stack.mallocPointer(count.get(0)); check(vkEnumeratePhysicalDevices(instance,count,devices),"enumerate GPUs");
            String rejection="No Vulkan GPU matches Minecraft's OpenGL device";
            for(int index=0;index<devices.remaining();index++) {
                var candidate=new VkPhysicalDevice(devices.get(index),instance);
                var identity=VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
                var props=VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(identity.address());
                vkGetPhysicalDeviceProperties2(candidate,props);
                if(uuid!=null && !sameUuid(uuid,identity.deviceUUID())) continue;
                if(Integer.compareUnsigned(props.properties().apiVersion(),VK_API_VERSION_1_3)<0) {
                    rejection="Vulkan 1.3 is required"; continue;
                }
                check(vkEnumerateDeviceExtensionProperties(candidate,(ByteBuffer)null,count,null),"enumerate extensions");
                var extensions=VkExtensionProperties.calloc(count.get(0),stack);
                check(vkEnumerateDeviceExtensionProperties(candidate,(ByteBuffer)null,count,extensions),"enumerate extensions");
                var supported=new HashSet<String>(); for(var extension:extensions) supported.add(extension.extensionNameString());
                if(!supported.containsAll(required)) { rejection="Missing Vulkan "+(rayTracing?"ray-query or ":"")+"Windows interoperability extensions"; continue; }
                var ray=VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
                var accel=VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().pNext(ray.address());
                var f12=VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(accel.address());
                var f13=VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default().pNext(f12.address());
                var features=VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f13.address());
                vkGetPhysicalDeviceFeatures2(candidate,features);
                if(rayTracing && (!ray.rayQuery() || !accel.accelerationStructure() || !f12.bufferDeviceAddress()
                        || !f12.shaderSampledImageArrayNonUniformIndexing() || !f12.descriptorBindingPartiallyBound()
                        || !f13.synchronization2() || !features.features().shaderInt64())) {
                    rejection="Missing ray-query, descriptor indexing, shaderInt64 or synchronization2 features"; continue;
                }
                if(!rayTracing&&!f13.dynamicRendering()){rejection="Vulkan dynamic rendering is unavailable";continue;}
                vkGetPhysicalDeviceQueueFamilyProperties(candidate,count,null);
                var families=VkQueueFamilyProperties.calloc(count.get(0),stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate,count,families);
                int queueFlags=rayTracing?VK_QUEUE_COMPUTE_BIT:VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT;
                for(int q=0;q<families.remaining();q++) if((families.get(q).queueFlags()&queueFlags)==queueFlags) {
                    physical=candidate; family=q; timestampBits=families.get(q).timestampValidBits();
                    independentBlend=features.features().independentBlend();
                    rayTracingSupported=ray.rayQuery()&&accel.accelerationStructure()&&f12.bufferDeviceAddress()&&f12.shaderSampledImageArrayNonUniformIndexing()&&f12.descriptorBindingPartiallyBound()&&f13.synchronization2()&&features.features().shaderInt64();
                    name=props.properties().deviceNameString(); timestampPeriod=props.properties().limits().timestampPeriod(); break;
                }
                if(physical!=null) {
                    if(dlssCandidate&&props.properties().vendorID()==0x10de) {
                        try {
                            var requested=DlssUpscaler.deviceExtensions(instance,physical);
                            if(!features.features().shaderStorageImageExtendedFormats())dlssUnavailable="DLSS motion-vector image format is unsupported";
                            else if(!supported.containsAll(requested))dlssUnavailable="DLSS device extensions are unavailable";
                            else {required.addAll(requested);dlssExtensions=true;}
                        }catch(RuntimeException|LinkageError failure){dlssUnavailable="DLSS extension query: "+failure.getMessage();}
                    }else if(dlssCandidate)dlssUnavailable="DLSS requires a supported NVIDIA GPU";
                    break;
                }
            }
            if(physical==null) throw new IllegalStateException(rejection);
            if(rayTracing) {
                var properties=VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                vkGetPhysicalDeviceProperties2(physical,VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(properties.address()));
                scratchAlignment=properties.minAccelerationStructureScratchOffsetAlignment();
            }
            var ray=VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default().rayQuery(true);
            var accel=VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().accelerationStructure(true).pNext(ray.address());
            var f12=VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().bufferDeviceAddress(true)
                    .shaderSampledImageArrayNonUniformIndexing(true).descriptorBindingPartiallyBound(true).pNext(accel.address());
            var f13=VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default().synchronization2(rayTracing).dynamicRendering(!rayTracing);
            if(rayTracing)f13.pNext(f12.address());
            var queues=VkDeviceQueueCreateInfo.calloc(1,stack); queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
            var names=stack.mallocPointer(required.size()); for(String extension:required) names.put(stack.UTF8(extension)); names.flip();
            var deviceInfo=VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(f13.address()).pQueueCreateInfos(queues)
                    .ppEnabledExtensionNames(names).pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack).shaderInt64(rayTracing).shaderStorageImageExtendedFormats(dlssExtensions).independentBlend(!rayTracing&&independentBlend));
            int created=vkCreateDevice(physical,deviceInfo,null,out);
            if(created!=VK_SUCCESS&&dlssExtensions) {
                dlssExtensions=false;dlssUnavailable="DLSS device creation failed (VkResult "+created+")";
                var baseNames=stack.mallocPointer(EXTENSIONS.size());for(var extension:EXTENSIONS)baseNames.put(stack.UTF8(extension));baseNames.flip();
                deviceInfo.ppEnabledExtensionNames(baseNames);deviceInfo.pEnabledFeatures().shaderStorageImageExtendedFormats(false);
                created=vkCreateDevice(physical,deviceInfo,null,out);
            }
            check(created,"create device"); device=new VkDevice(out.get(0),physical,deviceInfo);
            vkGetDeviceQueue(device,family,0,out); queue=new VkQueue(out.get(0),device);
            var functions=VmaVulkanFunctions.calloc(stack).set(instance,device);
            var allocatorInfo=VmaAllocatorCreateInfo.calloc(stack).instance(instance).physicalDevice(physical).device(device)
                    .vulkanApiVersion(VK_API_VERSION_1_3).pVulkanFunctions(functions).flags(rayTracing?VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT:0);
            check(vmaCreateAllocator(allocatorInfo,out),"create allocator"); allocator=out.get(0);
            var h=stack.mallocLong(1);
            check(vkCreateCommandPool(device,VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family),null,h),"create command pool"); pool=h.get(0);
        }
    }
    private static boolean sameUuid(byte[] bytes, ByteBuffer actual) {
        if(bytes.length!=VK_UUID_SIZE) return false;
        for(int i=0;i<bytes.length;i++) if(bytes[i]!=actual.get(i)) return false;
        return true;
    }
    public Buffer buffer(long size, int usage, boolean mapped) {
        if(allocated+Math.max(16,size)>budget)throw new IllegalStateException("Vulkan allocation exceeds configured GPU memory budget");
        try(var stack=MemoryStack.stackPush()) {
            var info=VkBufferCreateInfo.calloc(stack).sType$Default().size(Math.max(16,size))
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var allocation=VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO)
                    .flags(mapped ? VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT|VMA_ALLOCATION_CREATE_MAPPED_BIT : 0);
            var h=stack.mallocLong(1); var a=stack.mallocPointer(1); var details=VmaAllocationInfo.calloc(stack);
            check(vmaCreateBuffer(allocator,info,allocation,h,a,details),"allocate buffer");
            allocated+=details.size();
            ByteBuffer memory=mapped ? org.lwjgl.system.MemoryUtil.memByteBuffer(details.pMappedData(),(int)Math.max(16,size)) : null;
            return new Buffer(h.get(0),a.get(0),details.size(),memory);
        }
    }
    public final class Buffer implements AutoCloseable {
        public final long handle, allocation, bytes;
        public final ByteBuffer mapped;
        private boolean closed;
        Buffer(long handle,long allocation,long bytes,ByteBuffer mapped) { this.handle=handle;this.allocation=allocation;this.bytes=bytes;this.mapped=mapped; }
        public long address() { try(var stack=MemoryStack.stackPush()) {
            return vkGetBufferDeviceAddress(device,VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle));
        } }
        public void flush() { vmaFlushAllocation(allocator,allocation,0,VK_WHOLE_SIZE); }
        @Override public void close() { if(!closed) { closed=true;vmaDestroyBuffer(allocator,handle,allocation);allocated-=bytes; } }
    }
    VkCommandBuffer commandBuffer() {
        try(var stack=MemoryStack.stackPush()) {
            var info=VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            var out=stack.mallocPointer(1);check(vkAllocateCommandBuffers(device,info,out),"allocate command buffer");return new VkCommandBuffer(out.get(0),device);
        }
    }
    void begin(VkCommandBuffer cmd) { try(var stack=MemoryStack.stackPush()) {
        check(vkResetCommandBuffer(cmd,0),"reset commands");
        check(vkBeginCommandBuffer(cmd,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin commands");
    } }
    static void memoryBarrier(VkCommandBuffer cmd,int sourceStage,int sourceAccess,int targetStage,int targetAccess) {
        try(var stack=MemoryStack.stackPush()) {
            var barriers=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(sourceAccess).dstAccessMask(targetAccess);
            vkCmdPipelineBarrier(cmd,sourceStage,targetStage,0,barriers,null,null);
        }
    }
    @Override public void close() {
        if(device!=null) { vkDeviceWaitIdle(device); if(pool!=0)vkDestroyCommandPool(device,pool,null);
            if(allocator!=0)vmaDestroyAllocator(allocator);vkDestroyDevice(device,null);device=null; }
        if(debugMessenger!=0)EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance,debugMessenger,null);
        if(debugCallback!=null)debugCallback.free();
        if(instance!=null){vkDestroyInstance(instance,null);instance=null;}
    }
}
