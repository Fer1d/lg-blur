package com.lge.blurengine2;
import java.util.*;

public final class HostTest {
 static int checks;
 static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static final class Fake implements LifecycleModel.Backend {
  int calls, failAt=-1, invalidAt=-1,zeroAt=-1,errorAt=-1,deleteFailures;
  int next=1;
  final Set<Integer> textures=new HashSet<Integer>(), fbos=new HashSet<Integer>();
  final Set<Integer> deletedTextures=new HashSet<Integer>(),deletedFbos=new HashSet<Integer>();
  public void allocate(LifecycleModel.Target t) {
   int c=calls++;
   t.texture=next++;textures.add(t.texture);
   if(c==failAt)throw new IllegalStateException("partial failure");
   if(c==errorAt)throw new OutOfMemoryError("injected");
   if(c!=zeroAt){t.framebuffer=next++;fbos.add(t.framebuffer);}
  }
  public boolean validate(LifecycleModel.Target t){return calls-1!=invalidAt;}
  public void deleteFramebuffer(int id){ok(deletedFbos.add(id),"FBO double delete");fbos.remove(id);if(deleteFailures-->0)throw new IllegalStateException();}
  public void deleteTexture(int id){ok(deletedTextures.add(id),"texture double delete");textures.remove(id);}
  void newNamespace(){textures.clear();fbos.clear();deletedTextures.clear();deletedFbos.clear();next=1;}
 }
 static LifecycleModel model(Fake f){return new LifecycleModel(new Object(),new Object(),f);}
 static void create(LifecycleModel m){synchronized(m.surface){synchronized(m.renderer){m.contextCreated();}}}
 static boolean resize(LifecycleModel m,int w,int h,boolean retry){synchronized(m.surface){synchronized(m.renderer){return m.resize(w,h,retry);}}}
 static void release(LifecycleModel m){synchronized(m.surface){synchronized(m.renderer){m.release();}}}
 static void sizing() {
  long s=LandscapeSizing.fit(2400,1080,.5f,true,false,false);
  ok(LandscapeSizing.width(s)==960 && LandscapeSizing.height(s)==432,"wide landscape fit");
  s=LandscapeSizing.fit(1920,1080,.5f,true,false,false);
  ok(LandscapeSizing.width(s)==960 && LandscapeSizing.height(s)==540,"16:9 fit");
  s=LandscapeSizing.fit(1600,1200,1f,true,false,false);
  ok(LandscapeSizing.width(s)==1440 && LandscapeSizing.height(s)==1080,"4:3 fit");
  s=LandscapeSizing.fit(320,180,1f,true,false,false);
  ok(LandscapeSizing.width(s)==320 && LandscapeSizing.height(s)==180,"no upscaling");
  ok(LandscapeSizing.fit(1080,2400,.5f,true,false,false)==0,"portrait unchanged");
  ok(LandscapeSizing.fit(1080,1080,.5f,true,false,false)==0,"square unchanged");
  ok(LandscapeSizing.fit(2400,1080,.5f,true,true,false)==0,"IMAGE unchanged");
  ok(LandscapeSizing.fit(2400,1080,.5f,true,false,true)==0,"offscreen unchanged");
  ok(LandscapeSizing.fit(2400,1080,.5f,false,false,false)==0,"not realtime unchanged");
  for(float scale:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.MIN_VALUE,Float.MAX_VALUE})
   ok(LandscapeSizing.fit(2400,1080,scale,true,false,false)==0,"invalid scale");
  ok(LandscapeSizing.fit(100,0,.5f,true,false,false)==0,"zero geometry");
  Random random=new Random(41);
  for(int i=0;i<10000;i++) {
   int h=2+random.nextInt(5000),w=h+1+random.nextInt(5000);float scale=.01f+random.nextFloat();
   s=LandscapeSizing.fit(w,h,scale,true,false,false);int bw=LandscapeSizing.width(s),bh=LandscapeSizing.height(s);
   double factor=Math.min(1.0,Math.min(1920.0*scale/w,1080.0*scale/h));
   if(w*factor<1 || h*factor<1){ok(s==0,"subpixel fit fails closed");continue;}
   ok(bw>0 && bh>0 && bw<=w && bh<=h,"nonzero bounded surface");
   ok(bw<=1920.0*scale && bh<=1080.0*scale,"scaled FHD envelope");
   ok(Math.abs(bw-w*factor)<1.00000001 && Math.abs(bh-h*factor)<1.00000001,"aspect rounding below 1px");
   for(int l=0;l<6;l++)ok(LandscapeSizing.level(bw,l)>0 && LandscapeSizing.level(bh,l)>0,"six nonzero levels");
  }
  ok(LandscapeSizing.level(1,5)==1,"tiny levels clamp");
  s=LandscapeSizing.fit(Integer.MAX_VALUE,1,.5f,true,false,false);
  ok(s==0,"unrepresentable aspect fails closed");
 }
 static void lifecycle() {
  Fake f=new Fake();LifecycleModel m=model(f);
  boolean threw=false;try{m.contextCreated();}catch(IllegalStateException e){threw=true;}ok(threw,"lock assertion");
  create(m);ok(resize(m,960,432,false),"initial transaction");
  ok(f.calls==7 && m.live.targets.length==7,"input + six children");
  long g=m.resourceGeneration;Object animation=m.animation;
  m.cacheValid=true;
  for(int i=0;i<1000;i++)ok(resize(m,960,432,false),"identical lifecycle no-op");
  ok(f.calls==7 && m.resourceGeneration==g && m.cacheValid,"no repeated allocations or invalidations");
  LifecycleModel.Bundle old=m.live;
  ok(resize(m,960,540,false),"resize succeeds");
  ok(f.deletedFbos.size()==7 && f.deletedTextures.size()==7,"old six plus input released");
  ok(m.resourceGeneration>g && !m.cacheValid,"resource generation invalidation");
  ok(m.currentAnimationLevel==3.75f && m.animation==animation && m.mask==91 && m.dim==0x40203040 && m.oes==92 && m.partial,"render state preserved");
  synchronized(m.surface){synchronized(m.renderer){m.cleanup(old);}}
  ok(f.deletedTextures.size()==7,"cleanup idempotent");
  release(m);release(m);ok(f.textures.isEmpty() && f.fbos.isEmpty(),"release all/idempotent");
  // Fail at every allocation including a partial texture-only allocation.
  for(int mode=0;mode<4;mode++)for(int slot=0;slot<7;slot++) {
   f=new Fake();m=model(f);create(m);ok(resize(m,960,432,false),"failure baseline");
   old=m.live;g=m.resourceGeneration;int at=f.calls+slot;
   if(mode==0)f.failAt=at;if(mode==1)f.invalidAt=at;if(mode==2)f.zeroAt=at;if(mode==3)f.errorAt=at;
   boolean result=false,oom=false;
   try{result=resize(m,960,540,false);}catch(OutOfMemoryError e){oom=true;}
   ok(!result && (oom==(mode==3)),"failure behavior matches category");
   ok(m.live==old && !m.ready && !m.cacheValid && m.resourceGeneration>g,"no partial publication");
   ok(f.textures.size()==7 && f.fbos.size()==7,"all candidate names cleaned");
   int calls=f.calls;
   if(mode!=3){ok(!resize(m,960,540,false) && f.calls==calls,"failure latched; no allocation storm");}
   f.failAt=f.invalidAt=f.zeroAt=f.errorAt=-1;
   ok(resize(m,960,540,true),"explicit retry");release(m);
   ok(f.textures.isEmpty() && f.fbos.isEmpty(),"failure/retry no leaks");
  }
  f=new Fake();m=model(f);create(m);ok(resize(m,960,432,false),"context setup");old=m.live;
  f.newNamespace();create(m);
  ok(f.deletedFbos.isEmpty() && f.deletedTextures.isEmpty() && m.abandoned==7,"context recreation never deletes stale names");
  ok(resize(m,960,432,false),"new namespace reuses names");
  synchronized(m.surface){synchronized(m.renderer){m.cleanup(old);}}
  ok(f.deletedFbos.isEmpty() && f.textures.size()==7 && f.fbos.size()==7,"late cleanup cannot delete new namespace IDs");
  release(m);
  f=new Fake();m=model(f);create(m);resize(m,960,432,false);f.deleteFailures=1;
  ok(resize(m,960,540,false),"cleanup failure cannot roll back publication");
  ok(m.ready && m.cleanupErrors==1 && f.deletedFbos.size()==7 && f.deletedTextures.size()==7,"cleanup continues after exception");release(m);
 }
 public static void main(String[] args){sizing();lifecycle();System.out.println("PASS "+checks+" checks; 10000 sizing cases; 28 allocation-failure transactions; 7 targets; host model ONLY");}
}
