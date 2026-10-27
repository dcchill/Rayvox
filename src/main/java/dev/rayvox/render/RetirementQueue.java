package dev.rayvox.render;

import java.util.ArrayDeque;

/** Destruction is ordered by the last submission that can reference an allocation. */
public final class RetirementQueue {
    private record Entry(long submission, Runnable destroy) {}
    private final ArrayDeque<Entry> entries=new ArrayDeque<>();
    public void retire(long lastUse, Runnable destroy) { entries.addLast(new Entry(lastUse,destroy)); }
    public void complete(long serial) {
        // Do not assume retire calls arrive sorted by last use.
        for (var iterator=entries.iterator(); iterator.hasNext();) {
            var entry=iterator.next();
            if (entry.submission() <= serial) { iterator.remove(); entry.destroy().run(); }
        }
    }
    public int size() { return entries.size(); }
}
