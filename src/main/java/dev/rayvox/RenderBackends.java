package dev.rayvox;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import dev.rayvox.render.*;
import dev.rayvox.render.vulkan.VulkanBackend;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Startup selection and the Minecraft-facing adapter. Renderer changes take effect after restart. */
public final class RenderBackends {
    // Promotion requires the documented complete playable/hardware acceptance matrix.
    public static final boolean PLAYABLE_ACCEPTED=false;
    private static RenderBackend backend;
    private static BackendMode selected;
    private static TerrainScene terrain;
    private static ClientLevel level;
    private static long world,generation;
    private static double cameraX,cameraY,cameraZ;
    private static boolean capturing;
    private static final Map<Scene.Key,Scene.Mesh> captured=new LinkedHashMap<>();
    private static final Map<Integer,Integer> batchCounts=new HashMap<>();
    private static Set<Scene.Key> previousDynamic=Set.of();
    private RenderBackends(){}
    public static void initialize() {
        if(backend!=null)return;
        selected=Rayvox.RENDER_BACKEND.get();
        if(selected==BackendMode.OPENGL){backend=new OpenGlBackend("Selected in settings");return;}
        if(!BackendSelection.probeVulkan(selected,PLAYABLE_ACCEPTED)){backend=new OpenGlBackend("Vulkan automatic activation awaits playable acceptance; explicit preview available in settings");return;}
        for(String mod:new String[]{"iris","oculus","embeddium","sodium","rubidium"})if(net.neoforged.fml.ModList.get().isLoaded(mod)) {
            backend=new OpenGlBackend("Unsupported custom renderer: "+mod);return;
        }
        if(Minecraft.getInstance().options.graphicsMode().get()==GraphicsStatus.FABULOUS){backend=new OpenGlBackend("Vulkan preview requires Fast or Fancy graphics");return;}
        try {
            var candidate=new VulkanBackend(Rayvox.VULKAN_MEMORY.get()*1048576L);candidate.initialize();backend=candidate;
            if(level!=null)terrain=new TerrainScene(world,(state,emission)->RayvoxEngine.lightColors().resolve(state,emission));
        }catch(RuntimeException|LinkageError failure){fallback("Vulkan initialization failed",failure);}
    }
    public static boolean active(){return backend instanceof VulkanBackend&&Rayvox.ENABLED.get();}
    public static void beginFrame(){
        initialize();capturing=active();
        if(!capturing)return;
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();cameraX=camera.x;cameraY=camera.y;cameraZ=camera.z;
        // Captures from the previous frame are rendered before current-frame GL overlays.
        var removed=new ArrayList<>(previousDynamic);removed.removeAll(captured.keySet());
        backend.update(new Scene.Update(new ArrayList<>(captured.values()),removed,List.of(),false));
        previousDynamic=Set.copyOf(captured.keySet());captured.clear();batchCounts.clear();
    }
    public static void endFrame(){capturing=false;}
    public static boolean capture(MeshData mesh,RenderType type) {
        if(!active()||!capturing)return false;
        String name=RenderTypePolicy.name(type.toString());
        if(!RenderTypePolicy.capture(name))return false;
        if(mesh.drawState().mode()!=com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS&&mesh.drawState().mode()!=com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES)return false;
        int identity=System.identityHashCode(type),ordinal=batchCounts.merge(identity,1,Integer::sum)-1;
        var key=new Scene.Key(world,((long)identity<<32)|(ordinal&0xffffffffL),1);
        int texture=RenderSystem.getShaderTexture(0);TextureSnapshots.request(texture);
        var vertices=MeshDecoder.decode(mesh,name.contains("translucent")?3:1,null);
        if(name.contains("eyes")||name.contains("energy")||name.contains("beacon"))for(int i=0;i<vertices.remaining();i+=Scene.VERTEX_BYTES)vertices.putFloat(i+28,4);
        captured.put(key,new Scene.Mesh(key,++generation,vertices,texture,cameraX,cameraY,cameraZ,true));
        mesh.close();return true;
    }
    public static void renderStage(RenderLevelStageEvent event) {
        if(!active()||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS)return;
        try {
            var mc=Minecraft.getInstance();if(mc.level==null)return;
            if(mc.getMainRenderTarget().width<1||mc.getMainRenderTarget().height<1)return;
            updateLevel(mc.level);
            var position=event.getCamera().getPosition();
            int atlas=mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();TextureSnapshots.request(atlas);
            int radius=((VulkanBackend)backend).sceneRadius(mc.options.renderDistance().get());
            var update=terrain.update(mc.level,position.x,position.y,position.z,radius,atlas,((VulkanBackend)backend).availableMeshSlots());backend.update(update);
            backend.update(new Scene.Update(List.of(),List.of(),TextureSnapshots.drain(),false));
            var vp=new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
            float partial=mc.getTimer().getGameTimeDeltaPartialTick(false);
            var sky=mc.level.getSkyColor(position,partial);
            var sun=DirectionalLight.at(mc.level.getSunAngle(partial),mc.level.getMoonBrightness(),mc.level.getRainLevel(partial),mc.level.dimensionType().hasSkyLight()&&Rayvox.SUNLIGHT.get());
            var dynamic=new ArrayList<Scene.Light>();
            if(Rayvox.DYNAMIC.get())for(var light:DynamicLights.collect(mc.level,position,partial,RayvoxEngine.lightColors())) {
                var definition=light.definition();dynamic.add(new Scene.Light(light.x(),light.y(),light.z(),LightDefinition.linear(definition.red())*definition.intensity()*20,LightDefinition.linear(definition.green())*definition.intensity()*20,LightDefinition.linear(definition.blue())*definition.intensity()*20,definition.radius()));
            }
            backend.resize(mc.getMainRenderTarget().width,mc.getMainRenderTarget().height);
            mc.getMainRenderTarget().bindWrite(true);
            float[] fog=RenderSystem.getShaderFogColor();
            backend.render(new Scene.Camera(new Matrix4f(vp).invert(),vp,position.x,position.y,position.z,
                    new Vector3f(sun.x(),sun.y(),sun.z()),new Vector3f(sun.red(),sun.green(),sun.blue()).mul(sun.strength()*4),new Vector3f((float)sky.x,(float)sky.y,(float)sky.z),dynamic,world,false,
                    new Vector3f(fog[0],fog[1],fog[2]),Math.min(RenderSystem.getShaderFogStart(),Math.max(0,radius*16-32)),Math.min(RenderSystem.getShaderFogEnd(),Math.max(16,radius*16-16))));
        }catch(RuntimeException|LinkageError failure){fallback("Vulkan rendering failed",failure);}
    }
    public static void updateLevel(ClientLevel current) {
        if(current==level)return;
        if(terrain!=null){terrain.close();terrain=null;}
        if(backend instanceof VulkanBackend vulkan)vulkan.clearScene();
        level=current;world++;captured.clear();previousDynamic=Set.of();
        if(current!=null&&backend instanceof VulkanBackend)terrain=new TerrainScene(world,(state,emission)->RayvoxEngine.lightColors().resolve(state,emission));
    }
    public static void blockChanged(ClientLevel changed,int x,int y,int z){
        if(changed!=level||terrain==null)return;
        for(int sx=Math.floorDiv(x-1,16);sx<=Math.floorDiv(x+1,16);sx++)
            for(int sy=Math.floorDiv(y-1,16);sy<=Math.floorDiv(y+1,16);sy++)
                for(int sz=Math.floorDiv(z-1,16);sz<=Math.floorDiv(z+1,16);sz++)terrain.dirty(sx,sy,sz);
    }
    public static void columnChanged(ClientLevel changed,int x,int z,boolean unload){if(changed==level&&terrain!=null)terrain.column(x,z,unload,changed);}
    public static void reload(){if(terrain!=null){terrain.close();terrain=null;}if(backend instanceof VulkanBackend vulkan){vulkan.clearScene();vulkan.clearTextures();}TextureSnapshots.clear();captured.clear();previousDynamic=Set.of();level=null;}
    public static String status(){return backend==null?"Renderer: waiting for startup":backend.diagnostics().summary()+(selected!=Rayvox.RENDER_BACKEND.get()?"; restart required":"")+(terrain==null?"":"; "+terrain.pending()+" section jobs");}
    public static RenderBackend.Diagnostics diagnostics(){return backend==null?null:backend.diagnostics();}
    public static int pendingSections(){return terrain==null?0:terrain.pending();}
    public static VulkanBackend.Timings timings(){return backend instanceof VulkanBackend vulkan?vulkan.timings():null;}
    public static int dynamicMeshCount(){return backend instanceof VulkanBackend vulkan?vulkan.dynamicMeshCount():0;}
    private static void fallback(String reason,Throwable failure) {
        com.mojang.logging.LogUtils.getLogger().error(reason,failure);
        try{if(terrain!=null)terrain.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}finally{terrain=null;}
        try{if(backend!=null)backend.close();}catch(RuntimeException cleanup){com.mojang.logging.LogUtils.getLogger().error("Vulkan cleanup failed",cleanup);}
        backend=new OpenGlBackend(reason+": "+failure.getMessage());capturing=false;captured.clear();TextureSnapshots.clear();
    }
    public static void close(){if(terrain!=null){terrain.close();terrain=null;}if(backend!=null){backend.close();backend=null;}captured.clear();previousDynamic=Set.of();TextureSnapshots.clear();level=null;}
}
