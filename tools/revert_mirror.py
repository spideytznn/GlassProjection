import io

def rev(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (path, old[:60])
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    print('reverted', path)

OUT = 'projection-lab/src/main/java/io/github/sixzleo/tabfold/projection/'

rev(OUT + 'FixedDualOutput.java', [
("""    /** Mirror mode: the panel renders the live default display and touches inject into it. */
    final boolean mirror;
    private final int srcW,srcH;
    /** Mirror pane size: the image occupies the right half of the panel, matching the
     *  physical position of the covered half while the device opens. */
    private final int paneW,paneH;
""", ""),
("""        this.inner=inner;this.owner=owner;this.mirror=mirror;physicalId=display.getDisplayId();
        Point size=new Point();display.getRealSize(size);physicalWidth=size.x;physicalHeight=size.y;
        int turn=ProjectionMath.turn(inner,display.getRotation());
        width=turn%2==0?size.x:size.y;height=turn%2==0?size.y:size.x;
        // Mirror mode maps panel touches into the default display's coordinate space.
        Point src=new Point();
        try{service.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0).getRealSize(src);}catch(RuntimeException ignored){src.x=0;src.y=0;}
        srcW=src.x;srcH=src.y;
""", """
        this.inner=inner;this.owner=owner;physicalId=display.getDisplayId();
        Point size=new Point();display.getRealSize(size);physicalWidth=size.x;physicalHeight=size.y;
        int turn=ProjectionMath.turn(inner,display.getRotation());
        width=turn%2==0?size.x:size.y;height=turn%2==0?size.y:size.x;
"""),
("""        Matrix placement=new Matrix();
        if(mirror){
            // Mirror targets: the INNER panel shows the native screen on its hinge-right half
            // (the cover-sized pane, while the device opens); the COVER panel shows the
            // native screen's hinge-right half full-screen (same physical rectangle, 1:1).
            // The turn transforms below encode this panel's rotated mounting, exactly like
            // the full-screen path; the pane-sized VD rides the same mapping.
            boolean halfPane=inner; // big panel = half pane, small panel = full pane + source crop
            paneW=halfPane?width/2:width;paneH=height;
            FrameLayout.LayoutParams pane=new FrameLayout.LayoutParams(paneW,paneH);
            if(!halfPane){ // cover target: identity, full screen, GPU crops the source's right half
                root.addView(texture,pane);
            }else if(turn==1){ // folded-mounted inner: pane on the physical top half (= user's hinge right)
                texture.setRotation(-90);texture.setTranslationY(paneW);
                placement.setRotate(-90);placement.postTranslate(0,paneW);
                root.addView(texture,pane);
            }else if(turn==3){ // 180-mounted inner: pane on the physical bottom half
                texture.setRotation(90);texture.setTranslationX(paneH);texture.setTranslationY(physicalHeight-paneW);
                placement.setRotate(90);placement.postTranslate(paneH,physicalHeight-paneW);
                root.addView(texture,pane);
            }else{ // open-mounted inner: identity orientation, pane hugs the frame's right edge
                pane.gravity=Gravity.RIGHT|Gravity.TOP;
                placement.setTranslate(width/2,0);
                root.addView(texture,pane);
            }
        }else{
            paneW=width;paneH=height;
            root.addView(texture,new FrameLayout.LayoutParams(width,height));
            if(turn==1){texture.setRotation(-90);texture.setTranslationY(physicalHeight);placement.setRotate(-90);placement.postTranslate(0,physicalHeight);}
            else if(turn==2){texture.setRotation(180);texture.setTranslationX(physicalWidth);texture.setTranslationY(physicalHeight);placement.setRotate(180);placement.postTranslate(physicalWidth,physicalHeight);}
            else if(turn==3){texture.setRotation(90);texture.setTranslationX(physicalWidth);placement.setRotate(90);placement.postTranslate(physicalWidth,0);}
        }
        placement.invert(inverse);
""", """
        root.addView(texture,new FrameLayout.LayoutParams(width,height));
        texture.setPivotX(0);texture.setPivotY(0);
        Matrix placement=new Matrix();
        if(turn==1){texture.setRotation(-90);texture.setTranslationY(physicalHeight);placement.setRotate(-90);placement.postTranslate(0,physicalHeight);}
        else if(turn==2){texture.setRotation(180);texture.setTranslationX(physicalWidth);texture.setTranslationY(physicalHeight);placement.setRotate(180);placement.postTranslate(physicalWidth,physicalHeight);}
        else if(turn==3){texture.setRotation(90);texture.setTranslationX(physicalWidth);placement.setRotate(90);placement.postTranslate(physicalWidth,0);}
        placement.invert(inverse);
"""),
("""    FixedDualOutput(ProjectionService service,Display display,boolean inner,int densityOverride,FixedDualSession owner,boolean mirror)throws Exception{""",
 """    FixedDualOutput(ProjectionService service,Display display,boolean inner,int densityOverride,FixedDualSession owner)throws Exception{"""),
("""        forwarder.addView(feedback,new FrameLayout.LayoutParams(mirror?paneW:width,mirror?paneH:height));""",
 """        forwarder.addView(feedback,new FrameLayout.LayoutParams(width,height));"""),
("""            if(mirror){ // pane-space coords into the native display's space; outside = ignored
                if(srcW>0&&srcH>0){
                    if(event.getX()<0||event.getX()>paneW||event.getY()<0||event.getY()>paneH)return true;
                    android.graphics.Matrix scale=new android.graphics.Matrix();
                    if(inner){ // half pane shows the whole source
                        scale.setScale(srcW/(float)paneW,srcH/(float)paneH);
                    }else{ // full pane shows only the source's hinge-right half
                        scale.setScale(srcW/(2f*paneW),srcH/(float)paneH);
                        scale.postTranslate(srcW/2f,0);
                    }
                    event.transform(scale);
                }
                MobileHelper.dualTouch(0,event);return true;
            }
""", ""),
("""    /** Freeze the pane's current frame for a transition mask on the other panel. */
    android.graphics.Bitmap snapshot(){
        if(surface==null||!texture.isAvailable())return null;
        try{
            Bitmap bitmap=Bitmap.createBitmap(paneW,paneH,Bitmap.Config.ARGB_8888);
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
            PixelCopy.request(surface,bitmap,(result)->done.countDown(),main);
            return done.await(600,java.util.concurrent.TimeUnit.MILLISECONDS)?bitmap:null;
        }catch(RuntimeException|InterruptedException e){return null;}
    }
    void frame(float angle,boolean block){""", """    void frame(float angle,boolean block){"""),
("""        source.setDefaultBufferSize(paneW,paneH);surface=new Surface(source);""",
 """        source.setDefaultBufferSize(width,height);surface=new Surface(source);"""),
("""        gpu=new FixedDualGpu(surface,paneW,paneH,paneH,inner,mirror,mirror&&!inner,mirror&&!inner?srcW:paneW,mirror&&!inner?srcH:paneH,()->contentId>=0&&!(mirror&&!inner),input->{
            if(mirror){
                int reuse=owner.reuseMirror(input,inner?paneW:srcW,inner?paneH:srcH);
                if(reuse>=0){contentId=reuse;owner.contentReady();return;}
                MobileHelper.createMirrorContent(input,inner?paneW:srcW,inner?paneH:srcH,density,id->{
                    if(closed)return;if(id<0){owner.fail("无法创建镜像");return;}contentId=id;owner.adoptedMirror(id);owner.contentReady();
                });
            }
            else MobileHelper.createDualContent(input,width,contentHeight,density,inner,id->{
                if(closed)return;if(id<0){owner.fail("无法创建"+(inner?"内屏":"外屏")+"桌面");return;}contentId=id;owner.contentReady();
            });
        },owner::fail,(direct,target)->MobileHelper.dualSurface(contentId,target));""",
 """        gpu=new FixedDualGpu(surface,width,height,contentHeight,inner,()->contentId>=0,input->MobileHelper.createDualContent(input,width,contentHeight,density,inner,id->{
            if(closed)return;if(id<0){owner.fail("无法创建"+(inner?"内屏":"外屏")+"桌面");return;}contentId=id;owner.contentReady();
        }),owner::fail,(direct,target)->MobileHelper.dualSurface(contentId,target));"""),
])

rev(OUT + 'FixedDualGpu.java', [
("""    private final int width,height,bufferWidth,bufferHeight;private volatile int contentHeight;private final boolean inner;
    /** Mirror pane: never run the fold animation — it must stay a plain live copy. */
    private final boolean plain;
    /** Mirror pane on the small panel: sample only the source's hinge-right half (1:1). */
    private final boolean cropRight;""",
 """    private final int width,height;private volatile int contentHeight;private final boolean inner;"""),
("""    private final ProjectionAngleMotion motion=new ProjectionAngleMotion();
    /** Column-major u->u*0.5+0.5: maps sampling onto the texture's right half. */
    private static final float[] CROP_RIGHT={0.5f,0,0,0, 0,1,0,0, 0,0,1,0, 0.5f,0,0,1};""",
 """    private final ProjectionAngleMotion motion=new ProjectionAngleMotion();"""),
("""    FixedDualGpu(Surface output,int width,int height,int contentHeight,boolean inner,boolean plain,boolean cropRight,int bufferWidth,int bufferHeight,java.util.function.BooleanSupplier directAllowed,java.util.function.Consumer<Surface> ready,java.util.function.Consumer<String> failed,java.util.function.BiConsumer<Boolean,Surface> swap){
        this.width=width;this.height=height;this.bufferWidth=bufferWidth;this.bufferHeight=bufferHeight;this.contentHeight=contentHeight;this.inner=inner;this.plain=plain;this.cropRight=cropRight;this.failed=failed;""",
 """    FixedDualGpu(Surface output,int width,int height,int contentHeight,boolean inner,java.util.function.BooleanSupplier directAllowed,java.util.function.Consumer<Surface> ready,java.util.function.Consumer<String> failed,java.util.function.BiConsumer<Boolean,Surface> swap){
        this.width=width;this.height=height;this.contentHeight=contentHeight;this.inner=inner;this.failed=failed;"""),
("""        source=new SurfaceTexture(external);source.setDefaultBufferSize(bufferWidth,bufferHeight);source.setOnFrameAvailableListener(s->dirty=true,worker);input=new Surface(source);""",
 """        source=new SurfaceTexture(external);source.setDefaultBufferSize(width,contentHeight);source.setOnFrameAvailableListener(s->dirty=true,worker);input=new Surface(source);"""),
("""        pyramid=new FixedDualGpuPyramid(Math.max(bufferWidth,bufferHeight)/2,vertices);""",
 """        pyramid=new FixedDualGpuPyramid(Math.max(width,contentHeight)/2,vertices);"""),
("""        uniform("screenFadeActive",1);GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"screen"),bufferWidth,bufferHeight);started=SystemClock.uptimeMillis();""",
 """        uniform("screenFadeActive",1);GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"screen"),width,contentHeight);started=SystemClock.uptimeMillis();"""),
("""            if(changed){GLES20.glActiveTexture(GLES20.GL_TEXTURE7);source.updateTexImage();source.getTransformMatrix(matrix);
                if(cropRight){ // sample only U in [0.5,1]: the source's hinge-right half
                    android.opengl.Matrix.multiplyMM(matrix,0,matrix,0,CROP_RIGHT,0);
                    hasFrame=true;pyramidDirty=true;sourceFrames++;}
                else{hasFrame=true;pyramidDirty=true;sourceFrames++;}}""",
 """            if(changed){GLES20.glActiveTexture(GLES20.GL_TEXTURE7);source.updateTexImage();source.getTransformMatrix(matrix);hasFrame=true;pyramidDirty=true;sourceFrames++;}"""),
("""            float crop=ProjectionMath.cropFraction(eased,inner,AnimationSettings.stretchPercent)*amount;
            if(plain){tilt=0;crop=0;} // the mirror pane shows the raw copy, no fold theatre""",
 """            float crop=ProjectionMath.cropFraction(eased,inner,AnimationSettings.stretchPercent)*amount;"""),
("""    void resizeContent(int newHeight){
        if(newHeight<=0||newHeight>height)return;
        worker.post(()->{if(closed)return;contentHeight=newHeight;
            GLES20.glUseProgram(program);uniform("contentFraction",contentHeight<height?(float)contentHeight/height:1f);pyramidDirty=true;});
    }""",
 """    void resizeContent(int newHeight){
        if(newHeight<=0||newHeight>height)return;
        worker.post(()->{if(closed)return;contentHeight=newHeight;source.setDefaultBufferSize(width,contentHeight);
            GLES20.glUseProgram(program);uniform("contentFraction",contentHeight<height?(float)contentHeight/height:1f);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"screen"),width,contentHeight);pyramidDirty=true;});
    }"""),
])
print('all done')
