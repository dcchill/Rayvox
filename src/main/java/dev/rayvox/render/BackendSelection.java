package dev.rayvox.render;

/** Pure startup policy, independent of Minecraft and native driver loading. */
public final class BackendSelection {
    private BackendSelection(){}
    public static boolean probeVulkan(BackendMode requested,boolean playableAccepted) {
        return requested==BackendMode.VULKAN_PATH_TRACING||(requested==BackendMode.AUTO&&playableAccepted);
    }
}
