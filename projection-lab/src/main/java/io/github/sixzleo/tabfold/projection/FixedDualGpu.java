package io.github.sixzleo.tabfold.projection;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.*;
import android.view.Surface;
import java.nio.*;

/** Original live fold shader and Gaussian pipeline fed by an independent task surface. */
final class FixedDualGpu implements AutoCloseable {
    static volatile String innerStats="idle",coverStats="idle";
    private final HandlerThread thread=new HandlerThread("Duo-live-fold");
    private final Handler worker,main=new Handler(Looper.getMainLooper());
    private final int width,height;private final boolean inner;
    private final java.util.function.Consumer<String> failed;
    private volatile boolean closed;
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;private EGLContext context=EGL14.EGL_NO_CONTEXT;private EGLSurface window=EGL14.EGL_NO_SURFACE;
    private SurfaceTexture source;private Surface input;private FixedDualGpuPyramid pyramid;private int external,program;
    private boolean dirty,hasFrame,pyramidDirty;private float lastTilt=Float.NaN,lastCrop,lastOpacity,lastStrength;
    private long sourceFrames,pyramidUpdates,presentedFrames,statsAt;
    private final float[] matrix=new float[16];
    private final ProjectionAngleMotion motion=new ProjectionAngleMotion();
    private final ProjectionEntrance entrance=new ProjectionEntrance();
    private long started;
    FixedDualGpu(Surface output,int width,int height,boolean inner,java.util.function.Consumer<Surface> ready,java.util.function.Consumer<String> failed){
        this.width=width;this.height=height;this.inner=inner;this.failed=failed;thread.start();worker=new Handler(thread.getLooper());
        worker.post(()->{try{init(output);main.post(()->{if(!closed)ready.accept(input);});worker.post(draw);}catch(Exception e){error(e);}});
    }
    private void init(Surface output){
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("eglInitialize");
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0);
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        window=EGL14.eglCreateWindowSurface(display,configs[0],output,new int[]{EGL14.EGL_NONE},0);
        if(!EGL14.eglMakeCurrent(display,window,window,context))throw new IllegalStateException("eglMakeCurrent");
        program=FixedDualGpuPyramid.program(FixedDualGpuProgram.FOLD_FRAG);
        int[] names=new int[1];GLES20.glGenTextures(1,names,0);external=names[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,external);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        source=new SurfaceTexture(external);source.setDefaultBufferSize(width,height);source.setOnFrameAvailableListener(s->dirty=true,worker);input=new Surface(source);
        FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        pyramid=new FixedDualGpuPyramid(Math.max(width,height)/2,vertices);
        GLES20.glUseProgram(program);uniform("canvasSize",0);uniform("spillFraction",ProjectionMath.INNER_SPILL_FRACTION);uniform("hingeDistanceFraction",ProjectionMath.OUTER_HINGE_DISTANCE_FRACTION);
        uniform("inner",inner?1:0);uniform("turn",0);uniform("screenDarkness",0);
        // The original compositor's source-backed path includes the clear receiving half.
        uniform("screenFadeActive",1);GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"screen"),width,height);started=SystemClock.uptimeMillis();
    }
    private void uniform(String name,float value){GLES20.glUniform1f(GLES20.glGetUniformLocation(program,name),value);}
    private final Runnable draw=new Runnable(){public void run(){
        if(closed)return;
        try{
            boolean changed=dirty;dirty=false;
            if(changed){GLES20.glActiveTexture(GLES20.GL_TEXTURE7);source.updateTexImage();source.getTransformMatrix(matrix);hasFrame=true;pyramidDirty=true;sourceFrames++;}
            if(hasFrame){
                long now=SystemClock.uptimeMillis();FoldPose pose=ProjectionService.foldPose.expireDirectContact(SystemClock.elapsedRealtimeNanos());
                boolean blocked=ProjectionAngleMotion.hardBlocked(pose.blocksProjection(),pose.fullyOpened(),inner);
                float eased=motion.update(now,pose.angle(),inner,pose.blocksProjection(),pose.fullyOpened(),false);
                float endpoint=ProjectionMath.endpointOpacity(eased,inner,AnimationSettings.startAngle,blocked);
                boolean visible=ProjectionMath.endpointOpacity(inner?eased:pose.angle(),inner,AnimationSettings.startAngle,blocked)>0;
                float entry=entrance.update(now,visible,false,started,inner?180:80);
                float amount=ProjectionMath.onsetMotion(endpoint,entry);
                float tilt=ProjectionMath.effectTilt(eased,inner,AnimationSettings.startAngle,blocked)*amount;
                float crop=ProjectionMath.cropFraction(eased,inner,AnimationSettings.stretchPercent)*amount;
                float opacity=ProjectionMath.onsetOpacity(endpoint),strength=AnimationSettings.blurPercent/100f;
                // The shader samples only nativeSource when opacity is below .001,
                // tilt is zero, or blurStrength is zero. Keep the old pyramid dirty:
                // a later fold must refresh it even when the source did not change.
                boolean needsPyramid=opacity>=.001f&&tilt!=0f&&strength>0f;
                if(needsPyramid&&pyramidDirty){pyramid.update(external,matrix,width,height,0);pyramidDirty=false;pyramidUpdates++;}
                if(changed||!Float.isFinite(lastTilt)||Math.abs(tilt-lastTilt)>.002f||Math.abs(crop-lastCrop)>.00001f||opacity!=lastOpacity||strength!=lastStrength){
                    pyramid.bind(program,width,height);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE7);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,external);
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"nativeSource"),7);
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"nativeTex"),1,false,matrix,0);
                    uniform("tilt",(float)Math.toRadians(tilt));uniform("crop",crop);uniform("opacity",opacity);uniform("blurStrength",strength);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                    if(!EGL14.eglSwapBuffers(display,window))throw new IllegalStateException("Present failed");
                    presentedFrames++;
                    lastTilt=tilt;lastCrop=crop;lastOpacity=opacity;lastStrength=strength;
                }
                if(now-statsAt>=1000){statsAt=now;String stats="source="+sourceFrames+" pyramid="+pyramidUpdates+" presented="+presentedFrames+" tilt="+tilt+" needsBlur="+needsPyramid;if(inner)innerStats=stats;else coverStats=stats;}
            }
            worker.postDelayed(this,8);
        }catch(Exception e){error(e);}
    }};
    private void error(Exception e){android.util.Log.e("DuoFixed","Original fold renderer",e);main.post(()->failed.accept(e.toString()));close();}
    @Override public void close(){if(closed)return;closed=true;worker.removeCallbacksAndMessages(null);worker.post(()->{
        if(input!=null)input.release();if(source!=null)source.release();if(pyramid!=null)pyramid.close();
        if(program!=0)GLES20.glDeleteProgram(program);if(external!=0)GLES20.glDeleteTextures(1,new int[]{external},0);
        if(display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}
        thread.quitSafely();
    });}
}
