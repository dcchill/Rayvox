package dev.rayvox.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.*;
import dev.rayvox.RenderBackends;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.renderer.ShaderInstance;

/** Minecraft-facing capture; native rendering remains available for every declined draw. */
public final class RasterHooks {
    private static final Map<VertexBuffer,RasterMesh> meshes=new IdentityHashMap<>();
    private static long generation,bytes;
    private static ShaderInstance applied;
    private RasterHooks(){}
    public static void upload(VertexBuffer buffer,MeshData source) {
        if(RenderBackends.raster()==null)return;
        removeSnapshot(buffer);
        long size=source.vertexBuffer().remaining()+(source.indexBuffer()==null?0:source.indexBuffer().remaining());
        if(bytes+size>512L*1048576)return;
        var mesh=RasterMesh.copy(source,++generation);meshes.put(buffer,mesh);bytes+=mesh.bytes();
    }
    public static void indices(VertexBuffer buffer,ByteBuffer source) {
        var old=meshes.get(buffer);if(old==null)return;
        var updated=old.withIndices(source,++generation);bytes+=updated.bytes()-old.bytes();meshes.put(buffer,updated);
    }
    public static void applied(ShaderInstance shader){applied=shader;}
    public static boolean draw(VertexBuffer buffer,boolean persistent) {
        var raster=RenderBackends.raster();
        if(raster==null||!RenderBackends.renderingWorld())return false;
        try{return raster.draw(buffer,meshes.get(buffer),applied,persistent);}
        catch(RuntimeException|LinkageError failure){RenderBackends.failed("Vulkan vanilla draw failed",failure);return false;}
    }
    public static void nativeBoundary() {
        var raster=RenderBackends.raster();if(raster==null)return;
        try{raster.flush();}catch(RuntimeException|LinkageError failure){RenderBackends.failed("Vulkan vanilla submission failed",failure);}
    }
    public static void samplerChanged(){var raster=RenderBackends.raster();if(raster!=null)raster.samplerChanged();}
    public static void targetChanged(RenderTarget target){var raster=RenderBackends.raster();if(raster!=null)raster.beforeTargetChange(target);}
    public static void targetBound(RenderTarget target){var raster=RenderBackends.raster();if(raster!=null)raster.boundTarget(target);}
    public static boolean copyDepth(RenderTarget destination,RenderTarget source){var raster=RenderBackends.raster();return raster!=null&&raster.copyDepth(destination,source);}
    private static void removeSnapshot(VertexBuffer buffer){var old=meshes.remove(buffer);if(old!=null)bytes-=old.bytes();}
    public static void close(VertexBuffer buffer){removeSnapshot(buffer);var raster=RenderBackends.raster();if(raster!=null)raster.remove(buffer);}
    public static void clear(){meshes.clear();bytes=0;applied=null;}
}
