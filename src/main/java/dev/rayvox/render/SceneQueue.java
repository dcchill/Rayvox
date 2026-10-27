package dev.rayvox.render;

import java.util.*;

/** Bounded, coalescing hand-off. A newer tombstone can never resurrect an older mesh. */
public final class SceneQueue {
    private record Entry(long generation, Scene.Mesh mesh, long order) {}
    private final Map<Scene.Key, Entry> pending = new HashMap<>();
    private final Map<Scene.Key, Long> versions = new HashMap<>();
    private final int capacity;
    private long world, order;
    public SceneQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public synchronized void reset(long epoch) { pending.clear(); versions.clear(); world = epoch; }
    public synchronized boolean offer(Scene.Mesh mesh) { return put(mesh.key(), mesh.generation(), mesh); }
    public synchronized void invalidate(Scene.Key key,long generation) {
        if(key.world()!=world)return;
        versions.merge(key,generation-1,Math::max);
        var entry=pending.get(key);if(entry!=null&&entry.generation()<generation)pending.remove(key);
    }
    public synchronized boolean remove(Scene.Key key, long generation) { return put(key, generation, null); }
    private boolean put(Scene.Key key, long generation, Scene.Mesh mesh) {
        if (key.world() != world || generation <= versions.getOrDefault(key, -1L)) return false;
        if (!pending.containsKey(key) && pending.size() >= capacity) return false;
        versions.put(key, generation);
        pending.put(key, new Entry(generation, mesh, order++));
        return true;
    }
    public synchronized Scene.Update drain(int maximum, long byteBudget, double x, double y, double z) {
        var entries = new ArrayList<>(pending.entrySet());
        entries.sort(Comparator.comparingDouble(e -> {
            Scene.Mesh m = e.getValue().mesh();
            if (m == null) return -Double.MAX_VALUE;
            double dx=m.x()-x, dy=m.y()-y, dz=m.z()-z;
            return dx*dx+dy*dy+dz*dz - Math.min(4096, order-e.getValue().order())*256.0;
        }));
        var meshes = new ArrayList<Scene.Mesh>(); var removed = new ArrayList<Scene.Key>();
        long bytes=0; int count=0;
        for (var entry : entries) {
            if (count >= maximum) break;
            var mesh=entry.getValue().mesh();
            if (mesh != null && bytes + mesh.vertices().remaining() > byteBudget && count > 0) continue;
            pending.remove(entry.getKey()); count++;
            if (mesh == null) removed.add(entry.getKey());
            else { meshes.add(mesh); bytes += mesh.vertices().remaining(); }
        }
        return new Scene.Update(meshes, removed, List.of(), count != 0);
    }
    public synchronized int size() { return pending.size(); }
}
