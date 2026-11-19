package com.lge.blurengine2;

/** Host-only executable specification, NOT a production GL resource adapter.
 * Backend must expose partial names before any operation that can fail. */
public final class LifecycleModel {
    interface Backend {
        void allocate(Target t);
        boolean validate(Target t);
        void deleteFramebuffer(int id);
        void deleteTexture(int id);
    }
    static final class Target {
        final int w,h; final long epoch;
        int texture, framebuffer;
        Target(int w,int h,long epoch) {this.w=w;this.h=h;this.epoch=epoch;}
    }
    static final class Bundle {
        final int w,h; final long epoch; final Target[] targets = new Target[7];
        Bundle(int w,int h,long epoch) {this.w=w;this.h=h;this.epoch=epoch;}
    }
    final Object surface, renderer;
    final Thread glThread;
    final Backend backend;
    Bundle live;
    long epoch, resourceGeneration, inputGeneration, fxGeneration;
    boolean cacheValid, ready;
    int failedW,failedH, cleanupErrors, abandoned;
    // State must stay on existing renderers: target-only swaps never touch these.
    float currentAnimationLevel = 3.75f;
    int mask=91,dim=0x40203040,oes=92;
    boolean partial=true;
    final Object animation = new Object();
    LifecycleModel(Object surface,Object renderer,Backend backend) {
        this.surface=surface;this.renderer=renderer;this.backend=backend;
        glThread=Thread.currentThread();
    }
    void locks() {
        if (!Thread.holdsLock(surface) || !Thread.holdsLock(renderer))
            throw new IllegalStateException("surface -> renderer locks required");
        if (Thread.currentThread()!=glThread) throw new IllegalStateException("GL thread required");
    }
    /** Invoke at every onSurfaceCreated BEFORE any new glGen*. Drop stale names,
     * do not call release/delete on old input, FX, MultiScale, OES or image IDs. */
    void contextCreated() {
        locks();epoch++;
        if(live!=null) for(Target t:live.targets) if(t!=null) abandoned++;
        live=null;ready=false;failedW=failedH=0;invalidate();
    }
    void invalidate() {
        cacheValid=false;resourceGeneration++;inputGeneration++;fxGeneration++;
    }
    /** Only lifecycle dispatch calls this; not onDrawFrame. Return false means
     * suppress preprocessing/final render for desired geometry, transparent clear.
     * No automatic fallback that publishes a half-valid pyramid. */
    boolean resize(int w,int h, boolean explicitRetry) {
        locks();
        if(w<=0 || h<=0 || epoch==0) {ready=false;return false;}
        if(live!=null && live.epoch==epoch && live.w==w && live.h==h) {
            ready=true;return true;
        }
        ready=false;
        if(!explicitRetry && failedW==w && failedH==h) return false;
        invalidate();
        Bundle candidate=new Bundle(w,h,epoch);
        boolean published=false;
        try {
            for(int i=0;i<7;i++) {
                int tw=i==0?w:LandscapeSizing.level(w,i-1);
                int th=i==0?h:LandscapeSizing.level(h,i-1);
                // Register ownership BEFORE allocate can throw or create partial IDs.
                Target t=new Target(tw,th,epoch);candidate.targets[i]=t;
                backend.allocate(t);
                if(t.texture==0 || t.framebuffer==0 || !backend.validate(t))
                    throw new IllegalStateException("invalid allocation");
            }
            // Production adapter must precompute all texture arrays/blur selectors
            // before this no-throw publication section while both monitors are held.
            Bundle old=live;live=candidate;published=true;
            failedW=failedH=0;ready=true;
            cleanup(old);
            return true;
        } catch(RuntimeException e) {
            failedW=w;failedH=h;
            return false;
        } finally {
            // Also cleans partial allocations on OOME/Error, which still propagates.
            if(!published) cleanup(candidate);
        }
    }
    void cleanup(Bundle b) {
        if(b==null)return;
        // Context loss destroys its namespace. NEVER delete its numeric names in a new one.
        if(b.epoch!=epoch) {for(Target t:b.targets) if(t!=null) abandoned++;return;}
        for(int i=0;i<b.targets.length;i++) {
            Target t=b.targets[i];b.targets[i]=null;
            if(t==null)continue;
            int f=t.framebuffer, x=t.texture;t.framebuffer=t.texture=0;
            // Attempt both IDs and all later targets even if one delete throws.
            try {if(f!=0)backend.deleteFramebuffer(f);}catch(RuntimeException e){cleanupErrors++;}
            try {if(x!=0)backend.deleteTexture(x);}catch(RuntimeException e){cleanupErrors++;}
        }
    }
    void release() {locks();invalidate();cleanup(live);live=null;ready=false;}
}
