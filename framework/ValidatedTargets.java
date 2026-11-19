package com.lge.blurengine2;
import android.opengl.GLES20;

/** Candidate-only real GLES allocation source. NOT installed/integrated.
 * One full-size input plus six pyramid RGB targets. Does not invoke or modify
 * legacy RenderTarget allocation. Caller: GL thread, surface->renderer locks.
 * Publish only after allocate returns, wrappers/texture selectors prepared.
 */
public final class ValidatedTargets {
    static final int TEX=0x0de1, FBO=0x8d40, COMPLETE=0x8cd5;
    public interface GL {
        void get(int key,int[] values,int offset);
        int error();
        void genTextures(int count,int[] names,int offset);
        void genFramebuffers(int count,int[] names,int offset);
        void bindTexture(int target,int name);
        void bindFramebuffer(int target,int name);
        void texParameter(int target,int key,int value);
        void image(int width,int height);
        void attach(int texture);
        int status();
        void deleteTextures(int count,int[] names,int offset);
        void deleteFramebuffers(int count,int[] names,int offset);
    }
    public static final class AndroidGL implements GL {
        public void get(int key,int[] v,int o){GLES20.glGetIntegerv(key,v,o);}
        public int error(){return GLES20.glGetError();}
        public void genTextures(int n,int[] v,int o){GLES20.glGenTextures(n,v,o);}
        public void genFramebuffers(int n,int[] v,int o){GLES20.glGenFramebuffers(n,v,o);}
        public void bindTexture(int t,int n){GLES20.glBindTexture(t,n);}
        public void bindFramebuffer(int t,int n){GLES20.glBindFramebuffer(t,n);}
        public void texParameter(int t,int k,int v){GLES20.glTexParameteri(t,k,v);}
        public void image(int w,int h){GLES20.glTexImage2D(TEX,0,0x1907,w,h,0,0x1907,0x1401,null);}
        public void attach(int x){GLES20.glFramebufferTexture2D(FBO,0x8ce0,TEX,x,0);}
        public int status(){return GLES20.glCheckFramebufferStatus(FBO);}
        public void deleteTextures(int n,int[] v,int o){GLES20.glDeleteTextures(n,v,o);}
        public void deleteFramebuffers(int n,int[] v,int o){GLES20.glDeleteFramebuffers(n,v,o);}
    }
    public static final class Batch {
        public final long epoch;
        public final int[] width=new int[7],height=new int[7];
        public final int[] textures=new int[7],framebuffers=new int[7];
        // Preallocate delete scratch: OOM cleanup must not itself allocate arrays.
        private final int[] deleting=new int[1];
        public int cleanupFailures;
        public boolean abandoned;
        Batch(long epoch){this.epoch=epoch;}
        /** Idempotent and context-generation guarded. No stale GL calls. */
        public void release(GL gl,long currentEpoch) {
            boolean stale=epoch!=currentEpoch;
            if(stale)abandoned=true;
            for(int i=0;i<7;i++) {
                int f=framebuffers[i],t=textures[i];framebuffers[i]=textures[i]=0;
                if(stale)continue;
                deleting[0]=f;
                try{if(f!=0)gl.deleteFramebuffers(1,deleting,0);}catch(RuntimeException e){cleanupFailures++;}
                deleting[0]=t;
                try{if(t!=0)gl.deleteTextures(1,deleting,0);}catch(RuntimeException e){cleanupFailures++;}
            }
        }
    }
    static void check(GL gl){if(gl.error()!=0)throw new IllegalStateException("GL error; allocation rejected");}
    /** Epoch is a GL-context incarnation, not a numeric GLES object name.
     * Handles allocated IDs as unsigned bit patterns: GLES GLuint may be negative
     * in Java; zero is the only invalid generated name.
     * A preexisting GL error aborts before generating any names; not silently drained.
     */
    public static Batch allocate(GL gl,long epoch,int width,int height) {
        if(epoch<=0 || width<=0 || height<=0)throw new IllegalArgumentException();
        Batch b=new Batch(epoch);
        int[] saved=new int[5];
        check(gl);
        gl.get(0x8069,saved,0); // GL_TEXTURE_BINDING_2D on unchanged active unit
        gl.get(0x8ca6,saved,1); // GL_FRAMEBUFFER_BINDING
        gl.get(0x0d33,saved,2); // GL_MAX_TEXTURE_SIZE
        gl.get(0x0d3a,saved,3); // GL_MAX_VIEWPORT_DIMS: two values
        check(gl);
        if(saved[2]<=0 || saved[3]<=0 || saved[4]<=0 || width>saved[2] || height>saved[2] || width>saved[3] || height>saved[4])
            throw new IllegalArgumentException("target exceeds GL limits");
        boolean complete=false;
        try {
            try {
                for(int i=0;i<7;i++) {
                    int w=i==0?width:LandscapeSizing.level(width,i-1);
                    int h=i==0?height:LandscapeSizing.level(height,i-1);
                    b.width[i]=w;b.height[i]=h;
                    // GL writes directly to owner arrays BEFORE checks/possible throw.
                    gl.genTextures(1,b.textures,i);check(gl);
                    if(b.textures[i]==0)throw new IllegalStateException("zero texture name");
                    gl.genFramebuffers(1,b.framebuffers,i);check(gl);
                    if(b.framebuffers[i]==0)throw new IllegalStateException("zero FBO name");
                    gl.bindTexture(TEX,b.textures[i]);
                    gl.texParameter(TEX,0x2802,0x812f);
                    gl.texParameter(TEX,0x2803,0x812f);
                    gl.texParameter(TEX,0x2800,0x2601);
                    gl.texParameter(TEX,0x2801,0x2601);
                    gl.image(w,h);check(gl);
                    gl.bindFramebuffer(FBO,b.framebuffers[i]);
                    gl.attach(b.textures[i]);check(gl);
                    if(gl.status()!=COMPLETE)throw new IllegalStateException("incomplete candidate framebuffer");
                    check(gl);
                }
            } finally {
                // Attempt both restorations even if the first throws. They execute
                // before candidate publication, on success, failure, and OOME.
                try{gl.bindFramebuffer(FBO,saved[1]);}
                finally{gl.bindTexture(TEX,saved[0]);}
            }
            check(gl); // restoration error also prevents returning a candidate
            complete=true;
            return b;
        } finally {
            if(!complete)b.release(gl,epoch);
        }
    }
    private ValidatedTargets(){}
}
