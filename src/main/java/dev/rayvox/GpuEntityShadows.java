package dev.rayvox;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/** One upload per frame, shared by every terrain and lighting draw. */
final class GpuEntityShadows implements AutoCloseable {
    static final int ROWS = EntityShadows.MAX_CASTERS * (PlayerShadowCapture.MAX_PARTS + 1);
    final int texture = glGenTextures();
    private final java.nio.FloatBuffer data = BufferUtils.createFloatBuffer(ROWS * 12);
    int count, parts;

    GpuEntityShadows() {
        int old = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,3,ROWS,0,GL_RGBA,GL_FLOAT,(java.nio.ByteBuffer)null);
        glBindTexture(GL_TEXTURE_2D, old);
    }

    void update(List<EntityShadows.Caster> casters, Vec3 camera) {
        data.clear(); count = Math.min(EntityShadows.MAX_CASTERS, casters.size()); parts = 0;
        var bounds = casters.stream().map(EntityShadows.Caster::bounds).toList();
        for (int i=0;i<count;i++) {
            var caster=casters.get(i);var box=caster.bounds();var center=box.getCenter().subtract(camera);
            int offset=i*12;
            data.put(offset,(float)center.x).put(offset+1,(float)center.y).put(offset+2,(float)center.z)
                    .put(offset+3,EntityShadows.weight(box,bounds,camera));
            data.put(offset+4,(float)Math.max(box.getXsize()*0.5,0.025))
                    .put(offset+5,(float)Math.max(box.getYsize()*0.5,0.025))
                    .put(offset+6,(float)Math.max(box.getZsize()*0.5,0.025)).put(offset+7,EntityShadows.MAX_CASTERS+parts);
            data.put(offset+8,caster.parts().size()).put(offset+9,0).put(offset+10,0).put(offset+11,0);
            for(var part:caster.parts()) {
                var m=part.inverse();int row=(EntityShadows.MAX_CASTERS+parts++)*12;
                data.put(row,m.m00()).put(row+1,m.m10()).put(row+2,m.m20()).put(row+3,m.m30());
                data.put(row+4,m.m01()).put(row+5,m.m11()).put(row+6,m.m21()).put(row+7,m.m31());
                data.put(row+8,m.m02()).put(row+9,m.m12()).put(row+10,m.m22()).put(row+11,m.m32());
            }
        }
        if(count==0)return;
        data.limit((EntityShadows.MAX_CASTERS+parts)*12);
        int old=glGetInteger(GL_TEXTURE_BINDING_2D),unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int row=glGetInteger(GL_UNPACK_ROW_LENGTH),x=glGetInteger(GL_UNPACK_SKIP_PIXELS),y=glGetInteger(GL_UNPACK_SKIP_ROWS);
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);
            glBindTexture(GL_TEXTURE_2D,texture);
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,3,EntityShadows.MAX_CASTERS+parts,GL_RGBA,GL_FLOAT,data);
        } finally {
            glBindTexture(GL_TEXTURE_2D,old);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);
            glPixelStorei(GL_UNPACK_ROW_LENGTH,row);glPixelStorei(GL_UNPACK_SKIP_PIXELS,x);glPixelStorei(GL_UNPACK_SKIP_ROWS,y);
        }
    }

    public void close(){glDeleteTextures(texture);}
}
