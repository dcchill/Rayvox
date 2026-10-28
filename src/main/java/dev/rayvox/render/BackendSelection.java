package dev.rayvox.render;

/** Pure startup policy, independent of Minecraft and native driver loading. */
public final class BackendSelection {
    private BackendSelection(){}
    public static boolean probeVulkan(BackendMode requested,boolean playableAccepted) {
        return requested==BackendMode.VULKAN_PATH_TRACING||raster(requested)||(requested==BackendMode.AUTO&&playableAccepted);
    }
    public static boolean raster(BackendMode mode){return mode==BackendMode.VULKAN_VANILLA||mode==BackendMode.VULKAN_SHADER;}
    public static boolean realisticLighting(BackendMode mode,boolean enabled){return enabled&&!raster(mode);}
}
