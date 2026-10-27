package dev.rayvox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Single-owner-thread probe-brick residency and publication; independent of screen history and GL. */
public final class WorldLightingCache implements AutoCloseable {
    public enum Channel {
        STATIC_DIRECT(3), DYNAMIC(3), DIRECTIONAL_VISIBILITY(1), INDIRECT(18);

        final int components;
        Channel(int components) { this.components = components; }
    }

    public record Region(int x, int y, int z) {
        public static Region atBlock(int x, int y, int z) {
            return new Region(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        }

        double distanceSquared(double x, double y, double z) {
            double dx = this.x * 16.0 + 8 - x, dy = this.y * 16.0 + 8 - y, dz = this.z * 16.0 + 8 - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Owner and residency generation prevent acceptance after eviction, clear, or world replacement. */
    public record Ticket(WorldLightingCache owner, Region region, Channel channel, long generation, long version) {}

    private static final class Value {
        long requested = 1, completed;
        float[] samples;
        boolean gpuPublished;
    }

    private static final class Brick {
        final long generation;
        final EnumMap<Channel, Value> values = new EnumMap<>(Channel.class);
        Brick(long generation) {
            this.generation = generation;
            for (var channel : Channel.values()) values.put(channel, new Value());
        }
    }

    private final LinkedHashMap<Region, Brick> bricks = new LinkedHashMap<>();
    private final int capacity, probes;
    private final long bytesPerBrick;
    private long generation, revision, evictions;
    private boolean closed;

    /** Budget reserves every channel's float payload; JVM metadata is separately bounded by maxBricks. */
    public WorldLightingCache(int maxBricks, long payloadBudgetBytes, int probeSpacing) {
        if (maxBricks < 1 || payloadBudgetBytes < 0 || probeSpacing < 1 || probeSpacing > 16 || 16 % probeSpacing != 0)
            throw new IllegalArgumentException("Invalid probe-brick limits");
        int side = 16 / probeSpacing;
        probes = side * side * side;
        int components = 0;
        for (var channel : Channel.values()) components += channel.components;
        bytesPerBrick = (long)probes * components * Float.BYTES;
        capacity = (int)Math.min(maxBricks, payloadBudgetBytes / bytesPerBrick);
        if (capacity == 0) throw new IllegalArgumentException("Budget cannot hold one probe brick");
    }

    public void touch(Region region) {
        if (closed) throw new IllegalStateException("World lighting cache is closed");
        if (region == null) throw new NullPointerException("region");
        var existing = bricks.remove(region);
        if (existing != null) { bricks.put(region, existing); return; }
        if (bricks.size() == capacity) {
            bricks.remove(bricks.keySet().iterator().next());
            evictions++;
        }
        bricks.put(region, new Brick(++generation));
        revision++;
    }

    public void invalidate(Region region, Channel... channels) {
        var brick = peek(region);
        if (brick == null || channels.length == 0) return;
        for (var channel : channels) brick.values.get(channel).requested++;
        revision++;
    }

    public void invalidateAll(Channel... channels) {
        for (var region : regions()) invalidate(region, channels);
    }

    public Ticket ticket(Region region, Channel channel) {
        var brick = peek(region);
        return brick == null ? null : new Ticket(this, region, channel, brick.generation, brick.values.get(channel).requested);
    }

    public boolean isCurrent(Ticket ticket) {
        if (ticket == null || ticket.owner != this || closed) return false;
        var brick = peek(ticket.region);
        return brick != null && brick.generation == ticket.generation
                && brick.values.get(ticket.channel).requested == ticket.version;
    }

    public boolean valid(Region region, Channel channel) {
        var brick = peek(region);
        if (brick == null) return false;
        var value = brick.values.get(channel);
        return (value.samples != null || value.gpuPublished) && value.completed == value.requested;
    }

    /** A producer must supply a complete brick. Dequeueing work never makes it valid. */
    public boolean publish(Ticket ticket, float[] samples) {
        if (!isCurrent(ticket) || valid(ticket.region, ticket.channel)) return false;
        if (samples.length != sampleCount(ticket.channel)) throw new IllegalArgumentException("Wrong probe payload size");
        for (float sample : samples) {
            if (!Float.isFinite(sample) || sample < 0 || ticket.channel == Channel.DIRECTIONAL_VISIBILITY && sample > 1)
                throw new IllegalArgumentException("Invalid probe sample");
        }
        var value = peek(ticket.region).values.get(ticket.channel);
        value.samples = samples.clone();
        value.gpuPublished = false;
        value.completed = ticket.version;
        revision++;
        return true;
    }

    /** Publish GPU-owned storage after a complete brick draw, ordered before consumers on the same GL context. */
    public boolean publishGpu(Ticket ticket) {
        if (!isCurrent(ticket) || valid(ticket.region, ticket.channel)) return false;
        var value = peek(ticket.region).values.get(ticket.channel);
        value.samples = null;
        value.gpuPublished = true;
        value.completed = ticket.version;
        revision++;
        return true;
    }

    /** No mutable or stale payload escapes. GPU-owned samples have no CPU copy and return null. */
    public float[] copySamples(Region region, Channel channel) {
        if (!valid(region, channel)) return null;
        var samples = bricks.get(region).values.get(channel).samples;
        return samples == null ? null : samples.clone();
    }

    public List<Ticket> dirtyTickets() {
        var result = new ArrayList<Ticket>();
        for (var entry : bricks.entrySet()) for (var channel : Channel.values()) {
            var value = entry.getValue().values.get(channel);
            if (value.completed != value.requested)
                result.add(new Ticket(this, entry.getKey(), channel, entry.getValue().generation, value.requested));
        }
        return List.copyOf(result);
    }

    // Inspection and scheduling must not change LRU order.
    private Brick peek(Region region) {
        return bricks.get(region);
    }

    public List<Region> regions() { return List.copyOf(bricks.keySet()); }
    public int size() { return bricks.size(); }
    public int capacity() { return capacity; }
    public int sampleCount(Channel channel) { return probes * channel.components; }
    public long reservedPayloadBytes() { return bytesPerBrick * bricks.size(); }
    public long residentPayloadBytes() {
        long bytes = 0;
        for (var brick : bricks.values()) for (var value : brick.values.values())
            if (value.samples != null) bytes += (long)value.samples.length * Float.BYTES;
        return bytes;
    }
    public long revision() { return revision; }
    public int validCount(Channel channel) {
        int count = 0;
        for (var region : bricks.keySet()) if (valid(region, channel)) count++;
        return count;
    }
    public long evictions() { return evictions; }

    public void clear() { bricks.clear(); revision++; }

    @Override public void close() { clear(); closed = true; }
}
