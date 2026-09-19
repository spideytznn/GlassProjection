import io, os

def rev(path, pairs, deletes=()):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (path, old[:70])
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    for d in deletes:
        os.remove(d)
    print('reverted', path)

SRC = 'projection-lab/src/main/java/io/github/sixzleo/tabfold/projection/'

# 1. ProjectionService: restore HomeControlPanel wiring
rev(SRC + 'ProjectionService.java', [
("""            HomeStatusBar bar=new HomeStatusBar(wc,new ShadePullListener(){
                // 自制面板停用实验：改为撤出覆盖窗+注入下拉，让 HyperOS 原生面板接管 display 0。
                // @Override public void onPullFired(int side,float fingerY){HomeControlPanel.beginDrag(id,side==HomeStatusBar.SIDE_CONTROL,fingerY);}
                // @Override public void onPullDrag(float fingerY){HomeControlPanel.dragOn(id,fingerY);}
                // @Override public void onPullRelease(float velocityPxPerMs){HomeControlPanel.releaseOn(id,velocityPxPerMs);}
                @Override public void onPullFired(int side,float fingerY){NativeShade.pull(id,side,fingerY);}
                @Override public void onPullDrag(float fingerY){NativeShade.drag(id,fingerY);}
                @Override public void onPullRelease(float velocityPxPerMs){NativeShade.release(id,velocityPxPerMs);}
            });""",
 """            HomeStatusBar bar=new HomeStatusBar(wc,new ShadePullListener(){
                @Override public void onPullFired(int side,float fingerY){HomeControlPanel.beginDrag(id,side==HomeStatusBar.SIDE_CONTROL,fingerY);}
                @Override public void onPullDrag(float fingerY){HomeControlPanel.dragOn(id,fingerY);}
                @Override public void onPullRelease(float velocityPxPerMs){HomeControlPanel.releaseOn(id,velocityPxPerMs);}
            });"""),
("""            removeShadeBarAt(id);
            if(NativeShade.blocking()==id)return; // native shade owns this display; keep its strip clear""",
 """            removeShadeBarAt(id);"""),
("""    static void removeShadeBarAt(int id){""",
 """    private static void removeShadeBarAt(int id){"""),
])

# 2. FixedDualOutput: remove setNativeShade trio
rev(SRC + 'FixedDualOutput.java', [
("""    private FixedDualGpu gpu;
    private boolean nativeShade;
""", """    private FixedDualGpu gpu;
"""),
("""    /** Native-shade experiment: vacate this panel so the dormant system UI on the physical
     *  display shows through and takes touch. The TextureView only turns INVISIBLE — a GONE
     *  or detached view would tear down the surface and fail the whole session. */
    void setNativeShade(boolean on){
        if(nativeShade==on)return;nativeShade=on;
        try{
            WindowManager.LayoutParams f=(WindowManager.LayoutParams)forwarder.getLayoutParams();
            if(on){
                f.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                forwarder.setVisibility(View.INVISIBLE);texture.setVisibility(View.INVISIBLE);black.setVisibility(View.GONE);
            }else{
                f.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                forwarder.setVisibility(View.VISIBLE);texture.setVisibility(View.VISIBLE);
            }
            manager.updateViewLayout(forwarder,f);
            android.util.Log.i("DuoNative","setNativeShade("+on+") panel="+physicalId);
        }catch(RuntimeException e){android.util.Log.w("DuoNative","update failed",e);}
    }
""", ""),
("""    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){if(!closed&&!nativeShade)owner.fail("输出画面已断开");return true;}""",
 """    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){if(!closed)owner.fail("输出画面已断开");return true;}"""),
])

# 3. FixedDualSession: remove outputForContent + sessionEnded hook
rev(SRC + 'FixedDualSession.java', [
("""    /** Output that renders the given content display; null when no session or display unknown. */
    static FixedDualOutput outputForContent(int contentId){
        FixedDualSession s=current;
        if(s==null)return null;
        for(FixedDualOutput o:s.outputs)if(contentId<0?o.physicalId==0:o.contentId==contentId)return o;
        return null;
    }
""", ""),
("""        if(closed)return;closed=true;main.removeCallbacksAndMessages(null);Choreographer.getInstance().removeFrameCallback(frame);
        NativeShade.sessionEnded(); // drop the immersive-nav policy before the overlay dies
""", """        if(closed)return;closed=true;main.removeCallbacksAndMessages(null);Choreographer.getInstance().removeFrameCallback(frame);
"""),
])

# 4. ProjectionProvider: remove mirror-mode + mirror-shade-test hooks
rev(SRC + 'ProjectionProvider.java', [
("""        if("mirror-shade-test".equals(method)){
            // Debug hook: force the mirrored native shade over the non-primary panel.
            new Handler(Looper.getMainLooper()).post(()->{
                boolean wantPrimary="1".equals(arg);
                for(int contentId:FixedDualSession.activeContentIds()){
                    FixedDualOutput out=FixedDualSession.outputForContent(contentId);
                    if(out!=null&&out.physicalId==0==wantPrimary)MirrorShade.open(out,HomeStatusBar.SIDE_CONTROL);
                }
            });
            b.putString("status","requested");return b;
        }
""", ""),
("""        if("mirror-mode".equals(method)){
            // arg 0/1 toggles the always-mirror pipeline: non-primary panel shows the live
            // native display, no DuoHome anywhere. Session restarts to rebuild outputs.
            if(arg!=null)new Handler(Looper.getMainLooper()).post(()->{
                getContext().getSharedPreferences("duo_dual",0).edit().putBoolean("mirror",!"0".equals(arg)).apply();
                FixedDualSession.stop();
                if(FixedDualSession.enabled(getContext()))FixedDualSession.start(ProjectionService.instance);
            });
            b.putString("status",FixedDualSession.status);return b;
        }
""", ""),
])

# 5. AIDL: remove mirror methods
rev('projection-lab/src/main/aidl/io/github/sixzleo/tabfold/projection/IHelperHost.aidl', [
("""
    int createMirrorContent(in Surface surface, int width, int height, int density) = 15;
    void releaseMirrorContent(int displayId) = 16;
""", ""),
])

# 6. FixedDualContentHost: remove createMirror/releaseMirror, restore guards
rev(SRC + 'FixedDualContentHost.java', [
("""    /** Releases one mirror display; content displays stay owned by the session lifecycle. */
    void releaseMirror(int displayId){
        VirtualDisplay display=displays.remove(displayId);
        if(display!=null){densities.remove(displayId);display.release();}
    }
    /** Mirror-mode display: no OWN_CONTENT_ONLY, so it renders the live content of the
     *  default display (display 0) scaled into the requested size — the "always mirror
     *  the native screen" pipeline. No home launch, no IME: it owns no content. */
    int createMirror(Surface surface,int width,int height,int density)throws Exception{
        if(surface==null||!surface.isValid()||width<100||height<100||width>4096||height>4096||density<100||density>800)
            throw new IllegalArgumentException("Invalid output");
        int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
        try{flags|=DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_TRUSTED").getInt(null);}catch(Exception ignored){}
        VirtualDisplay display;
        if(android.os.Build.VERSION.SDK_INT>=35)
            display=shell.getSystemService(DisplayManager.class).createVirtualDisplay(new VirtualDisplayConfig.Builder("Duo live mirror",width,height,density).setSurface(surface).setFlags(flags).setRequestedRefreshRate(120f).build());
        else{display=shell.getSystemService(DisplayManager.class).createVirtualDisplay("Duo live mirror",width,height,density,surface,flags);
            surface.setFrameRate(120,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);}
        if(display==null)throw new IllegalStateException("Mirror display rejected");
        int id=display.getDisplay().getDisplayId();displays.put(id,display);densities.put(id,density);
        if(displays.size()==1)applyPrimaryRate(true);
        return id;
    }
""", ""),
("""    synchronized void touch(int displayId,MotionEvent event)throws Exception{
        // displayId 0 = mirror mode injecting into the real primary display.
        if((displayId!=0&&!displays.containsKey(displayId))||event==null||SET_DISPLAY_ID==null)return;""",
 """    synchronized void touch(int displayId,MotionEvent event)throws Exception{
        if(!displays.containsKey(displayId)||event==null||SET_DISPLAY_ID==null)return;"""),
("""    void key(int displayId,int keyCode)throws Exception{
        if((displayId!=0&&!displays.containsKey(displayId))||(keyCode!=KeyEvent.KEYCODE_BACK&&keyCode!=KeyEvent.KEYCODE_HOME&&keyCode!=KeyEvent.KEYCODE_APP_SWITCH))return;
        if(displayId==0){ // native target: the system resolves home/recents itself
            long now=SystemClock.uptimeMillis();
            for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}){
                KeyEvent event=new KeyEvent(now,now,action,keyCode,0);KeyEvent.class.getMethod("setDisplayId",int.class).invoke(event,displayId);inject(event);
            }
            return;
        }
        if(keyCode==KeyEvent.KEYCODE_APP_SWITCH){""",
 """    void key(int displayId,int keyCode)throws Exception{
        if(!displays.containsKey(displayId)||(keyCode!=KeyEvent.KEYCODE_BACK&&keyCode!=KeyEvent.KEYCODE_HOME&&keyCode!=KeyEvent.KEYCODE_APP_SWITCH))return;
        if(keyCode==KeyEvent.KEYCODE_APP_SWITCH){"""),
])

# 7. MobileHelperHost: remove overrides + revert in-session switch
rev(SRC + 'MobileHelperHost.java', [
("""    @Override public synchronized int createMirrorContent(android.view.Surface surface,int width,int height,int density){
        caller();long token=Binder.clearCallingIdentity();
        try{if(fixedState<0)throw new IllegalStateException("Fixed topology required");
            if(dualContent==null)dualContent=new FixedDualContentHost(context);
            return dualContent.createMirror(surface,width,height,density);
        }catch(Exception e){android.util.Log.e("DuoFixed","Mirror creation failed",e);return -1;}
        finally{Binder.restoreCallingIdentity(token);}
    }
""", ""),
("""    @Override public synchronized void releaseMirrorContent(int displayId){
        caller();long token=Binder.clearCallingIdentity();
        try{if(dualContent!=null)dualContent.releaseMirror(displayId);}catch(Exception e){android.util.Log.e("DuoFixed","Mirror release failed",e);}
        finally{Binder.restoreCallingIdentity(token);}
    }
""", ""),
("""            if(fixedState>=0){
                if(state==fixedState)return "OK already fixed";
                // In-session 5<->6 flip: supersede our own override without teardown, so the
                // panels never unpower and the masks keep the hand-off covered.
                try{
                    Class<?> request=Class.forName("android.hardware.devicestate.DeviceStateRequest");
                    Object builder=request.getMethod("newBuilder",int.class).invoke(null,state);
                    fixedStateGlobal.getClass().getMethod("requestState",request,java.util.concurrent.Executor.class,Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback"))
                        .invoke(fixedStateGlobal,builder.getClass().getMethod("build").invoke(builder),null,null);
                    fixedState=state;return "OK switched state="+state;
                }catch(Exception e){releaseFixedState();return "ERROR switch "+e;}
            }""",
 """            if(fixedState>=0)return state==fixedState?"OK already fixed":"ERROR primary mapping cannot change during a session";"""),
])

# 8. MobileHelper: remove statics
rev(SRC + 'MobileHelper.java', [
("""    static void releaseMirrorContent(int id){IHelperHost current=host;
        worker.execute(()->{try{if(current!=null)current.releaseMirrorContent(id);}catch(Exception ignored){}});}
    /** Mirror-mode display: renders the live default-display content; no own content. */
    static void createMirrorContent(android.view.Surface surface,int w,int h,int density,java.util.function.IntConsumer done){
        IHelperHost current=host;
        worker.execute(()->{int id=-1;try{if(current!=null)id=current.createMirrorContent(surface,w,h,density);}catch(Exception e){android.util.Log.e("DuoFixed","Mirror create",e);}
            final int result=id;main.post(()->done.accept(result));});
    }
""", ""),
])

# 9. delete shade classes
for f in [SRC + 'NativeShade.java', SRC + 'MirrorShade.java']:
    if os.path.exists(f):
        os.remove(f)
        print('deleted', f)

print('all reverted')
