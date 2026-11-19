package com.lge.blurengine2;

import android.graphics.Rect;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.Display;
import android.os.IBinder;
import com.lge.view.SurfaceControlEx;

/** Installed only at existing SF request and original latch sites. No new latch or scheduling. */
public final class CaptureIntegration {
    private final BlurGLSurfaceView view;
    private final BlurGLSurfaceView.GLrender renderer;
    private final StableCapturePacer pacer=new StableCapturePacer();
    private Frame pending;
    // Native audit: onFrameReplaced does not notify Java; timestamps are not unique.
    // Research-only gate: deliberately no production setter and no runtime auto-enable.
    private boolean inputIdentityTrusted=false;
    private boolean requested, valid, observedNewInput;
    private Frame observed, cached;
    private long observedTimestamp, observedCallbacks, cachedTimestamp, cachedCallbacks;
    private long cachedInputGeneration,cachedFxGeneration;
    private int cachedOes;
    private BlurInputMaker cachedInput;
    private FXBlurRenderer cachedFx;
    private long captures,conversions,pyramids,reuses;
    public CaptureIntegration(BlurGLSurfaceView view, BlurGLSurfaceView.GLrender renderer) {
        this.view=view; this.renderer=renderer;
    }
    private void locks() {
        if(!Thread.holdsLock(view.pacerLock()) || !Thread.holdsLock(renderer))
            throw new IllegalStateException("surface -> renderer locks required");
    }
    public void observe() {
        locks();
        requested=false;
        if (!eligible()) {pacer.reset(); pending=null; valid=false;}
    }
    private boolean eligible() {
        return !view.pacerHidden() && !view.pacerOwner().getRealHide() &&
            !view.pacerOwner().isPowerSaveMode() &&
            view.pacerSource()==Global.BACKGROUND_RESOURCE.NONE &&
            view.pacerMode()!=Global.MODE.IMAGE;
    }
    private Frame sample() {
        Display display=view.pacerDisplay();
        int rawRotation=display.getRotation(), rotation=rawRotation;
        int x=renderer.pacerX(), y=renderer.pacerY();
        int w=renderer.pacerW(), h=renderer.pacerH();
        int left=x,top=y,right=x+w,bottom=y+h;
        if(rawRotation==1) {
            left=view.pacerDisplayWidth()-y-h;top=x;right=left+h;bottom=top+w;rotation=3;
        } else if(rawRotation==3) {
            left=y;top=view.pacerDisplayHeight()-x-w;right=left+h;bottom=top+w;rotation=1;
        }
        float scale=view.pacerScale();
        Surface surface=view.pacerSurface(); SurfaceTexture texture=view.pacerTexture();
        IBinder handle=view.getSurfaceControl().getHandle();
        int id=view.getDisplayId();
        Point size=view.pacerSize(); int sx=size.x,sy=size.y;
        boolean eligible=eligible();
        Rect crop=new Rect(left,top,right,bottom);
        StableCapturePacer.Snapshot snapshot=new StableCapturePacer.Snapshot(
            new Object[]{texture,surface,display,handle,view.pacerSource(),view.pacerMode(),renderer.pacerContext()},
            new int[]{left,top,right,bottom,(int)(w*scale),(int)(h*scale),rotation,
                Float.floatToRawIntBits(scale),id,rawRotation,x,y,w,h,sx,sy,
                view.pacerDisplayWidth(),view.pacerDisplayHeight(),view.pacerHideSerial(),eligible?1:0});
        return new Frame(snapshot,surface,texture,handle,crop,(int)(w*scale),(int)(h*scale),rotation,id,eligible);
    }
    public void capture() {
        locks();
        Frame before=sample();
        // Keep original crop field observable, but pass a distinct request-local Rect.
        renderer.pacerCrop().set(before.crop);
        Frame confirmation=sample();
        if(!before.snapshot.same(confirmation.snapshot)) pacer.reset();
        boolean mayPace=before.snapshot.same(confirmation.snapshot) && before.eligible &&
            view.pacerState()==BlurGLSurfaceView.RENDER_STATE.RENDER;
        boolean request=pacer.before(before.snapshot,mayPace,before.texture.getTimestamp(),System.nanoTime());
        pending=before;
        requested|=request;
        if(request) {
            valid=false; captures++;
            SurfaceControlEx.screenshotForBlurEngine2(renderer.pacerContext(),before.id,
                before.surface,before.handle,before.crop,before.width,before.height,false,before.rotation);
        }
        // Mismatch must refuse credit even if state returns before completion.
        if(!before.snapshot.same(confirmation.snapshot)) {pacer.reset();pending=null;}
    }
    public void latched() {
        locks();
        Frame after=sample();
        long timestamp=after.texture.getTimestamp(), callbacks=renderer.pacerCallbacks();
        observedNewInput=observed==null || !observed.snapshot.same(after.snapshot) ||
            timestamp!=observedTimestamp || callbacks!=observedCallbacks;
        observed=after; observedTimestamp=timestamp; observedCallbacks=callbacks;
        // Observe every original latch, including late buffers on skipped request frames.
        if(pending!=null && !pending.snapshot.same(after.snapshot)) pacer.reset();
        else pacer.latched(after.snapshot,timestamp);
        pending=null;
    }
    public void invalidate() {locks();valid=false;}
    /** Called in the actual draw transaction, after updateInputTexture. */
    public void preprocess() {
        locks();
        Frame before=sample();
        long timestamp=before.texture.getTimestamp(),callbacks=renderer.pacerCallbacks();
        BlurInputMaker input=renderer.pacerInput(); FXBlurRenderer fx=renderer.pacerFx();
        long ig=input.reuseGeneration(),fg=fx.reuseGeneration(); int oes=renderer.pacerOes();
        boolean stable=eligible() && view.pacerState()==BlurGLSurfaceView.RENDER_STATE.RENDER;
        boolean monotonic=cached==null || !before.snapshot.same(cached.snapshot) ||
            timestamp>=cachedTimestamp;
        boolean same=inputIdentityTrusted && valid && stable && !requested && !observedNewInput &&
            timestamp!=0 && timestamp>=cachedTimestamp && timestamp==cachedTimestamp &&
            callbacks==cachedCallbacks && cached!=null && before.snapshot.same(cached.snapshot) &&
            input==cachedInput && fx==cachedFx && oes==cachedOes &&
            ig==cachedInputGeneration && fg==cachedFxGeneration;
        if(same) {reuses++;return;}
        valid=false;
        // Invalid until BOTH calls return normally; exceptions retain original propagation.
        input.render(); conversions++;
        fx.renderToTexture(); pyramids++;
        Frame after=sample();
        if(stable && monotonic && eligible() && view.pacerState()==BlurGLSurfaceView.RENDER_STATE.RENDER &&
            before.snapshot.same(after.snapshot) && timestamp!=0 &&
            timestamp==after.texture.getTimestamp() && callbacks==renderer.pacerCallbacks() &&
            input==renderer.pacerInput() && fx==renderer.pacerFx() && oes==renderer.pacerOes() &&
            ig==input.reuseGeneration() && fg==fx.reuseGeneration()) {
            cached=after;cachedTimestamp=timestamp;cachedCallbacks=callbacks;
            cachedInput=input;cachedFx=fx;cachedOes=oes;
            cachedInputGeneration=ig;cachedFxGeneration=fg;valid=true;
        }
        observedNewInput=false;
    }
    /** Aggregate host/debug snapshot, not per-frame logging. Caller holds original monitors. */
    public long[] counters() {locks();return new long[]{captures,conversions,pyramids,reuses};}

    private static final class Frame {
        final StableCapturePacer.Snapshot snapshot;
        final Surface surface; final SurfaceTexture texture; final IBinder handle; final Rect crop;
        final int width,height,rotation,id; final boolean eligible;
        Frame(StableCapturePacer.Snapshot snapshot,Surface surface,SurfaceTexture texture,IBinder handle,
            Rect crop,int width,int height,int rotation,int id,boolean eligible) {
            this.snapshot=snapshot;this.surface=surface;this.texture=texture;this.handle=handle;
            this.crop=crop;this.width=width;this.height=height;this.rotation=rotation;this.id=id;this.eligible=eligible;
        }
    }
}
