package dev.rayvox.render;

import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Owned CPU snapshots. Vertices: position/encoded emission color, normal/emission,
 * UV/material/roughness, RGBA tint; four vec4s per expanded triangle vertex. */
public final class Scene {
    public static final int VERTEX_BYTES = 64;
    private Scene() {}
    public record Key(long world, long object, int kind) {
        public Key(long world,long object) {this(world,object,0);}
    }
    public record Mesh(Key key, long generation, ByteBuffer vertices, int texture,
                       double x, double y, double z, boolean dynamic) {
        public Mesh {
            if (!vertices.isDirect() || vertices.remaining() % (3 * VERTEX_BYTES) != 0)
                throw new IllegalArgumentException("Expected owned direct triangle vertices");
            vertices = vertices.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder());
        }
        public int triangles() { return vertices.remaining() / (3 * VERTEX_BYTES); }
    }
    public record Texture(int id, long generation, int width, int height, ByteBuffer rgba,
                          int x,int y,int fullWidth,int fullHeight,int mipLevel) {
        public Texture(int id,long generation,int width,int height,ByteBuffer rgba) {this(id,generation,width,height,rgba,0,0,width,height,0);}
        public Texture(int id,long generation,int width,int height,ByteBuffer rgba,int x,int y,int fullWidth,int fullHeight) {this(id,generation,width,height,rgba,x,y,fullWidth,fullHeight,0);}
    }
    public record Light(double x, double y, double z, float red, float green, float blue, float radius) {}
    public record Update(List<Mesh> meshes, List<Key> removed, List<Texture> textures, boolean resetHistory) {
        public Update { meshes = List.copyOf(meshes); removed = List.copyOf(removed); textures = List.copyOf(textures); }
        public static Update empty() { return new Update(List.of(), List.of(), List.of(), false); }
    }
    public record Camera(Matrix4f inverseViewProjection, Matrix4f viewProjection,
                         double x, double y, double z, Vector3f sunDirection, Vector3f sunRadiance,
                         Vector3f sky, List<Light> lights, long world, boolean cut,Vector3f fogColor,float fogStart,float fogEnd) {
        public Camera(Matrix4f inverseVP,Matrix4f vp,double x,double y,double z,Vector3f sunDirection,Vector3f sunRadiance,Vector3f sky,List<Light> lights,long world,boolean cut) {
            this(inverseVP,vp,x,y,z,sunDirection,sunRadiance,sky,lights,world,cut,new Vector3f(),8192,8193);
        }
        public Camera {
            inverseViewProjection = new Matrix4f(inverseViewProjection);
            viewProjection = new Matrix4f(viewProjection);
            sunDirection = new Vector3f(sunDirection); sunRadiance = new Vector3f(sunRadiance);
            sky = new Vector3f(sky); lights = List.copyOf(lights);
            fogColor=new Vector3f(fogColor);
        }
    }
}
