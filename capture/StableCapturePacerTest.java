package com.lge.blurengine2;
public final class StableCapturePacerTest {
    static int checks;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    static StableCapturePacer.Snapshot s(Object surface,int n){return new StableCapturePacer.Snapshot(new Object[]{surface},new int[]{n});}
    public static void main(String[] args){
        Object surface=new Object(); StableCapturePacer p=new StableCapturePacer();
        StableCapturePacer.Snapshot a=s(surface,0);
        long t=0,ts=1;
        check(p.before(a,true,ts,t),"initial request");p.latched(a,++ts);
        t+=16666667;check(p.before(s(surface,0),true,ts,t),"settling request");p.latched(a,++ts);
        t+=16666667;check(!p.before(s(surface,0),true,ts,t),"equivalent snapshot paces");
        t+=16666667;check(p.before(a,true,ts,t),"due request");p.latched(a,ts);
        t+=1000;check(p.before(a,true,ts,t),"void failure/no progress retries immediately");p.latched(a,++ts);
        check(!p.before(a,true,ts,t+1000),"retry success recovers");
        check(p.before(s(surface,1),true,ts,t+2000),"config changes refresh");
        StableCapturePacer.Snapshot b=s(new Object(),1);
        check(p.before(b,true,ts,t+3000),"same texture replacement surface refresh");p.latched(b,++ts);
        check(p.before(b,true,ts,t+4000),"replacement settles rather than poison");p.latched(b,++ts);
        check(p.before(b,true,ts,t+40000000),"replacement reaches due");p.latched(b,++ts);
        check(!p.before(b,true,ts,t+40001000),"replacement can pace again");
        check(p.before(b,false,ts,t+40002000),"setup states ungated");p.latched(b,++ts);
        p.reset();check(p.before(b,true,ts,t+40003000),"hidden/power observation refresh");p.latched(b,++ts);
        p.latched(a,++ts);check(p.before(b,true,ts,t+40004000),"post-state mismatch rejects credit");
        p.latched(b,0);check(p.before(b,true,0,t+40005000),"zero timestamp does not earn credit");
        p.latched(b,++ts);check(p.before(b,true,ts,-10),"clock rollback retries");
        Object[] refs={surface};int[] values={7};StableCapturePacer.Snapshot immutable=new StableCapturePacer.Snapshot(refs,values);
        refs[0]=null;values[0]=8;check(immutable.same(s(surface,7)),"snapshot defensively copied");
        System.out.println("PASS helper state checks="+checks);
    }
}
