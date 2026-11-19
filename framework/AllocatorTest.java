package com.lge.blurengine2;
import java.util.*;
public final class AllocatorTest {
 static int checks;
 static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static final class FakeGL implements ValidatedTargets.GL {
  int next=1,texture=700,fbo=800,error,allocSlot=-1,statusCalls;
  int failSlot=-1,failStage=-1,mode;boolean injected;
  final Set<Integer> textures=new HashSet<Integer>(),fbos=new HashSet<Integer>();
  void fail(int stage) {
   if(allocSlot==failSlot && stage==failStage && !injected){
    injected=true;if(mode==0)throw new IllegalStateException("GL wrapper failure");
    if(mode==1)throw new OutOfMemoryError("injected");
    error=0x505;
   }
  }
  public void get(int k,int[] v,int o){v[o]=k==0x8069?texture:k==0x8ca6?fbo:4096;if(k==0xd3a)v[o+1]=4096;}
  public int error(){int e=error;error=0;return e;}
  public void genTextures(int n,int[] v,int o){allocSlot++;v[o]=next++;textures.add(v[o]);fail(0);}
  public void genFramebuffers(int n,int[] v,int o){v[o]=next++;fbos.add(v[o]);fail(1);}
  public void bindTexture(int t,int n){texture=n;}
  public void bindFramebuffer(int t,int n){fbo=n;}
  public void texParameter(int t,int k,int v){}
  public void image(int w,int h){fail(2);}
  public void attach(int t){fail(3);}
  public int status(){statusCalls++;return allocSlot==failSlot && failStage==4?0x8cd6:0x8cd5;}
  public void deleteTextures(int n,int[] v,int o){ok(textures.remove(v[o]),"texture owned once");}
  public void deleteFramebuffers(int n,int[] v,int o){ok(fbos.remove(v[o]),"FBO owned once");}
 }
 public static void main(String[] args) {
  FakeGL gl=new FakeGL();ValidatedTargets.Batch b=ValidatedTargets.allocate(gl,1,960,432);
  ok(gl.statusCalls==7 && gl.textures.size()==7 && gl.fbos.size()==7,"all seven validated");
  ok(gl.texture==700 && gl.fbo==800,"bindings restored on success");
  ok(b.width[6]==15 && b.height[6]==6,"all dimensions mapped");
  b.release(gl,1);b.release(gl,1);ok(gl.textures.isEmpty() && gl.fbos.isEmpty(),"idempotent release");
  int transactions=0;
  for(int mode=0;mode<3;mode++)for(int stage=0;stage<5;stage++)for(int slot=0;slot<7;slot++) {
   gl=new FakeGL();gl.failSlot=slot;gl.failStage=stage;gl.mode=mode;
   boolean failed=false;try{ValidatedTargets.allocate(gl,1,960,432);}catch(IllegalStateException e){failed=true;}catch(OutOfMemoryError e){failed=true;ok(mode==1,"OOME category");}
   ok(failed,"allocation rejected");
   ok(gl.textures.isEmpty() && gl.fbos.isEmpty(),"partial candidate GL names cleaned including OOME");
   ok(gl.texture==700 && gl.fbo==800,"bindings restored on failure");transactions++;
  }
  gl=new FakeGL();gl.error=0x502;
  boolean failed=false;try{ValidatedTargets.allocate(gl,1,960,432);}catch(IllegalStateException e){failed=true;}
  ok(failed && gl.allocSlot==-1,"preexisting GL error fails before allocation");
  gl=new FakeGL();failed=false;try{ValidatedTargets.allocate(gl,1,5000,432);}catch(IllegalArgumentException e){failed=true;}
  ok(failed && gl.allocSlot==-1,"GL limits reject before allocation");
  gl=new FakeGL();b=ValidatedTargets.allocate(gl,1,960,432);Set<Integer> names=new HashSet<Integer>(gl.textures);
  b.release(gl,2);ok(b.abandoned && gl.textures.equals(names),"new context never deletes stale names");
  gl=new FakeGL();gl.next=Integer.MIN_VALUE;b=ValidatedTargets.allocate(gl,1,960,432);
  ok(b.textures[0]<0,"unsigned nonzero GL names accepted");b.release(gl,1);
  System.out.println("PASS "+checks+" allocator checks; "+transactions+" injected driver/wrapper/incomplete/OOME failures; actual GLES adapter compiled, GPU NOT executed");
 }
}
