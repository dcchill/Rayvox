package dev.rayvox.render;

import com.mojang.blaze3d.vertex.*;
import java.nio.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Copies vanilla buffers before their owners close/reuse them. No GPU readback. */
public final class MeshDecoder {
    private MeshDecoder() {}
    public static ByteBuffer decode(MeshData mesh,int material,Matrix4f transform) {
        var state=mesh.drawState();var format=state.format();
        int position=format.getOffset(VertexFormatElement.POSITION),normal=format.getOffset(VertexFormatElement.NORMAL),
                color=format.getOffset(VertexFormatElement.COLOR),uv=format.getOffset(VertexFormatElement.UV0),light=format.getOffset(VertexFormatElement.UV2);
        if(position<0)return ByteBuffer.allocateDirect(0);
        int[] indices;
        if(state.mode()==VertexFormat.Mode.QUADS) {
            indices=new int[state.vertexCount()/4*6];int out=0;
            for(int i=0;i+3<state.vertexCount();i+=4)for(int offset:new int[]{0,1,2,2,3,0})indices[out++]=i+offset;
        } else if(state.mode()==VertexFormat.Mode.TRIANGLES) {
            indices=new int[state.vertexCount()];for(int i=0;i<indices.length;i++)indices[i]=i;
        } else return ByteBuffer.allocateDirect(0);
        var source=mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        var target=ByteBuffer.allocateDirect(indices.length*Scene.VERTEX_BYTES).order(ByteOrder.nativeOrder());
        var p=new Vector4f();var n=new Vector3f();
        for(int index:indices) {
            int start=index*format.getVertexSize();
            p.set(source.getFloat(start+position),source.getFloat(start+position+4),source.getFloat(start+position+8),1);
            if(transform!=null)transform.transform(p);
            target.putFloat(p.x).putFloat(p.y).putFloat(p.z).putFloat(1);
            if(normal>=0)n.set(source.get(start+normal)/127f,source.get(start+normal+1)/127f,source.get(start+normal+2)/127f);
            else n.set(0,1,0);
            if(transform!=null)transform.transformDirection(n);if(n.lengthSquared()<.001)n.set(0,1,0);else n.normalize();
            float emission=light<0?0:(source.getInt(start+light)&15)/15f*8;
            target.putFloat(n.x).putFloat(n.y).putFloat(n.z).putFloat(emission);
            target.putFloat(uv<0?0:source.getFloat(start+uv)).putFloat(uv<0?0:source.getFloat(start+uv+4)).putFloat(material).putFloat(material==2?.1f:.8f);
            for(int c=0;c<4;c++)target.putFloat(color<0?1:(source.get(start+color+c)&255)/255f);
        }
        // Some vanilla layers have no stored normals (particles, text); derive face normals.
        if(normal<0)for(int offset=0;offset<target.position();offset+=Scene.VERTEX_BYTES*3) {
            var a=new Vector3f(target.getFloat(offset),target.getFloat(offset+4),target.getFloat(offset+8));
            var b=new Vector3f(target.getFloat(offset+64),target.getFloat(offset+68),target.getFloat(offset+72));
            var c=new Vector3f(target.getFloat(offset+128),target.getFloat(offset+132),target.getFloat(offset+136));
            b.sub(a).cross(c.sub(a));if(b.lengthSquared()<.000001)b.set(0,1,0);else b.normalize();
            for(int vertex=0;vertex<3;vertex++){int start=offset+vertex*64+16;target.putFloat(start,b.x).putFloat(start+4,b.y).putFloat(start+8,b.z);}
        }
        return target.flip();
    }
}
