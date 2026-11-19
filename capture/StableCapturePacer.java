package com.lge.blurengine2;

/** Renderer-owned state; caller holds surface then renderer monitors.
 * Timestamp movement is evidence of buffer progress, never capture provenance. */
public final class StableCapturePacer {
    public static final long PERIOD_NS = 33333333L;
    public static final class Snapshot {
        private final Object[] refs;
        private final int[] values;
        public Snapshot(Object[] refs, int[] values) {
            this.refs=refs.clone(); this.values=values.clone();
        }
        public boolean same(Snapshot b) {
            if (b==null || refs.length!=b.refs.length || values.length!=b.values.length) return false;
            for(int i=0;i<refs.length;i++) if(refs[i]!=b.refs[i]) return false;
            for(int i=0;i<values.length;i++) if(values[i]!=b.values[i]) return false;
            return true;
        }
    }
    private Snapshot config;
    private long stableSince,lastAttempt,beforeTimestamp;
    private boolean attempted,progressed,pending;
    public void reset() { config=null; attempted=progressed=pending=false; }
    public boolean before(Snapshot next, boolean renderEligible, long timestamp, long now) {
        if (!next.same(config)) {
            config=next; stableSince=now; attempted=progressed=pending=false;
        }
        long stable=now-stableSince, due=now-lastAttempt;
        if(stable<0 || (attempted && due<0)) {
            stableSince=now; attempted=progressed=pending=false;
        }
        if(renderEligible && attempted && progressed && stable>=PERIOD_NS && due>=0 && due<PERIOD_NS)
            return false;
        pending=attempted=true; progressed=false; lastAttempt=now; beforeTimestamp=timestamp;
        return true;
    }
    public void latched(Snapshot actual, long timestamp) {
        if (!actual.same(config)) { reset(); return; }
        if(attempted) progressed=timestamp!=0 && timestamp>beforeTimestamp;
        pending=false;
    }
}
