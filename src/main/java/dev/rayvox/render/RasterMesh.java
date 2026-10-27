package dev.rayvox.render;

import com.mojang.blaze3d.vertex.*;
import java.nio.*;

/** Owned native vertices and sorted indices; no expansion or lighting conversion. */
public record RasterMesh(MeshData.DrawState state, ByteBuffer vertices, ByteBuffer indices, long generation) {
    public static RasterMesh copy(MeshData mesh,long generation) {
        return new RasterMesh(mesh.drawState(),copy(mesh.vertexBuffer()),mesh.indexBuffer()==null?null:copy(mesh.indexBuffer()),generation);
    }
    public RasterMesh withIndices(ByteBuffer source,long generation) {
        return new RasterMesh(state,vertices,copy(source),generation);
    }
    public static ByteBuffer copy(ByteBuffer source) {
        var owned=ByteBuffer.allocateDirect(source.remaining()).order(ByteOrder.nativeOrder());
        owned.put(source.duplicate()).flip();return owned.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
    public ByteBuffer triangleIndices() {
        if(indices!=null)return indices;
        var result=ByteBuffer.allocateDirect(state.indexCount()*4).order(ByteOrder.nativeOrder());
        if(state.mode()==VertexFormat.Mode.QUADS) {
            for(int vertex=0;vertex<state.vertexCount();vertex+=4)
                for(int offset:new int[]{0,1,2,2,3,0})result.putInt(vertex+offset);
        } else for(int i=0;i<state.indexCount();i++)result.putInt(i);
        return result.flip();
    }
    public boolean supported(){return state.mode()==VertexFormat.Mode.QUADS||state.mode()==VertexFormat.Mode.TRIANGLES;}
    public long bytes(){return vertices.remaining()+(indices==null?0:indices.remaining());}
}
