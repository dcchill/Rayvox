package dev.rayvox.render;

import java.nio.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Lazily seeds each used texture once, then copies only CPU-uploaded animation rectangles. */
public final class TextureSnapshots {
    private static final class Shadow {
        final int width,height;final ByteBuffer pixels;
        int x0,y0,x1,y1;boolean first=true;long generation;
        Shadow(int width,int height,ByteBuffer pixels){this.width=width;this.height=height;this.pixels=pixels;x0=0;y0=0;x1=width;y1=height;}
    }
    private static final Map<Integer,Shadow> textures=new LinkedHashMap<>();
    private static final Set<Integer> removed=new LinkedHashSet<>();
    private static final int[] bindings=new int[32];
    private static long revision,bytes;
    private TextureSnapshots(){}
    public static boolean interested(){return !textures.isEmpty();}
    public static void bound(int texture){int unit=com.mojang.blaze3d.platform.GlStateManager._getActiveTexture()-GL_TEXTURE0;if(unit>=0&&unit<bindings.length)bindings[unit]=texture;}
    public static int bound(){int unit=com.mojang.blaze3d.platform.GlStateManager._getActiveTexture()-GL_TEXTURE0;return unit>=0&&unit<bindings.length?bindings[unit]:0;}
    public static void request(int id) {
        if(id==0||textures.containsKey(id))return;
        int old=glGetInteger(GL_TEXTURE_BINDING_2D),pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int row=glGetInteger(GL_PACK_ROW_LENGTH),skipX=glGetInteger(GL_PACK_SKIP_PIXELS),skipY=glGetInteger(GL_PACK_SKIP_ROWS),alignment=glGetInteger(GL_PACK_ALIGNMENT);
        try {
            glBindTexture(GL_TEXTURE_2D,id);int width=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH),height=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT);
            if(width<1||height<1)return;
            long size=(long)width*height*4;if(size>Integer.MAX_VALUE||bytes+size>512L*1048576)throw new IllegalStateException("Texture snapshot memory budget exceeded");
            var pixels=ByteBuffer.allocateDirect((int)size);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_ALIGNMENT,1);
            glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            textures.put(id,new Shadow(width,height,pixels));bytes+=size;
        }finally{
            glPixelStorei(GL_PACK_ROW_LENGTH,row);glPixelStorei(GL_PACK_SKIP_PIXELS,skipX);glPixelStorei(GL_PACK_SKIP_ROWS,skipY);glPixelStorei(GL_PACK_ALIGNMENT,alignment);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glBindTexture(GL_TEXTURE_2D,old);
        }
    }
    public static void upload(int id,int level,int x,int y,int sourceX,int sourceY,int width,int height,int sourceWidth,int components,long pointer) {
        var shadow=textures.get(id);if(shadow==null||level!=0||components!=4||pointer==0)return;
        if(x<0||y<0||x+width>shadow.width||y+height>shadow.height)return;
        long destination=MemoryUtil.memAddress(shadow.pixels);
        for(int row=0;row<height;row++)MemoryUtil.memCopy(pointer+((long)(sourceY+row)*sourceWidth+sourceX)*4,destination+((long)(y+row)*shadow.width+x)*4,(long)width*4);
        shadow.x0=Math.min(shadow.x0,x);shadow.y0=Math.min(shadow.y0,y);shadow.x1=Math.max(shadow.x1,x+width);shadow.y1=Math.max(shadow.y1,y+height);shadow.generation=++revision;
    }
    public static List<Scene.Texture> drain() {
        var updates=new ArrayList<Scene.Texture>();
        for(int id:removed)updates.add(new Scene.Texture(id,++revision,0,0,ByteBuffer.allocateDirect(0)));removed.clear();
        for(var entry:textures.entrySet()) {
            var shadow=entry.getValue();if(shadow.x0>=shadow.x1||shadow.y0>=shadow.y1)continue;
            int width=shadow.x1-shadow.x0,height=shadow.y1-shadow.y0;
            var pixels=ByteBuffer.allocateDirect(width*height*4);
            for(int row=shadow.y0;row<shadow.y1;row++) {
                var source=shadow.pixels.duplicate();source.position((row*shadow.width+shadow.x0)*4).limit((row*shadow.width+shadow.x1)*4);pixels.put(source);
            }
            pixels.flip();updates.add(new Scene.Texture(entry.getKey(),++revision,width,height,pixels,shadow.x0,shadow.y0,shadow.width,shadow.height));
            shadow.first=false;shadow.x0=shadow.width;shadow.y0=shadow.height;shadow.x1=shadow.y1=0;
        }
        return updates;
    }
    public static void deleted(int id){var old=textures.remove(id);if(old!=null){bytes-=(long)old.width*old.height*4;removed.add(id);}}
    public static void clear(){textures.clear();removed.clear();bytes=0;}
}
