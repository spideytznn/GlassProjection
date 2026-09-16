package io.github.sixzleo.tabfold.probe;

import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.opengl.*;
import android.os.*;
import android.view.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.sixzleo.tabfold.projection.ProjectionMath;

/** Experimental shared capture with per-display output. No native task migration. */
final class DualLiveRenderer implements AutoCloseable {
    static final int SIZE=1182;
    final EGLDisplay display;
    final EGLContext context;
    final EGLConfig config;
    final EGLSurface bootstrap;
    final FloatBuffer vertices;
    final HandlerThread callbacks=new HandlerThread("dual-source-callback");
    final AtomicBoolean available=new AtomicBoolean();
    final float[] matrix=new float[16];
    final boolean effects;
    int sourceTexture,program,sourceWidth,sourceHeight,sourceTurn,frames;
    SurfaceTexture texture;
    Surface input;
    VirtualDisplay mirror;
    LiveBlurPyramid pyramid;
    boolean hasFrame;
    boolean rightHalfMirror;
    int snapshotTexture,snapshotProgram,snapshotWidth,snapshotHeight;
    long sourceTimestamp;
    float eased=Float.NaN;
    long lastDraw;
    static final String ORIENT="uniform float outputTurn;uniform vec2 outputScreen;vec2 orient(vec2 p){if(outputTurn<.5)return p;if(outputTurn<1.5)return vec2(p.y,1.-p.x);if(outputTurn<2.5)return vec2(1.-p.x,1.-p.y);return vec2(1.-p.y,p.x);}";
    static final String PLAIN="#extension GL_OES_EGL_image_external : require\nprecision highp float;uniform samplerExternalOES source;uniform mat4 tex;uniform vec2 sourceExtent,sourceFit,screen;uniform vec4 sourceCrop,outputRegion;uniform float canvasSize;varying vec2 uv;"+ORIENT+"void main(){vec2 p=uv*canvasSize/outputScreen;if(p.x>1.||p.y>1.){gl_FragColor=vec4(0.);return;}p=(orient(p)-outputRegion.xy)/outputRegion.zw;if(p.x<0.||p.y<0.||p.x>1.||p.y>1.){gl_FragColor=vec4(0.,0.,0.,1.);return;}p=(p-.5)/sourceFit+.5;if(p.x<0.||p.y<0.||p.x>1.||p.y>1.){gl_FragColor=vec4(.015,.02,.025,1.);return;}p=sourceCrop.xy+p*sourceCrop.zw;p=(1.-sourceExtent)*.5+p*sourceExtent;gl_FragColor=texture2D(source,(tex*vec4(p.x,1.-p.y,0.,1.)).xy);}";

    static final class Output implements AutoCloseable {
        final SurfaceControl layer;
        final Surface surface;
        final EGLSurface window;
        final EGLDisplay display;
        final int width,height,rotation,stack;
        final int viewRotation,outputTurn,viewWidth,viewHeight;
        final boolean inner;
        Output(DualLiveRenderer renderer,int width,int height,int rotation,int stack,boolean inner)throws Exception {
            this.width=width;this.height=height;this.rotation=rotation;this.stack=stack;this.inner=inner;display=renderer.display;
            // This lhasa trial uses the previously measured upright inner pose (3).
            // Presentation display 1 otherwise stays at rotation 0 even in that pose.
            viewRotation=inner&&stack!=0?3:rotation;outputTurn=(viewRotation-rotation+4)%4;
            viewWidth=outputTurn%2==0?width:height;viewHeight=outputTurn%2==0?height:width;
            layer=new SurfaceControl.Builder().setName("Glass bounded dual live "+(inner?"inner":"cover")).setBufferSize(SIZE,SIZE).setOpaque(true).setHidden(true).build();
            try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
                SurfaceControl.Transaction.class.getMethod("setLayerStack",SurfaceControl.class,int.class).invoke(t,layer,stack);
                SurfaceControl.Transaction.class.getMethod("setSkipScreenshot",SurfaceControl.class,boolean.class).invoke(t,layer,true);
                // Raw GL draws in logical coordinates; prevent producer pre-rotation
                // from shifting the visible crop within the oversized square buffer.
                SurfaceControl.Transaction.class.getMethod("setFixedTransformHint",SurfaceControl.class,int.class).invoke(t,layer,0);
                t.setLayer(layer,2000000).setScale(layer,2f,2f).setPosition(layer,0,0).apply();
            }
            surface=Surface.class.getConstructor(SurfaceControl.class).newInstance(layer);
            window=EGL14.eglCreateWindowSurface(display,renderer.config,surface,new int[]{EGL14.EGL_NONE},0);
            if(window==EGL14.EGL_NO_SURFACE)throw new IllegalStateException("Output EGL surface");
        }
        void show(){try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){t.setVisibility(layer,true).apply();}}
        public void close(){
            try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){t.setVisibility(layer,false).reparent(layer,null).apply();}
            EGL14.eglDestroySurface(display,window);surface.release();layer.release();
        }
    }
    DualLiveRenderer(int width,int height,int turn,boolean effects)throws Exception {
        this.effects=effects;sourceWidth=width;sourceHeight=height;sourceTurn=turn;
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("EGL init");
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0);config=configs[0];
        context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        bootstrap=EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        if(!EGL14.eglMakeCurrent(display,bootstrap,bootstrap,context))throw new IllegalStateException("EGL context");
        vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        String fragment=PLAIN;
        if(effects){
            if(!LiveMirrorWindowProbe.FOLD_FRAG.contains("p=toScreen(p);vec2 extent=screen/max(screen.x,screen.y);")||!LiveMirrorWindowProbe.FOLD_FRAG.contains("return vec4(color*alpha,alpha);"))throw new IllegalStateException("Review experiment adaptation after fold shader changes");
            fragment=LiveMirrorWindowProbe.FOLD_FRAG
                .replace("uniform vec2 screen;","uniform vec2 screen,sourceExtent,sourceFit;")
                .replace("varying vec2 uv;","varying vec2 uv;"+ORIENT)
                .replace("uv*canvasSize/screen:uv","uv*canvasSize/outputScreen:uv")
                .replace("vec2 u=canonical(p);","p=orient(p);vec2 u=canonical(p);")
                .replace("p=toScreen(p);vec2 extent=screen/max(screen.x,screen.y);","p=toScreen(p);p=(p-.5)/sourceFit+.5;if(p.x<0.||p.y<0.||p.x>1.||p.y>1.)return vec3(.015,.02,.025);vec2 extent=sourceExtent;")
                .replace("return vec4(color*alpha,alpha);","return vec4(mix(at(u,0.),color,alpha),1.);");
        }
        program=LiveBlurPyramid.program(fragment);
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);sourceTexture=ids[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,sourceTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        callbacks.start();texture=new SurfaceTexture(sourceTexture);texture.setDefaultBufferSize(SIZE,SIZE);texture.setOnFrameAvailableListener(t->available.set(true),new Handler(callbacks.getLooper()));input=new Surface(texture);
        mirror=(VirtualDisplay)DisplayManager.class.getMethod("createVirtualDisplay",String.class,int.class,int.class,int.class,Surface.class).invoke(null,"Glass bounded dual live capture",SIZE,SIZE,0,input);
        if(mirror==null)throw new IllegalStateException("Capture unavailable");
        if(effects)pyramid=new LiveBlurPyramid(SIZE,vertices);
    }
    boolean update(){
        EGL14.eglMakeCurrent(display,bootstrap,bootstrap,context);
        if(!available.getAndSet(false))return false;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);texture.updateTexImage();texture.getTransformMatrix(matrix);sourceTimestamp=texture.getTimestamp();frames++;hasFrame=true;
        if(effects)pyramid.update(sourceTexture,matrix,sourceWidth,sourceHeight,sourceTurn);
        return true;
    }
    void rebindSource(int width,int height,int turn)throws Exception {
        // The mirror follows logical display 0. Recreate its producer on a native
        // role swap so an old-layout frame is never interpreted as the new crop.
        if(mirror!=null){mirror.release();mirror=null;}
        EGL14.eglMakeCurrent(display,bootstrap,bootstrap,context);
        texture.setOnFrameAvailableListener(null);input.release();texture.release();
        available.set(false);hasFrame=false;
        sourceWidth=width;sourceHeight=height;sourceTurn=turn;
        texture=new SurfaceTexture(sourceTexture);texture.setDefaultBufferSize(SIZE,SIZE);
        texture.setOnFrameAvailableListener(t->available.set(true),new Handler(callbacks.getLooper()));input=new Surface(texture);
        mirror=(VirtualDisplay)DisplayManager.class.getMethod("createVirtualDisplay",String.class,int.class,int.class,int.class,Surface.class).invoke(null,"Glass bounded dual live capture",SIZE,SIZE,0,input);
        if(mirror==null)throw new IllegalStateException("Capture rebind unavailable");
    }
    void freezeFrame(){
        if(!hasFrame||effects)throw new IllegalStateException("Snapshot requires a plain prepared frame");
        EGL14.eglMakeCurrent(display,bootstrap,bootstrap,context);
        if(snapshotTexture!=0)throw new IllegalStateException("Snapshot already captured");
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);snapshotTexture=ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,snapshotTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,SIZE,SIZE,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1,ids,0);int fbo=ids[0];GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,snapshotTexture,0);
        int copy=0;
        try{
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Snapshot framebuffer");
            // Shared vertex shader emits top-origin UVs; the FBO texture uses
            // bottom-origin coordinates. Preserve the original sampler mapping.
            copy=LiveBlurPyramid.program("#extension GL_OES_EGL_image_external : require\nprecision highp float;uniform samplerExternalOES source;uniform mat4 tex;varying vec2 uv;void main(){gl_FragColor=texture2D(source,(tex*vec4(uv.x,1.-uv.y,0.,1.)).xy);}");
            GLES20.glUseProgram(copy);GLES20.glViewport(0,0,SIZE,SIZE);GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,sourceTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(copy,"source"),0);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copy,"tex"),1,false,matrix,0);
            int pos=GLES20.glGetAttribLocation(copy,"pos");vertices.position(0);GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("Snapshot GL copy");
        }finally{GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glDeleteFramebuffers(1,new int[]{fbo},0);if(copy!=0)GLES20.glDeleteProgram(copy);}
        snapshotProgram=LiveBlurPyramid.program(PLAIN.replace("#extension GL_OES_EGL_image_external : require\n","").replace("samplerExternalOES","sampler2D"));
        snapshotWidth=sourceWidth;snapshotHeight=sourceHeight;
    }
    void draw(Output output,Bundle settings){drawFrame(output,settings,false);}
    void drawSnapshot(Output output){drawFrame(output,new Bundle(),true);}
    void drawFrame(Output output,Bundle settings,boolean frozen){
        if(frozen?snapshotTexture==0:!hasFrame)throw new IllegalStateException("No prepared source frame");
        int activeProgram=frozen?snapshotProgram:program;
        EGL14.eglMakeCurrent(display,output.window,output.window,context);EGL14.eglSwapInterval(display,0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glViewport(0,0,SIZE,SIZE);GLES20.glDisable(GLES20.GL_BLEND);
        if(effects)pyramid.bind(program,SIZE,SIZE);else{
            GLES20.glUseProgram(activeProgram);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(frozen?GLES20.GL_TEXTURE_2D:GLES11Ext.GL_TEXTURE_EXTERNAL_OES,frozen?snapshotTexture:sourceTexture);GLES20.glUniform1i(GLES20.glGetUniformLocation(activeProgram,"source"),0);
            float[] transform=matrix;if(frozen){transform=new float[16];android.opengl.Matrix.setIdentityM(transform,0);}
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(activeProgram,"tex"),1,false,transform,0);
            int pos=GLES20.glGetAttribLocation(activeProgram,"pos");vertices.position(0);GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices);
        }
        float cropX=!frozen&&rightHalfMirror&&!output.inner?.5f:0f,cropW=1f-cropX;
        float regionX=rightHalfMirror&&output.inner?.5f:0f,regionW=1f-regionX;
        int sourceW=frozen?snapshotWidth:sourceWidth,sourceH=frozen?snapshotHeight:sourceHeight;
        float longest=Math.max(sourceW,sourceH),ratio=sourceW*cropW/sourceH,target=output.viewWidth*regionW/output.viewHeight;
        GLES20.glUniform2f(GLES20.glGetUniformLocation(activeProgram,"sourceExtent"),sourceW/longest,sourceH/longest);
        // Center-crop inside the selected half to fill without stretching icons.
        GLES20.glUniform2f(GLES20.glGetUniformLocation(activeProgram,"sourceFit"),rightHalfMirror?(ratio>target?ratio/target:1f):(ratio<target?ratio/target:1f),rightHalfMirror?(ratio>target?1f:target/ratio):(ratio<target?1f:target/ratio));
        GLES20.glUniform4f(GLES20.glGetUniformLocation(activeProgram,"sourceCrop"),cropX,0,cropW,1);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(activeProgram,"outputRegion"),regionX,0,regionW,1);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(activeProgram,"screen"),output.viewWidth,output.viewHeight);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(activeProgram,"outputScreen"),output.width,output.height);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(activeProgram,"outputTurn"),output.outputTurn);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(activeProgram,"canvasSize"),SIZE*2);
        if(effects){
            long now=SystemClock.uptimeMillis();float angle=settings.getFloat("angle");
            eased=ProjectionMath.followAngle(eased,angle,lastDraw==0?16:now-lastDraw);lastDraw=now;
            boolean blocked=settings.getBoolean("projectionBlocked");int start=settings.getInt("startAngle",1);
            float tilt=ProjectionMath.effectTilt(eased,output.inner,start,blocked);
            uniform("tilt",(float)Math.toRadians(tilt));uniform("crop",ProjectionMath.cropFraction(eased,output.inner,settings.getInt("stretchPercent",100))*ProjectionMath.openingAmount(eased,start,blocked));
            uniform("inner",output.inner?1:0);uniform("turn",ProjectionMath.turn(output.inner,output.viewRotation));
            uniform("opacity",ProjectionMath.endpointOpacity(eased,output.inner,start,blocked));float strength=settings.getFloat("blurStrength",1);uniform("blurStrength",Float.isFinite(strength)?Math.max(0,Math.min(2,strength)):1);
            uniform("spillFraction",ProjectionMath.INNER_SPILL_FRACTION);uniform("hingeDistanceFraction",ProjectionMath.OUTER_HINGE_DISTANCE_FRACTION);
            uniform("screenDarkness",0);uniform("screenFadeActive",0);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        if(!EGL14.eglSwapBuffers(display,output.window))throw new IllegalStateException("Output swap");
    }
    void uniform(String name,float value){GLES20.glUniform1f(GLES20.glGetUniformLocation(program,name),value);}
    public void close(){
        EGL14.eglMakeCurrent(display,bootstrap,bootstrap,context);
        if(mirror!=null)mirror.release();if(input!=null)input.release();if(texture!=null)texture.release();callbacks.quitSafely();
        if(pyramid!=null)pyramid.close();if(program!=0)GLES20.glDeleteProgram(program);GLES20.glDeleteTextures(1,new int[]{sourceTexture},0);
        if(snapshotProgram!=0)GLES20.glDeleteProgram(snapshotProgram);if(snapshotTexture!=0)GLES20.glDeleteTextures(1,new int[]{snapshotTexture},0);
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(display,bootstrap);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);
    }
}
