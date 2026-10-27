package dev.rayvox.render.vulkan;

import dev.rayvox.render.UpscalingMode;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.lwjgl.vulkan.*;

/** Optional Windows NGX bridge. Every session call runs on the Vulkan render thread. */
final class DlssUpscaler implements AutoCloseable {
    private static boolean loaded;
    private static String loadFailure="DLSS bridge is not packaged";
    private static Path runtimeDirectory;
    private long session;
    private final VulkanContext context;
    private long reservedBytes;
    private boolean feature, failed;
    private String status="DLSS waiting for initialization";

    static synchronized boolean load() {
        if(loaded||runtimeDirectory!=null)return loaded;
        try {
            String override=System.getProperty("rayvox.dlss.library");
            Path library;
            if(override!=null)library=Path.of(override).toAbsolutePath();
            else {
                if(!System.getProperty("os.name").startsWith("Windows")||!System.getProperty("os.arch").equals("amd64"))return false;
                try(var bridge=DlssUpscaler.class.getResourceAsStream("/natives/windows-x86_64/rayvox_dlss.dll")) {
                    if(bridge==null)return false;
                    runtimeDirectory=Files.createTempDirectory("rayvox-dlss-");
                    library=runtimeDirectory.resolve("rayvox_dlss.dll");
                    runtimeDirectory.toFile().deleteOnExit();
                    Files.copy(bridge,library);library.toFile().deleteOnExit();
                    try(var runtime=DlssUpscaler.class.getResourceAsStream("/natives/windows-x86_64/nvngx_dlss.dll")) {
                        if(runtime==null)throw new IOException("DLSS runtime is not packaged");
                        Files.copy(runtime,runtimeDirectory.resolve("nvngx_dlss.dll"));
                        runtimeDirectory.resolve("nvngx_dlss.dll").toFile().deleteOnExit();
                    }
                }
            }
            runtimeDirectory=library.getParent();
            System.load(library.toString());
            if(abiVersion()!=1)throw new UnsatisfiedLinkError("DLSS bridge ABI mismatch");
            loaded=true;
        } catch(IOException|RuntimeException|LinkageError failure) {loadFailure=failure.getMessage();}
        return loaded;
    }

    static String loadFailure(){return loadFailure;}
    static List<String> instanceExtensions(){return List.of(extensions(0,0,runtimeDirectory.toString()));}
    static List<String> deviceExtensions(VkInstance instance,VkPhysicalDevice physical){return List.of(extensions(instance.address(),physical.address(),runtimeDirectory.toString()));}

    DlssUpscaler(VulkanContext context) {
        this.context=context;
        if(!context.dlssExtensions){failed=true;status=context.dlssUnavailable;return;}
        try {
            Path logs=Path.of("logs","rayvox-dlss").toAbsolutePath();Files.createDirectories(logs);
            session=initialize(context.instance.address(),context.physical.address(),context.device.address(),
                    VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"),VK10.vkGetInstanceProcAddr(context.instance,"vkGetDeviceProcAddr"),
                    runtimeDirectory.toString(),logs.toString());
            if(session==0)throw new IllegalStateException("NGX returned an empty session");
        }catch(IOException|RuntimeException|LinkageError failure){fail(failure);}
    }

    boolean available(){return !failed&&session!=0;}
    boolean ready(){return available()&&feature;}
    String status(){return status;}
    void fail(Throwable failure){failed=true;status="DLSS unavailable: "+failure.getMessage();}

    boolean create(VkCommandBuffer command,int inputWidth,int inputHeight,int outputWidth,int outputHeight,UpscalingMode mode) {
        if(!available())return false;
        try {
            int quality=inputWidth==outputWidth&&inputHeight==outputHeight?5:switch(mode){case PERFORMANCE->0;case BALANCED->1;default->2;};
            createFeature(session,command.address(),inputWidth,inputHeight,outputWidth,outputHeight,quality);
            long bytes=allocatedBytes(session);
            if(bytes<0||bytes>context.budget-context.allocated)throw new IllegalStateException("NGX allocations exceed the configured GPU memory budget");
            reservedBytes=bytes;context.allocated+=bytes;
            feature=true;status="DLSS ready";return true;
        }catch(RuntimeException|LinkageError failure){fail(failure);return false;}
    }

    boolean evaluate(VkCommandBuffer command,VulkanImage color,VulkanImage depth,VulkanImage motion,VulkanImage output,VulkanImage bias,
                     float jitterX,float jitterY,boolean reset,float frameMillis) {
        if(!ready())return false;
        try {
            evaluateFeature(session,command.address(),new long[]{color.image,color.view,depth.image,depth.view,motion.image,motion.view,output.image,output.view,bias.image,bias.view},
                    color.width,color.height,output.width,output.height,jitterX,jitterY,reset,frameMillis);
            status="DLSS active";return true;
        }catch(RuntimeException|LinkageError failure){fail(failure);return false;}
    }

    /** The caller must drain submitted commands before releasing an NGX feature. */
    void release() {if(session!=0){releaseFeature(session);context.allocated-=reservedBytes;reservedBytes=0;feature=false;if(!failed)status="DLSS waiting for recreation";}}
    @Override public void close(){if(session!=0){shutdown(session);context.allocated-=reservedBytes;reservedBytes=0;session=0;feature=false;}}

    static float jitter(long frame,int base) {
        float value=0,weight=1;long index=Math.floorMod(frame,32)+1;
        while(index>0){weight/=base;value+=(index%base)*weight;index/=base;}
        return value-.5f;
    }

    private static native int abiVersion();
    private static native String[] extensions(long instance,long physical,String directory);
    private static native long initialize(long instance,long physical,long device,long getInstanceProcAddr,long getDeviceProcAddr,String directory,String logs);
    private static native void createFeature(long session,long command,int width,int height,int outputWidth,int outputHeight,int quality);
    private static native void evaluateFeature(long session,long command,long[] images,int width,int height,int outputWidth,int outputHeight,float jitterX,float jitterY,boolean reset,float frameMillis);
    private static native void releaseFeature(long session);
    private static native long allocatedBytes(long session);
    private static native void shutdown(long session);
}
