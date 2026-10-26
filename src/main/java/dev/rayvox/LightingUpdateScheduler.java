package dev.rayvox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Single-owner-thread bounded queue. Delayed GPU feedback is never inferred from CPU extraction. */
public final class LightingUpdateScheduler implements AutoCloseable {
    private record Key(WorldLightingCache.Region region, WorldLightingCache.Channel channel) {}
    private static final class Pending {
        WorldLightingCache.Ticket ticket;
        final long since;
        long lastDispatch;
        boolean inFlight;
        Pending(WorldLightingCache.Ticket ticket, long since) { this.ticket = ticket; this.since = since; }
    }

    public record Work(WorldLightingCache.Region region, List<WorldLightingCache.Ticket> tickets) {
        public Work { tickets = List.copyOf(tickets); }
    }

    private final WorldLightingCache cache;
    private final LinkedHashMap<Key, Pending> pending = new LinkedHashMap<>();
    private long frame, dispatchSerial, synchronizedRevision = -1;
    private double gpuNanosPerRegion;
    private boolean closed;
    private static final long MAX_WAIT_FRAMES = 120;

    public LightingUpdateScheduler(WorldLightingCache cache) { this.cache = cache; }

    public void synchronize() {
        if (closed || synchronizedRevision == cache.revision()) return;
        var dirty = cache.dirtyTickets();
        var next = new LinkedHashMap<Key, Pending>();
        for (var ticket : dirty) {
            var key = new Key(ticket.region(), ticket.channel());
            var previous = pending.get(key);
            if (previous == null || previous.ticket.generation() != ticket.generation()) previous = new Pending(ticket, frame);
            else if (!previous.ticket.equals(ticket)) { previous.ticket = ticket; previous.inFlight = false; }
            next.put(key, previous);
        }
        pending.clear();
        pending.putAll(next);
        synchronizedRevision = cache.revision();
    }

    public void advanceFrame() { synchronize(); frame++; }

    /** Unknown cost bootstraps one region; zero budget explicitly disables dispatch. */
    public List<Work> schedule(double cameraX, double cameraY, double cameraZ, int maxRegions, long gpuBudgetNanos,
            Predicate<WorldLightingCache.Region> visible, ToDoubleFunction<WorldLightingCache.Region> importance) {
        if (maxRegions < 0 || gpuBudgetNanos < 0) throw new IllegalArgumentException("Negative lighting budget");
        synchronize();
        if (closed || maxRegions == 0 || gpuBudgetNanos == 0) return List.of();
        int limit = gpuNanosPerRegion == 0 ? 1
                : (int)Math.min(maxRegions, Math.max(1, Math.floor(gpuBudgetNanos / gpuNanosPerRegion)));
        limit = Math.min(limit, maxRegions);
        var regions = new LinkedHashMap<WorldLightingCache.Region, List<Pending>>();
        for (var item : pending.values()) if (!item.inFlight)
            regions.computeIfAbsent(item.ticket.region(), ignored -> new ArrayList<>()).add(item);
        var ranked = new ArrayList<>(regions.keySet());
        // Visibility and importance are bounded hints. Overdue work outranks both, ensuring eventual service.
        var scores = new java.util.HashMap<WorldLightingCache.Region, Double>();
        for (var region : ranked) {
            double hint = importance.applyAsDouble(region);
            if (!Double.isFinite(hint)) hint = 0;
            scores.put(region, Math.sqrt(region.distanceSquared(cameraX, cameraY, cameraZ))
                    - (visible.test(region) ? 64 : 0) - Math.clamp(hint, 0, 1) * 32
                    - (frame - oldest(regions.get(region))) * 0.25);
        }
        ranked.sort((left, right) -> {
            long a = oldest(regions.get(left)), b = oldest(regions.get(right));
            boolean overdueA = frame - a >= MAX_WAIT_FRAMES, overdueB = frame - b >= MAX_WAIT_FRAMES;
            if (overdueA != overdueB) return overdueA ? -1 : 1;
            if (overdueA) {
                int serviceOrder = Long.compare(lastDispatch(regions.get(left)), lastDispatch(regions.get(right)));
                if (serviceOrder != 0) return serviceOrder;
                if (a != b) return Long.compare(a, b);
            }
            return Double.compare(scores.get(left), scores.get(right));
        });
        var result = new ArrayList<Work>();
        for (var region : ranked) {
            if (result.size() == limit) break;
            var tickets = new ArrayList<WorldLightingCache.Ticket>();
            long dispatch = ++dispatchSerial;
            for (var item : regions.get(region)) {
                item.inFlight = true;
                item.lastDispatch = dispatch;
                tickets.add(item.ticket);
            }
            result.add(new Work(region, tickets));
        }
        return List.copyOf(result);
    }

    private static long oldest(List<Pending> items) {
        long oldest = Long.MAX_VALUE;
        for (var item : items) oldest = Math.min(oldest, item.since);
        return oldest;
    }

    private static long lastDispatch(List<Pending> items) {
        long last = Long.MAX_VALUE;
        for (var item : items) last = Math.min(last, item.lastDispatch);
        return last;
    }

    public boolean complete(WorldLightingCache.Ticket ticket, float[] samples) {
        synchronize();
        var item = ticket == null ? null : pending.get(new Key(ticket.region(), ticket.channel()));
        if (closed || item == null || !item.inFlight || !item.ticket.equals(ticket)) return false;
        boolean accepted = cache.publish(ticket, samples);
        if (accepted) {
            // Publication only removed this dirty entry. Avoid rescanning every brick per completed channel.
            pending.remove(new Key(ticket.region(), ticket.channel()));
            synchronizedRevision = cache.revision();
        }
        return accepted;
    }

    public void retry(Work work) {
        synchronize();
        for (var ticket : work.tickets) {
            var item = pending.get(new Key(ticket.region(), ticket.channel()));
            if (item != null && item.ticket.equals(ticket)) item.inFlight = false;
        }
    }

    /** Feed only timestamps for actual lighting work, not terrain rendering or extraction CPU time. */
    public void recordGpuTime(int regions, long nanos) {
        if (regions < 1 || nanos < 1) throw new IllegalArgumentException("Invalid lighting GPU measurement");
        double sample = (double)nanos / regions;
        gpuNanosPerRegion = gpuNanosPerRegion == 0 ? sample : gpuNanosPerRegion * 0.75 + sample * 0.25;
    }

    public int pendingCount() { synchronize(); return pending.size(); }
    public int inFlightCount() {
        synchronize();
        return (int)pending.values().stream().filter(item -> item.inFlight).count();
    }
    @Override public void close() { pending.clear(); closed = true; }
}