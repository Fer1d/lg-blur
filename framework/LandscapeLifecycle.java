package com.lge.blurengine2;
import android.opengl.GLES20;
import com.lge.blurengine2.util.RenderTarget;

/** Actual candidate controller called by generated GLrender smali wrappers.
 * All calls occur on GL thread under surface -> renderer monitors.
 * Polling compares primitive keys only; allocation occurs once per changed key.
 */
public final class LandscapeLifecycle {
 private final ValidatedTargets.GL gl;
 private long epoch;
 private ValidatedTargets.Batch live;
 private boolean initialized, adapted, ready, seen;
 private int sw,sh,scaleBits;
 private Global.MODE mode;
 private Global.BACKGROUND_RESOURCE source;
 public LandscapeLifecycle(){this(new ValidatedTargets.AndroidGL());}
 LandscapeLifecycle(ValidatedTargets.GL gl){this.gl=gl;}
 public void created(){epoch++;live=null;initialized=adapted=ready=seen=false;}
 public void initialized(){initialized=true;seen=false;}
 public void changed(){seen=false;ready=false;}
 public boolean ensure(BlurGLSurfaceView view,BlurGLSurfaceView.GLrender renderer) {
  if(!Thread.holdsLock(view.pacerLock()) || !Thread.holdsLock(renderer))throw new IllegalStateException("surface->renderer locks required");
  if(!initialized)return false;
  int w=renderer.pacerW(),h=renderer.pacerH(),bits=Float.floatToRawIntBits(view.pacerScale());
  Global.MODE m=view.pacerMode();Global.BACKGROUND_RESOURCE s=view.pacerSource();
  if(seen && w==sw && h==sh && bits==scaleBits && m==mode && s==source)return ready;
  sw=w;sh=h;scaleBits=bits;mode=m;source=s;seen=true;ready=false;
  float scale=view.pacerScale();
  if(w<=0 || h<=0)return false;
  boolean horizontal=w>h && m!=Global.MODE.IMAGE && s==Global.BACKGROUND_RESOURCE.NONE;
  if(!horizontal && !adapted){ready=true;return true;}
  long size=horizontal?LandscapeSizing.fit(w,h,scale,true,false,false):0;
  int tw=horizontal?LandscapeSizing.width(size):(int)(1080f*scale);
  int th=horizontal?LandscapeSizing.height(size):(int)(1920f*scale);
  // No distorted/clamped production pyramid: all six levels must fit naturally.
  if(tw<64 || th<64 || !(scale>0) || scale==Float.POSITIVE_INFINITY)return false;
  renderer.landscapeInvalidate();
  ValidatedTargets.Batch candidate=null;
  boolean published=false,oldValid=false;
  try {
   BlurInputMaker input=renderer.pacerInput();FXBlurRenderer fx=renderer.pacerFx();
   MultiScaleBlur[] children=fx.landscapeChildren();
   if(children==null || children.length!=6)throw new IllegalStateException("six children required");
   RenderTarget[] old=new RenderTarget[7];old[0]=input.landscapeTarget();
   for(int i=0;i<6;i++){if(children[i]==null)throw new IllegalStateException();old[i+1]=children[i].landscapeTarget();}
   if(live==null){
    // Adopt old seven names BEFORE allocating a replacement; never release FX programs.
    ValidatedTargets.Batch adopted=new ValidatedTargets.Batch(epoch);
    for(int i=0;i<7;i++){
     if(old[i]==null)throw new IllegalStateException();
     adopted.width[i]=old[i].getWidth();adopted.height[i]=old[i].getHeight();
     adopted.textures[i]=old[i].getTextureId();adopted.framebuffers[i]=old[i].getFrameBufferId();
    }
    live=adopted;
   }
   // Reject zero names and failed initial shaders/link before any target swap.
   int[] programs=new int[9];input.landscapePrograms(programs);fx.landscapePrograms(programs);
   for(int i=0;i<6;i++)programs[i+3]=children[i].landscapeProgram();
   int[] result=new int[1];
   for(int p:programs){if(p==0)throw new IllegalStateException("zero program");GLES20.glGetProgramiv(p,0x8b82,result,0);if(result[0]==0)throw new IllegalStateException("unlinked program");}
   ValidatedTargets.check(gl);
   // Check adopted/current attachments too; legacy createRenderTarget only logged failure.
   int[] binding=new int[1];gl.get(0x8ca6,binding,0);
   try{for(int i=0;i<7;i++){
    if(live.textures[i]==0 || live.framebuffers[i]==0)throw new IllegalStateException("invalid adopted names");
    gl.bindFramebuffer(ValidatedTargets.FBO,live.framebuffers[i]);
    if(gl.status()!=ValidatedTargets.COMPLETE)throw new IllegalStateException("incomplete adopted target");
    ValidatedTargets.check(gl);
   }}finally{gl.bindFramebuffer(ValidatedTargets.FBO,binding[0]);}
   ValidatedTargets.check(gl);oldValid=true;
   // Bound simultaneous old7+new7 storage conservatively at8bytes/pixel64MiB.
   // Driver overhead/real format cannot be proven here; GL allocation checks remain required.
   long pixels=0;
   for(int i=0;i<7;i++){
    pixels+=(long)live.width[i]*live.height[i];
    int nw=i==0?tw:LandscapeSizing.level(tw,i-1),nh=i==0?th:LandscapeSizing.level(th,i-1);
    pixels+=(long)nw*nh;
   }
   if(pixels>8388608L)throw new IllegalStateException("transaction target budget");
   // Same owned geometry needs no replacement, only after it passed validation.
   if(live.width[0]==tw && live.height[0]==th){adapted=horizontal;ready=true;return true;}
   candidate=ValidatedTargets.allocate(gl,epoch,tw,th);
   RenderTarget[] prepared=new RenderTarget[7];int[] ids=new int[7];
   for(int i=0;i<7;i++){
    prepared[i]=new RenderTarget(candidate.width[i],candidate.height[i],candidate.textures[i],candidate.framebuffers[i],true);
    ids[i]=candidate.textures[i];
   }
   int level=fx.getCurrentScale();if(level<0 || level>=6)throw new IllegalStateException("invalid current float level");
   int lower=ids[level],upper=ids[level+1];
   // No allocations, GL operations, callbacks or getters from first live store to final commit.
   input.landscapePublish(prepared[0],tw,th);
   children[0].landscapePublish(prepared[1],candidate.width[1],candidate.height[1]);
   children[1].landscapePublish(prepared[2],candidate.width[2],candidate.height[2]);
   children[2].landscapePublish(prepared[3],candidate.width[3],candidate.height[3]);
   children[3].landscapePublish(prepared[4],candidate.width[4],candidate.height[4]);
   children[4].landscapePublish(prepared[5],candidate.width[5],candidate.height[5]);
   children[5].landscapePublish(prepared[6],candidate.width[6],candidate.height[6]);
   fx.landscapePublish(ids,lower,upper);
   ValidatedTargets.Batch retired=live;live=candidate;published=true;adapted=horizontal;ready=true;
   retired.release(gl,epoch);
   return true;
  } catch(RuntimeException failure){ready=oldValid;return ready;}
  catch(OutOfMemoryError allocationFailure){ready=oldValid;return ready;}
  finally{if(candidate!=null && !published)candidate.release(gl,epoch);}
 }
}
