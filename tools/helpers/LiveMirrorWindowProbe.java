package io.github.sixzleo.tabfold.probe;

import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.opengl.*;
import android.os.*;
import android.view.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ten-second local GPU mirror preview. Own window only, excluded from capture. */
public final class LiveMirrorWindowProbe {
    static Object am,provider;
    static IBinder providerToken;
    static java.lang.reflect.Method providerCall;
    static final String AUTHORITY="io.github.sixzleo.tabfold.projection.surface";
    static Surface leasedOutput;
    static SurfaceControl leasedControl;
    static volatile OutputOwnerGuard ownerGuard;
    static int leasedCanvasSize;
    static Handler main;
    static volatile boolean stopped;
    static volatile boolean failed;
    static final AtomicBoolean finishing=new AtomicBoolean();
    static boolean foldMode;
    static boolean continuous;
    static volatile long progressAt;
    static long renewAt;
    static final RenderWakeSignal renderWake=new RenderWakeSignal();
    static boolean wakeNotifications;
    static boolean pushedFrames;
    static final RenderFrameCache frameCache=new RenderFrameCache();
    static java.nio.channels.FileLock processLock;
    static java.io.RandomAccessFile lockFile;
    static int durationMs=10000;
    static final String VERT="attribute vec2 pos; varying vec2 uv; void main(){gl_Position=vec4(pos,0.,1.);uv=vec2((pos.x+1.)*.5,(1.-pos.y)*.5);}";
    static final String FRAG="#extension GL_OES_EGL_image_external : require\nprecision highp float; uniform samplerExternalOES source; uniform mat4 tex; uniform float tilt; varying vec2 uv;\n"
        +"vec3 sampleAt(vec2 p){if(p.x<0.||p.y<0.||p.x>1.||p.y>1.)return vec3(0.);return texture2D(source,(tex*vec4(p.x,1.-p.y,0.,1.)).xy).rgb;}\n"
        +"void main(){float x=uv.x*.073;float t=x*sin(tilt);vec2 q=uv;"
        +"float s=min(.008,max(t,0.)*.35);vec3 c=vec3(0.);float wsum=0.;for(int iy=-2;iy<=2;iy++){for(int ix=-2;ix<=2;ix++){float w=exp(-.5*float(ix*ix+iy*iy));c+=pow(sampleAt(q+vec2(float(ix),float(iy)*.4264)*s),vec3(2.2))*w;wsum+=w;}}"
        +"c=pow(c/wsum,vec3(1./2.2));if(uv.x<.006||uv.x>.994||uv.y<.004||uv.y>.996)c=vec3(.1,.9,.8);gl_FragColor=vec4(c,1.);}";
    static final String FOLD_FRAG="precision highp float;uniform sampler2D level0,level1,level2,level3,level4,level5,level6;uniform float bufferSize,contentScale,canvasSize,contentFraction,bandMix,bandClear;uniform float tilt,crop,inner,turn,opacity,blurStrength,spillFraction,hingeDistanceFraction,screenDarkness,screenFadeActive;uniform vec2 screen;varying vec2 uv;"
        +"vec2 canonical(vec2 p){if(turn<.5)return p;if(turn<1.5)return vec2(1.-p.y,p.x);if(turn<2.5)return vec2(1.-p.x,1.-p.y);return vec2(p.y,1.-p.x);}"
        +"vec2 toScreen(vec2 p){if(turn<.5)return p;if(turn<1.5)return vec2(p.y,1.-p.x);if(turn<2.5)return vec2(1.-p.x,1.-p.y);return vec2(1.-p.y,p.x);}"
        +"vec3 layer(vec2 p,float l){vec3 c;if(l<.5)c=texture2D(level0,p).rgb;else if(l<1.5)c=texture2D(level1,p).rgb;else if(l<2.5)c=texture2D(level2,p).rgb;else if(l<3.5)c=texture2D(level3,p).rgb;else if(l<4.5)c=texture2D(level4,p).rgb;else if(l<5.5)c=texture2D(level5,p).rgb;else c=texture2D(level6,p).rgb;return pow(c,vec3(2.2));}"
        +"vec3 at(vec2 p,float sigma){p=toScreen(p);float y=p.y;float cf=contentFraction;float yS=y>cf?cf+(cf-y)*bandMix:y;p=vec2(p.x,yS/max(cf,.0001));vec2 extent=screen/max(screen.x,screen.y);p=(1.-extent)*.5+p*extent;p=(1.-contentScale)*.5+p*contentScale;p.y=1.-p.y;float variance=sigma*sigma;float l=clamp(.5*log2(1.+3.*variance/2.854),0.,6.);float lo=floor(l);float a=2.854*(pow(4.,lo)-1.)/3.;float b=2.854*(pow(4.,min(lo+1.,6.))-1.)/3.;float f=clamp((variance-a)/max(b-a,.0001),0.,1.);return pow(mix(layer(p,lo),layer(p,min(lo+1.,6.)),f),vec3(1./2.2));}"
        +"vec4 composite(vec3 color,float alpha,vec2 u){float cf=mix(1.,contentFraction,bandMix);float strip=clamp((toScreen(u).y-cf)/max(1.-cf,.0001),0.,1.);float bandA=1.-strip*bandClear;float dim=1.-(.22+.4*strip)*strip;color*=dim;if(screenFadeActive>.5){vec3 c=mix(at(u,0.),color,alpha)*(1.-screenDarkness);return vec4(c*dim*bandA,bandA);}return vec4(color*alpha*bandA,alpha*bandA);}"
        +"void main(){if(screenDarkness>.999){gl_FragColor=vec4(0.,0.,0.,1.);return;}vec2 p=canvasSize>0.?uv*canvasSize/screen:uv;if(p.x>1.||p.y>1.){gl_FragColor=vec4(0.);return;}vec2 u=canonical(p);if(opacity<.001){gl_FragColor=composite(vec3(0.),0.,u);return;}float W=inner>.5?.146:.073;float x=(u.x-(inner>.5?.5:0.))*W;float y=(u.y-.5)*.16;float spill=inner>.5?.073*spillFraction*sin(tilt):0.;if(inner>.5&&x>=0.&&(spill<=.000001||x>=spill)){gl_FragColor=composite(vec3(0.),0.,u);return;}float coverage=inner>.5&&x>0.?1.-smoothstep(0.,max(spill,.000001),x):1.;if(tilt==0.&&crop==0.){gl_FragColor=composite(at(u,0.),opacity*coverage,u);return;}float paperX=inner>.5?min(x,0.):x;vec3 P=vec3(paperX*cos(tilt),y,abs(paperX)*sin(tilt));vec3 D=normalize(P-vec3(inner>.5?0.:.0365,0.,.6));float t=-P.z/min(D.z,-.0001);vec3 Q=P+t*D;vec2 paperQ=vec2(Q.x/W+(inner>.5?.5:0.),Q.y/.16+.5);float a=clamp(abs(x)/.073,0.,1.);vec2 q=inner>.5&&x>=0.?u:vec2((inner>.5?.5:0.)+x*(1.-crop*a)/W,u.y);float blurGap=t;if(inner>.5){float d=max(spill-x,0.)/(1.+spill/.073);vec3 B=vec3(-d*cos(tilt),y,d*sin(tilt));blurGap=length(B-vec3(0.,0.,.6))*B.z/(.6-B.z);}else{float d=.073*hingeDistanceFraction+abs(x)*(1.-hingeDistanceFraction);vec3 B=vec3(d*cos(tilt),y,d*sin(tilt));blurGap=length(B-vec3(.0365,0.,.6))*B.z/(.6-B.z);}"
        +"float s=min(.03,max(blurGap,0.)*(inner>.5?.55:.75));vec2 canonicalSize=mod(turn,2.)<.5?screen:screen.yx;float sigma=s*canonicalSize.x/max(screen.x,screen.y)*bufferSize*contentScale*blurStrength;"
        // Only top/bottom contours recede; rounded corners meet the pinned sides.
        +"vec2 pixelScale=canonicalSize/max(screen.x,screen.y)*bufferSize*contentScale;float edgeY=min(paperQ.y,1.-paperQ.y)*pixelScale.y;float sideDistance=min(u.x,1.-u.x)*pixelScale.x;float leafPixels=.073/W*pixelScale.x;float radius=leafPixels*.055*sin(tilt);float cornerX=max(radius-sideDistance,0.);float cornerInset=radius-sqrt(max(radius*radius-cornerX*cornerX,0.));float boundary=cornerInset-edgeY;"
        +"float band=max(leafPixels*.18,1.);float inward=max(-boundary,0.)/band;float influence=exp(-.5*inward*inward);float edgeSigma=leafPixels*.025*sin(tilt)*blurStrength*coverage;sigma*=1.4;float baseVariance=sigma*sigma;float edgeVariance=edgeSigma*edgeSigma;float paperSigma=min(62.,sqrt(baseVariance+edgeVariance));sigma=min(62.,sqrt(baseVariance+edgeVariance*influence*influence));vec3 c=at(q,sigma);float feather=max(paperSigma*3.2,.5);float paper=mix(1.,smoothstep(-feather,feather,-boundary),coverage);c*=paper;"
        +"float alpha=opacity*coverage;gl_FragColor=composite(c,alpha,u);}";
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();main=new Handler(Looper.getMainLooper());
            continuous=args.length>0&&"live".equals(args[0]);
            foldMode=continuous||(args.length>0&&"fold".equals(args[0]));
            if(continuous){
                lockFile=new java.io.RandomAccessFile("/data/local/tmp/tabfold-live.lock","rw");
                processLock=lockFile.getChannel().tryLock();
                if(processLock==null){System.out.println("Already running");System.exit(0);return;}
            }else if(foldMode)durationMs=args.length>1?Math.max(5,Math.min(55,Integer.parseInt(args[1])))*1000:50000;
            Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);providerToken=new Binder();
            Object holder=Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class).invoke(am,AUTHORITY,0,providerToken,"LiveMirrorPreview");
            if(holder==null)throw new IllegalStateException("No projection provider");
            provider=holder.getClass().getField("provider").get(holder);
            providerCall=Class.forName("android.content.IContentProvider").getMethod("call",android.content.AttributionSource.class,String.class,String.class,String.class,Bundle.class);
            if(foldMode){
                Bundle subscription=new Bundle();
                subscription.putBinder("listener",new Messenger(new Handler(Looper.getMainLooper(),message->{
                    Bundle update=message.peekData();if(update!=null&&update.containsKey("_revision"))frameCache.accept(update,true);
                    renderWake.signal();return true;
                })).getBinder());
                Bundle reply=call("mirror-listen",null,subscription);
                wakeNotifications=reply.getBoolean("supported");pushedFrames=reply.getBoolean("pushFrames");
            }
            if(continuous){
                progressAt=SystemClock.uptimeMillis();
                new Thread(LiveMirrorWindowProbe::runContinuous,"mirror-supervisor").start();
                main.postDelayed(new Runnable(){public void run(){
                    if(new java.io.File("/data/local/tmp/tabfold-live.stop").exists()||SystemClock.uptimeMillis()-progressAt>5000){stopped=true;finish();}
                    else main.postDelayed(this,500);
                }},500);
                Looper.loop();return;
            }
            call(foldMode?"mirror-fold":"mirror-preview",foldMode?"60":"15");
            Bundle lease=null;
            long deadline=SystemClock.uptimeMillis()+2000;
            while(SystemClock.uptimeMillis()<deadline){lease=call("mirror-lease",null);leasedOutput=lease.getParcelable("surface");if(leasedOutput!=null)break;Thread.sleep(20);}
            if(leasedOutput==null)throw new IllegalStateException("Desktop service/preview unavailable");
            leasedControl=lease.getParcelable("control");
            guardOutput(lease);
            leasedCanvasSize=lease.getInt("canvasSize");
            try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
                SurfaceControl.Transaction.class.getMethod("setSkipScreenshot",SurfaceControl.class,boolean.class).invoke(t,leasedControl,true);
                    SurfaceControl black=lease.getParcelable("blackoutControl");
                    if(black!=null){
                        SurfaceControl.Transaction.class.getMethod("setSkipScreenshot",SurfaceControl.class,boolean.class).invoke(t,black,true);
                        SurfaceControl.Transaction.class.getMethod("setRelativeLayer",SurfaceControl.class,SurfaceControl.class,int.class).invoke(t,black,leasedControl,-1);
                        black.release();
                    }
                    t.apply();
            }
            call("mirror-blackout-prepared",Long.toString(lease.getLong("blackoutId")));
            final int width=lease.getInt("width"),height=lease.getInt("height");
            new Thread(()->render(leasedOutput,width,height),"mirror-gpu").start();
            main.postDelayed(()->{stopped=true;finish();},durationMs+3000);
            Looper.loop();
        }catch(Throwable error){failed=true;error.printStackTrace();finish();}
    }
    static Bundle call(String method,String arg)throws Exception {
        return call(method,arg,null);
    }
    static Bundle call(String method,String arg,Bundle extras)throws Exception {
        return (Bundle)providerCall.invoke(provider,new android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build(),AUTHORITY,method,arg,extras);
    }
    static void renew()throws Exception{
        long now=SystemClock.uptimeMillis();progressAt=now;
        if(now>=renewAt){call("mirror-live","1");renewAt=now+500;}
    }
    static synchronized void releaseOutput(){
        OutputOwnerGuard guard=ownerGuard;ownerGuard=null;if(guard!=null)guard.close();
        if(leasedControl!=null){leasedControl.release();leasedControl=null;}
        if(leasedOutput!=null){leasedOutput.release();leasedOutput=null;}
    }
    static void guardOutput(Bundle lease)throws RemoteException {
        SurfaceControl root=lease.getParcelable("rootControl");IBinder owner=lease.getBinder("ownerToken");
        if(root!=null&&owner!=null)ownerGuard=new OutputOwnerGuard(owner,root,()->stopped=true);
        else if(root!=null)root.release();
    }
    static void runContinuous(){
        try{
            while(!stopped){
                renew();Bundle lease=call("mirror-lease",null);
                leasedOutput=lease.getParcelable("surface");
                if(leasedOutput==null){Thread.sleep(100);continue;}
                leasedControl=lease.getParcelable("control");
                guardOutput(lease);
                leasedCanvasSize=lease.getInt("canvasSize");
                try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
                    SurfaceControl.Transaction.class.getMethod("setSkipScreenshot",SurfaceControl.class,boolean.class).invoke(t,leasedControl,true);
                    SurfaceControl black=lease.getParcelable("blackoutControl");
                    if(black!=null){
                        SurfaceControl.Transaction.class.getMethod("setSkipScreenshot",SurfaceControl.class,boolean.class).invoke(t,black,true);
                        SurfaceControl.Transaction.class.getMethod("setRelativeLayer",SurfaceControl.class,SurfaceControl.class,int.class).invoke(t,black,leasedControl,-1);
                        black.release();
                    }
                    t.apply();
                }
                call("mirror-blackout-prepared",Long.toString(lease.getLong("blackoutId")));
                render(leasedOutput,lease.getInt("width"),lease.getInt("height"));
                releaseOutput();
                if(failed)break;
                Thread.sleep(100);
            }
        }catch(Throwable error){failed=true;error.printStackTrace();}
        finally{finish();}
    }
    static void finish(){
        stopped=true;
        if(!finishing.compareAndSet(false,true))return;
        OutputOwnerGuard guard=ownerGuard;if(guard!=null)guard.detach();
        if(main==null){System.exit(1);return;}
        try{
            if(providerCall!=null)call(continuous?"mirror-live":foldMode?"mirror-fold":"mirror-preview","0");
            if(providerToken!=null)Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class).invoke(am,AUTHORITY,providerToken,0);
        }catch(Throwable e){e.printStackTrace();}
        releaseOutput();
        System.out.println("CLEANED preview window");System.exit(failed?1:0);
    }
    static int shader(int type,String code){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,code);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));return s;}
    static void render(Surface output,int width,int height){
        EGLDisplay display=EGL14.EGL_NO_DISPLAY;EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface window=EGL14.EGL_NO_SURFACE;
        VirtualDisplay mirror=null;Surface input=null;SurfaceTexture texture=null;
        LiveBlurPyramid pyramid=null;
        try {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
            if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("eglInitialize");
            EGLConfig[] configs=new EGLConfig[1];int[] n=new int[1];
            EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,n,0);
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            window=EGL14.eglCreateWindowSurface(display,configs[0],output,new int[]{EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(display,window,window,context))throw new IllegalStateException("eglMakeCurrent");
            int program=GLES20.glCreateProgram();GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,VERT));GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,foldMode?FOLD_FRAG:FRAG));GLES20.glLinkProgram(program);
            int[] linked=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);if(linked[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            int uTilt=GLES20.glGetUniformLocation(program,"tilt"),uCrop=GLES20.glGetUniformLocation(program,"crop");
            int uInner=GLES20.glGetUniformLocation(program,"inner"),uTurn=GLES20.glGetUniformLocation(program,"turn");
            int uOpacity=GLES20.glGetUniformLocation(program,"opacity"),uStrength=GLES20.glGetUniformLocation(program,"blurStrength");
            int uScreen=GLES20.glGetUniformLocation(program,"screen"),uDarkness=GLES20.glGetUniformLocation(program,"screenDarkness");
            int uFade=GLES20.glGetUniformLocation(program,"screenFadeActive"),uTex=GLES20.glGetUniformLocation(program,"tex");
            GLES20.glUseProgram(program);int[] textures=new int[1];GLES20.glGenTextures(1,textures,0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            texture=new SurfaceTexture(textures[0]);texture.setDefaultBufferSize(width,height);AtomicBoolean ready=new AtomicBoolean();texture.setOnFrameAvailableListener(t->{ready.set(true);renderWake.signal();},main);input=new Surface(texture);
            long start=SystemClock.uptimeMillis();
            mirror=(VirtualDisplay)DisplayManager.class.getMethod("createVirtualDisplay",String.class,int.class,int.class,int.class,Surface.class).invoke(null,"TabFold-live-gpu-preview",width,height,0,input);
            FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
            if(foldMode)pyramid=new LiveBlurPyramid(width,vertices);
            GLES20.glUseProgram(program);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"canvasSize"),leasedCanvasSize);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"contentFraction"),1f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"spillFraction"),io.github.sixzleo.tabfold.projection.ProjectionMath.INNER_SPILL_FRACTION);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"hingeDistanceFraction"),io.github.sixzleo.tabfold.projection.ProjectionMath.OUTER_HINGE_DISTANCE_FRACTION);
            int pos=GLES20.glGetAttribLocation(program,"pos");GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"source"),0);GLES20.glViewport(0,0,width,height);
            int frames=0,sourceFrames=0;float[] matrix=new float[16];boolean saved=false,hasTexture=false;
            long pollAt=0;Bundle geometry=null;String previousGeometry="",previousDisplay="",pyramidGeometry="";float eased=Float.NaN;
            FoldReturnMotion returnMotion=new FoldReturnMotion();
            io.github.sixzleo.tabfold.projection.ProjectionEntrance entrance=new io.github.sixzleo.tabfold.projection.ProjectionEntrance();
            io.github.sixzleo.tabfold.projection.ProjectionAngleMotion angleMotion=new io.github.sixzleo.tabfold.projection.ProjectionAngleMotion();
            boolean wasFullyOpened=false;
            io.github.sixzleo.tabfold.projection.CoverLayoutReady coverLayout=new io.github.sixzleo.tabfold.projection.CoverLayoutReady();
            float previousEntrance=0;
            boolean wasHeld=false,returnComplete=false;
            RenderIdleGate idleGate=new RenderIdleGate();boolean sourceAttached=true;
            RenderDrawGate drawGate=new RenderDrawGate();int reusedFrames=0,lastReused=0;long lastPushes=frameCache.pushes();
            int pyramidUpdates=0,telemetryPolls=0,lastFrames=0,lastSources=0,lastPyramids=0,lastPolls=0;
            long reportAt=SystemClock.uptimeMillis(),captureCheckAt=0;
            java.io.File captureRequest=new java.io.File("/data/local/tmp/tabfold-live.capture");
            while(!stopped&&(continuous||SystemClock.uptimeMillis()-start<durationMs)){
                long now=SystemClock.uptimeMillis();
                long observedWake=renderWake.version();
                if(now-reportAt>=10000){
                    System.out.println("PERF windowMs="+(now-reportAt)+" presents="+(frames-lastFrames)+" sourceFrames="+(sourceFrames-lastSources)+" pyramids="+(pyramidUpdates-lastPyramids)+" polls="+(telemetryPolls-lastPolls)+" pushes="+(frameCache.pushes()-lastPushes)+" reused="+(reusedFrames-lastReused)+" captureAttached="+sourceAttached);
                    lastPushes=frameCache.pushes();lastReused=reusedFrames;
                    reportAt=now;lastFrames=frames;lastSources=sourceFrames;lastPyramids=pyramidUpdates;lastPolls=telemetryPolls;
                }
                boolean captureNow=false;
                if(continuous&&now>=captureCheckAt){captureCheckAt=now+250;captureNow=captureRequest.exists()&&captureRequest.delete();}
                String revealRequest=null;
                if(continuous)renew();
                if(foldMode){
                    if(pushedFrames){
                        if(now>=pollAt||frameCache.needsFull()){
                            long phaseAt=SystemClock.uptimeMillis();frameCache.accept(call("mirror-frame",null),false);
                            telemetryPolls++;pollAt=now+1000;logSlowPhase("telemetry",phaseAt);
                        }
                        geometry=frameCache.snapshot();
                    }else if(now>=pollAt){long phaseAt=SystemClock.uptimeMillis();geometry=call("mirror-frame",null);telemetryPolls++;pollAt=now+12;logSlowPhase("telemetry",phaseAt);}
                    if(geometry==null||!geometry.getBoolean("alive")||!geometry.getBoolean("allowed"))break;
                }
                boolean sourceChanged=ready.getAndSet(false);
                if(foldMode){
                    boolean inner=geometry.getBoolean("inner");int rotation=geometry.getInt("rotation"),sw=geometry.getInt("screenWidth"),sh=geometry.getInt("screenHeight");
                    if(sw<=0||sh<=0)break;
                    int turn=io.github.sixzleo.tabfold.projection.ProjectionMath.turn(inner,rotation);
                    String sourceGeometry=sw+"x"+sh+":"+turn;
                    float angle=geometry.getFloat("angle",Float.NaN);if(!Float.isFinite(angle))break;
                    boolean fullyOpened=geometry.getBoolean("fullyOpened");
                    boolean poseBlocked=geometry.getBoolean("projectionBlocked");
                    boolean physicallyBlocked=io.github.sixzleo.tabfold.projection.ProjectionAngleMotion.hardBlocked(poseBlocked,fullyOpened,inner);
                    String key=sw+"x"+sh+":"+rotation+":"+inner;
                    String displayKey=key+":"+geometry.getInt("state");
                    now=SystemClock.uptimeMillis();
                    if(!displayKey.equals(previousDisplay)){
                        System.out.println("OUTPUT_GEOMETRY elapsedMs="+(now-start)+" "+displayKey+" angle="+angle+" frames="+frames);
                        previousDisplay=displayKey;
                    }
                    boolean sceneChanged=!key.equals(previousGeometry);
                    if(sceneChanged)previousGeometry=key;
                    eased=angleMotion.update(now,angle,inner,poseBlocked,fullyOpened,sceneChanged);
                    if(fullyOpened!=wasFullyOpened){System.out.println("FLAT_"+(fullyOpened?"RETURN":"RESUME")+" renderAngle="+eased+" rawAngle="+geometry.getFloat("rawAngle"));wasFullyOpened=fullyOpened;}
                    int startAngle=geometry.getInt("startAngle",1);
                    float tilt=io.github.sixzleo.tabfold.projection.ProjectionMath.effectTilt(eased,inner,startAngle,physicallyBlocked);
                    float endpoint=io.github.sixzleo.tabfold.projection.ProjectionMath.endpointOpacity(eased,inner,startAngle,physicallyBlocked);
                    boolean visible=io.github.sixzleo.tabfold.projection.ProjectionMath.endpointOpacity(inner?eased:angle,inner,startAngle,physicallyBlocked)>0;
                    long coverToken=geometry.getLong("coverToken");
                    boolean coverReady=coverLayout.update(now,coverToken,geometry.getLong("coverStartedAt"),
                        sw+"x"+sh+":"+rotation,geometry.getInt("state")==Display.STATE_ON,
                        geometry.getBoolean("geometryValid"),sourceChanged,inner?120:450);
                    float entry;
                    boolean waitingForCover=coverToken!=0&&!coverReady;
                    if(waitingForCover){entrance.update(now,false,false);entry=0;}
                    else if(coverReady){
                        // Draw directly above the black backing; readiness never removes that layer.
                        entry=entrance.update(now,visible,true,now-180,inner?180:80);
                        revealRequest=coverToken+":"+sw+"x"+sh+":"+rotation+":"+inner;
                    }else entry=entrance.update(now,visible,sceneChanged,geometry.getLong("sceneStartedAt",now),inner?180:80);
                    if(sceneChanged)System.out.println("HANDOFF_ENTRY sceneAgeMs="+(now-geometry.getLong("sceneStartedAt",now))+" amount="+entry+" state="+geometry.getInt("state"));
                    if(visible&&entry==0&&(sceneChanged||previousEntrance>0))System.out.println("NEUTRAL_ENTRY angle="+angle);
                    if(entry==1&&previousEntrance<1)System.out.println("ENTRY_COMPLETE angle="+angle);
                    previousEntrance=entry;
                    // Flat telemetry resets the hold gate. Keep any visual hold
                    // until folding resumes, so a hidden effect cannot reappear.
                    boolean held=fullyOpened&&inner?wasHeld:geometry.getBoolean("foldHeld");
                    if(held!=wasHeld){System.out.println("HOLD_"+(held?"RETURN_START":"RESUME")+" angle="+angle);wasHeld=held;returnComplete=false;}
                    float amount=returnMotion.update(now,held,physicallyBlocked);
                    if(held&&amount==0&&!returnComplete){System.out.println("HOLD_RETURN_COMPLETE angle="+angle);returnComplete=true;}
                    // Start with an opaque, unshifted copy. Ramp the effect itself;
                    // a translucent shifted copy would expose a second native icon.
                    float motion=io.github.sixzleo.tabfold.projection.ProjectionMath.onsetMotion(endpoint,entry)*amount;
                    tilt*=motion;
                    float opacity=waitingForCover?0:io.github.sixzleo.tabfold.projection.ProjectionMath.onsetOpacity(endpoint)*FoldReturnMotion.coverage(amount);
                    boolean idle=idleGate.update(now,opacity>0,geometry.getBoolean("screenFadeActive"),coverToken,sceneChanged);
                    if(idle){
                        // One transparent buffer removes the effect. No shader,
                        // blur pyramid or repeat presents are needed while clear.
                        if(idleGate.clearFrame||captureNow){
                            drawGate.reset();
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
                            GLES20.glClearColor(0,0,0,0);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                            if(captureNow){saveFrame(width,height);System.out.println("CAPTURED_GPU_FRAME "+previousGeometry+" angle="+eased+" idle=true");}
                            if(!EGL14.eglSwapBuffers(display,window))throw new IllegalStateException("idle clear swap");
                            frames++;
                        }
                        if(sourceAttached&&idleGate.detach(now)){
                            mirror.setSurface(null);sourceAttached=false;
                            System.out.println("CAPTURE_PAUSE angle="+angle+" held="+held);
                        }
                        // A shell-to-app pose change wakes this wait immediately.
                        // The timeout only maintains the lease and catches missed signals.
                        renderWake.await(observedWake,sourceAttached||!wakeNotifications?20:250);
                        if(!pushedFrames)pollAt=0;continue;
                    }
                    if(!sourceAttached){
                        // Drain the old queue, then wait for fresh content from
                        // the reattached display before revealing the projection.
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);texture.updateTexImage();
                        ready.set(false);sourceChanged=false;hasTexture=false;
                        mirror.setSurface(input);sourceAttached=true;
                        System.out.println("CAPTURE_RESUME angle="+angle);
                    }
                    if(sourceChanged){
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE7);
                        long phaseAt=SystemClock.uptimeMillis();
                        texture.updateTexImage();texture.getTransformMatrix(matrix);hasTexture=true;sourceFrames++;
                        logSlowPhase("source-frame",phaseAt);
                    }
                    if(!hasTexture){renderWake.await(observedWake,8);continue;}
                    float crop=io.github.sixzleo.tabfold.projection.ProjectionMath.cropFraction(eased,inner,geometry.getInt("stretchPercent",io.github.sixzleo.tabfold.projection.ProjectionMath.DEFAULT_STRETCH_PERCENT))*motion;
                    float strength=geometry.getFloat("blurStrength",1f);strength=Float.isFinite(strength)?Math.max(0,Math.min(2,strength)):1;
                    float darkness=io.github.sixzleo.tabfold.projection.ScreenFade.sample(now,geometry.getInt("screenFadeMode"),
                        geometry.getLong("screenFadeAt"),geometry.getFloat("screenDarkness"),geometry.getFloat("screenFadeFrom"));
                    boolean fade=geometry.getBoolean("screenFadeActive");
                    if(!drawGate.draw(sourceChanged,sceneChanged||captureNow||revealRequest!=null,sw,sh,rotation,inner,fade,tilt,crop,opacity,darkness,strength)){
                        reusedFrames++;renderWake.await(observedWake,8);continue;
                    }
                    if(sourceChanged||!sourceGeometry.equals(pyramidGeometry)){
                        long phaseAt=SystemClock.uptimeMillis();
                        pyramid.update(textures[0],matrix,sw,sh,turn);pyramid.bind(program,width,height);pyramidUpdates++;
                        logSlowPhase("blur-pyramid",phaseAt);
                        pyramidGeometry=sourceGeometry;
                    }
                    if(drawGate.parametersChanged){
                    GLES20.glUniform1f(uTilt,(float)Math.toRadians(tilt));
                    // Enter from the actual unshifted desktop, then follow linearly.
                    GLES20.glUniform1f(uCrop,crop);
                    GLES20.glUniform1f(uInner,inner?1:0);
                    GLES20.glUniform1f(uTurn,inner?(rotation+1)%4:rotation);
                    GLES20.glUniform1f(uOpacity,opacity);
                    GLES20.glUniform1f(uStrength,strength);
                    GLES20.glUniform2f(uScreen,sw,sh);
                    GLES20.glUniform1f(uDarkness,darkness);
                    GLES20.glUniform1f(uFade,fade?1:0);
                    }
                }else{
                    if(sourceChanged){texture.updateTexImage();texture.getTransformMatrix(matrix);hasTexture=true;sourceFrames++;}
                    if(!hasTexture){Thread.sleep(2);continue;}
                    float angle=(float)(.5-.5*Math.cos((now-start)/10000.0*Math.PI*2))*.65f;
                    GLES20.glUniform1f(uTilt,angle);
                }
                GLES20.glUniformMatrix4fv(uTex,1,false,matrix,0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                if(captureNow){
                    saveFrame(width,height);System.out.println("CAPTURED_GPU_FRAME "+previousGeometry+" angle="+eased);
                }
                if(!foldMode&&!saved&&SystemClock.uptimeMillis()-start>3000){saveFrame(width,height);saved=true;}
                long swapAt=SystemClock.uptimeMillis();
                if(!EGL14.eglSwapBuffers(display,window))throw new IllegalStateException("swap buffers");
                long swapMs=SystemClock.uptimeMillis()-swapAt;
                if(swapMs>40)System.out.println("SLOW_PRESENT ms="+swapMs+" "+previousDisplay);
                if(revealRequest!=null){
                    call("mirror-cover-content-ready",revealRequest);
                    System.out.println("COVER_FRAME_READY "+revealRequest+" angle="+eased);
                }
                if(frames++==0)System.out.println("FIRST_PREVIEW_FRAME ms="+(SystemClock.uptimeMillis()-start));
            }
            System.out.println("PREVIEW frames="+frames+" sourceFrames="+sourceFrames+" elapsedMs="+(SystemClock.uptimeMillis()-start));
        }catch(Throwable error){failed=true;error.printStackTrace();}
        finally {
            // Drop the last lock/home frame before abandoning an output surface.
            if(display!=EGL14.EGL_NO_DISPLAY&&window!=EGL14.EGL_NO_SURFACE&&context!=EGL14.EGL_NO_CONTEXT){
                if(EGL14.eglMakeCurrent(display,window,window,context)){
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
                    GLES20.glClearColor(0,0,0,0);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    EGL14.eglSwapBuffers(display,window);
                }
            }
            if(mirror!=null)mirror.release();if(input!=null)input.release();if(texture!=null)texture.release();
            if(pyramid!=null)pyramid.close();
            if(display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);}
            if(!continuous)finish();
        }
    }
    static void logSlowPhase(String phase,long started){
        long elapsed=SystemClock.uptimeMillis()-started;
        if(elapsed>40)System.out.println("SLOW_PHASE "+phase+" ms="+elapsed);
    }
    static void saveFrame(int w,int h)throws Exception {
        ByteBuffer bytes=ByteBuffer.allocateDirect(w*h*4);GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes);
        int[] pixels=new int[w*h];int transparent=0,feathered=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int p=((h-1-y)*w+x)*4,a=bytes.get(p+3)&255;
            if(a==0)transparent++;else if(a<255)feathered++;
            int r=a==0?0:Math.min(255,(bytes.get(p)&255)*255/a);
            int g=a==0?0:Math.min(255,(bytes.get(p+1)&255)*255/a);
            int b=a==0?0:Math.min(255,(bytes.get(p+2)&255)*255/a);
            pixels[y*w+x]=(a<<24)|(r<<16)|(g<<8)|b;
        }
        System.out.println("CAPTURE_ALPHA transparent="+transparent+" feathered="+feathered);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(pixels,w,h,android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream("/data/local/tmp/tabfold-live-projection.png")){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
}
