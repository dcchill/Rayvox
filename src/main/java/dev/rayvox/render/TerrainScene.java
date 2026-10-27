package dev.rayvox.render;

import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

/** Snapshots are made on the client thread; vanilla section tessellation runs on one bounded worker. */
public final class TerrainScene implements AutoCloseable {
    private final long world;
    private final java.util.function.BiFunction<net.minecraft.world.level.block.state.BlockState,Integer,dev.rayvox.LightDefinition> lightResolver;
    private final SceneQueue completed=new SceneQueue(256);
    private final Map<Long,Long> generations=new HashMap<>();
    private final Map<Long,Long> dirty=new HashMap<>();
    private final Set<Long> resident=new HashSet<>(),inFlight=new HashSet<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"Rayvox Vulkan mesher");thread.setDaemon(true);return thread;});
    private final ConcurrentLinkedQueue<Long> finished=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> retry=new ConcurrentLinkedQueue<>();
    private final Set<Scene.Key> removed=new HashSet<>();
    private final ConcurrentLinkedQueue<Throwable> failures=new ConcurrentLinkedQueue<>();
    private SectionBufferBuilderPack buffers;
    private long revision;
    private int scanX,scanZ,lastX=Integer.MIN_VALUE,lastZ=Integer.MIN_VALUE,lastRadius;
    private boolean closed;
    public TerrainScene(long world,java.util.function.BiFunction<net.minecraft.world.level.block.state.BlockState,Integer,dev.rayvox.LightDefinition> resolver){this.world=world;this.lightResolver=resolver;completed.reset(world);}
    public void dirty(int x,int y,int z) {
        long key=SectionPos.asLong(x,y,z),generation=++revision;
        generations.put(key,generation);dirty.put(key,generation);
        completed.invalidate(new Scene.Key(world,key),generation);
    }
    public void column(int x,int z,boolean unload,ClientLevel level) {
        var chunk=unload?null:level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
        for(int y=level.getMinSection();y<level.getMaxSection();y++) {
            long key=SectionPos.asLong(x,y,z);
            if(unload){dirty.remove(key);resident.remove(key);long generation=++revision;generations.put(key,generation);completed.invalidate(new Scene.Key(world,key),generation+1);removed.add(new Scene.Key(world,key));}
            else if(chunk instanceof LevelChunk actual&&(!actual.isSectionEmpty(y)||resident.contains(key)))dirty(x,y,z);
        }
    }
    public Scene.Update update(ClientLevel level,double x,double y,double z,int radius,int texture,int drainLimit) {
        for(Long done;(done=finished.poll())!=null;)inFlight.remove(done);
        for(Long key;(key=retry.poll())!=null;)if(resident.contains(key))dirty(SectionPos.x(key),SectionPos.y(key),SectionPos.z(key));
        Throwable workerFailure=failures.poll();if(workerFailure!=null)throw new IllegalStateException("Terrain tessellation failed",workerFailure);
        int cx=SectionPos.posToSectionCoord(x),cz=SectionPos.posToSectionCoord(z);
        if(cx!=lastX||cz!=lastZ||radius!=lastRadius){scanX=-radius;scanZ=-radius;lastX=cx;lastZ=cz;lastRadius=radius;}
        // Amortize enumeration; empty sections need no region snapshot or GPU allocation.
        for(int columns=0;columns<8 && scanZ<=radius;columns++) {
            int sx=cx+scanX,sz=cz+scanZ;
            var chunk=level.getChunkSource().getChunk(sx,sz,ChunkStatus.FULL,false);
            if(chunk instanceof LevelChunk actual) {
                for(int sy=level.getMinSection();sy<level.getMaxSection();sy++) {
                    long key=SectionPos.asLong(sx,sy,sz);
                    if(!actual.isSectionEmpty(sy)&&!resident.contains(key)&&!dirty.containsKey(key)&&!inFlight.contains(key))dirty(sx,sy,sz);
                }
            }
            if(++scanX>radius){scanX=-radius;scanZ++;}
        }
        for(var iterator=resident.iterator();iterator.hasNext();) {
            long key=iterator.next();if(Math.abs(SectionPos.x(key)-cx)>radius+1||Math.abs(SectionPos.z(key)-cz)>radius+1) {
                iterator.remove();dirty.remove(key);long generation=++revision;generations.put(key,generation);completed.invalidate(new Scene.Key(world,key),generation+1);removed.add(new Scene.Key(world,key));
            }
        }
        long started=System.nanoTime();
        while(inFlight.size()<2&&completed.size()<200&&System.nanoTime()-started<2_000_000) {
            Long key=dirty.keySet().stream().filter(k->!inFlight.contains(k))
                    .min(Comparator.comparingDouble(k->{double dx=SectionPos.x(k)*16+8-x,dy=SectionPos.y(k)*16+8-y,dz=SectionPos.z(k)*16+8-z;return dx*dx+dy*dy+dz*dz-Math.min(4096,revision-dirty.get(k))*64.0;})).orElse(null);
            if(key==null)break;
            long generation=dirty.remove(key);
            if(Math.abs(SectionPos.x(key)-cx)>radius+1||Math.abs(SectionPos.z(key)-cz)>radius+1)continue;
            var section=SectionPos.of(key);
            if(!level.hasChunk(section.x(),section.z()))continue;
            var region=new RenderRegionCache().createRegion(level,section);
            resident.add(key);
            if(region==null){completed.offer(new Scene.Mesh(new Scene.Key(world,key),generation,ByteBuffer.allocateDirect(0),texture,section.minBlockX(),section.minBlockY(),section.minBlockZ(),false));continue;}
            inFlight.add(key);
            // LightColors samples sprites and is client-thread confined. Freeze its
            // resolved results before handing the section snapshot to the worker.
            var definitions=new IdentityHashMap<net.minecraft.world.level.block.state.BlockState,dev.rayvox.LightDefinition>();
            for(var position:BlockPos.betweenClosed(section.origin(),section.origin().offset(15,15,15))) {
                var state=region.getBlockState(position);int emission=state.getLightEmission(region,position);
                if(emission>0&&!definitions.containsKey(state))definitions.put(state,lightResolver.apply(state,emission));
            }
            var mc=Minecraft.getInstance();
            var compiler=new SectionCompiler(mc.getBlockRenderer(),mc.getBlockEntityRenderDispatcher());
            worker.execute(()-> {
                try {
                    if(buffers==null)buffers=new SectionBufferBuilderPack();
                    var result=compiler.compile(section,region,VertexSorting.DISTANCE_TO_ORIGIN,buffers);
                    try {
                        var parts=new ArrayList<ByteBuffer>();int bytes=0;
                        for(var entry:result.renderedLayers.entrySet()) {
                            int material=entry.getKey()==RenderType.translucent()?2:entry.getKey()==RenderType.solid()?0:1;
                            var decoded=MeshDecoder.decode(entry.getValue(),material,null);parts.add(decoded);bytes+=decoded.remaining();
                            // Fluid tessellation does not pass through ModelBlockRenderer's
                            // emission encoding; recover material identity from the snapshot.
                            for(int triangle=0;triangle<decoded.remaining();triangle+=Scene.VERTEX_BYTES*3) {
                                int vertex=triangle;
                                float px=(decoded.getFloat(vertex)+decoded.getFloat(vertex+64)+decoded.getFloat(vertex+128))/3;
                                float py=(decoded.getFloat(vertex+4)+decoded.getFloat(vertex+68)+decoded.getFloat(vertex+132))/3;
                                float pz=(decoded.getFloat(vertex+8)+decoded.getFloat(vertex+72)+decoded.getFloat(vertex+136))/3;
                                int bx=Math.clamp((int)Math.floor(px-decoded.getFloat(vertex+16)*.01),0,15);
                                int by=Math.clamp((int)Math.floor(py-decoded.getFloat(vertex+20)*.01),0,15);
                                int bz=Math.clamp((int)Math.floor(pz-decoded.getFloat(vertex+24)*.01),0,15);
                                var blockPosition=new BlockPos(section.minBlockX()+bx,section.minBlockY()+by,section.minBlockZ()+bz);
                                var state=region.getBlockState(blockPosition);
                                String blockName=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                                boolean metallic=blockName.equals("iron_block")||blockName.equals("gold_block")||blockName.endsWith("copper")||blockName.endsWith("copper_block");
                                var definition=definitions.get(state);
                                float emission=Math.max(decoded.getFloat(vertex+28),state.getLightEmission(region,blockPosition)/15f*8);
                                int rgb=0;
                                if(definition!=null){emission=definition.contributes()?definition.intensity()*8:0;rgb=((int)(definition.red()*255)<<16)|((int)(definition.green()*255)<<8)|(int)(definition.blue()*255);}
                                for(int corner=0;corner<3;corner++) {
                                vertex=triangle+corner*Scene.VERTEX_BYTES;
                                decoded.putFloat(vertex+28,emission);if(definition!=null)decoded.putFloat(vertex+12,-(rgb+1.0f));
                                if(metallic&&emission<=0)decoded.putFloat(vertex+28,-1).putFloat(vertex+44,.2f);
                                if(material==2) {
                                    boolean water=state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                                    boolean lava=state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
                                    decoded.putFloat(vertex+40,lava?0:2).putFloat(vertex+44,water?.1f:.3f);
                                }
                                }
                            }
                        }
                        var vertices=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());for(var part:parts)vertices.put(part);vertices.flip();
                        if(!completed.offer(new Scene.Mesh(new Scene.Key(world,key),generation,vertices,texture,section.minBlockX(),section.minBlockY(),section.minBlockZ(),false))) {
                            // A full queue must retry instead of silently leaving a stale section.
                            if(completed.size()>=256)retry.add(key);
                        }
                    }finally{result.release();buffers.clearAll();}
                }catch(Throwable failure){failures.add(failure);}finally{finished.add(key);}
            });
        }
        var update=completed.drain(Math.min(4,drainLimit),8L*1048576,x,y,z);
        var deletion=List.copyOf(removed);removed.clear();
        return new Scene.Update(update.meshes(),deletion,List.of(),update.resetHistory()||!deletion.isEmpty());
    }
    public int pending(){return dirty.size()+inFlight.size()+completed.size();}
    @Override public void close() {
        if(closed)return;closed=true;
        worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Mesher failed to stop");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        if(buffers!=null)buffers.close();dirty.clear();resident.clear();generations.clear();
    }
}
