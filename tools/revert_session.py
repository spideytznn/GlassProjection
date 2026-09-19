import io

def rev(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (path, old[:70])
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    print('reverted', path)

rev('projection-lab/src/main/java/io/github/sixzleo/tabfold/projection/FixedDualSession.java', [
# fields
("""    private boolean closed;
    private int touchedDisplay=-1;
    /** Choreography: in-session primary flips with hysteresis on the fold angle. */
    private static final float SWITCH_OPEN_ANGLE=120f,SWITCH_FOLD_ANGLE=60f;
    private boolean switching;
    private int mirrorContentId=-1;
    private FreezeMask pendingMask;""",
 """    private boolean closed;
    private int touchedDisplay=-1;"""),
# mirrorMode + maintain
("""    /** Mirror mode: the panel shows the live native display instead of our desktop. */
    static boolean mirrorMode(android.content.Context c){return c.getSharedPreferences("duo_dual",0).getBoolean("mirror",false);}
    static void maintain(ProjectionService service){
        if(!mirrorMode(service))DuoHomeActivity.validateSecondaryHomes();""",
 """    static void maintain(ProjectionService service){
        DuoHomeActivity.validateSecondaryHomes();"""),
("""        boolean ownHome=service.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        // Mirror mode does not own the home role: the native launcher stays the desktop.
        if(!ownHome&&!mirrorMode(service)){ownHomeSince=0;stop();return;}""",
 """        boolean ownHome=service.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        if(!ownHome){ownHomeSince=0;stop();return;}"""),
# begin timeout
("""        final int need=mirrorMode(service)?1:2;
        status="preparing";main.postDelayed(()->{if(outputs.size()!=need||outputs.stream().anyMatch(o->o.contentId<0))fail("双屏内容准备超时");},15000);""",
 """        status="preparing";main.postDelayed(()->{if(outputs.size()!=2||outputs.stream().anyMatch(o->o.contentId<0))fail("双屏内容准备超时");},15000);"""),
# prepare
("""            boolean mirror=mirrorMode(service);
            if(mirror){ // only the non-primary panel needs an output; the primary IS the native screen
                int primaryId=dm.getDisplay(0).getDisplayId();
                for(Display candidate:new Display[]{innerDisplay,coverDisplay})
                    if(candidate!=null&&candidate.getDisplayId()!=primaryId&&outputs.isEmpty())
                        outputs.add(new FixedDualOutput(service,candidate,candidate.equals(innerDisplay),0,this,true));
            }else{
                if(innerDisplay!=null&&outputs.stream().noneMatch(o->o.inner))
                    outputs.add(new FixedDualOutput(service,innerDisplay,true,0,this,false));
                if(coverDisplay!=null&&outputs.stream().noneMatch(o->!o.inner))
                    outputs.add(new FixedDualOutput(service,coverDisplay,false,compensatedDensity(innerDisplay,coverDisplay),this,false));
            }
            int need=mirror?1:2;
            if(outputs.size()<need){if(SystemClock.uptimeMillis()>readyUntil)throw new IllegalStateException("Missing physical panel");main.postDelayed(this::prepare,50);}""",
 """            if(innerDisplay!=null&&outputs.stream().noneMatch(o->o.inner))
                outputs.add(new FixedDualOutput(service,innerDisplay,true,0,this));
            if(coverDisplay!=null&&outputs.stream().noneMatch(o->!o.inner))
                outputs.add(new FixedDualOutput(service,coverDisplay,false,compensatedDensity(innerDisplay,coverDisplay),this));
            if(outputs.size()<2){if(SystemClock.uptimeMillis()>readyUntil)throw new IllegalStateException("Missing physical panel");main.postDelayed(this::prepare,50);}"""),
# contentReady
("""    void contentReady(){if(closed)return;if(outputs.size()==(mirrorMode(service)?1:2)&&outputs.stream().allMatch(o->o.contentId>=0)){
        if(pendingMask!=null){FreezeMask mask=pendingMask;pendingMask=null;main.postDelayed(()->{mask.dismiss();switching=false;android.util.Log.i("DuoSwitch","settled");},800);}
        status="running";Choreographer.getInstance().postFrameCallback(frame);}}""",
 """    void contentReady(){if(closed)return;if(outputs.size()==2&&outputs.stream().allMatch(o->o.contentId>=0)){status="running";Choreographer.getInstance().postFrameCallback(frame);}}"""),
# frame loop
("""            DisplayManager dm=service.getSystemService(DisplayManager.class);
            if(!primary.equals(identity(dm.getDisplay(0)))){
                if(!switching||!mirrorMode(service)){fail("Physical primary unexpectedly changed");return;}
                Choreographer.getInstance().postFrameCallback(this);return; // a switch owns the flip
            }
            if(switching){Choreographer.getInstance().postFrameCallback(this);return;}""",
 """            DisplayManager dm=service.getSystemService(DisplayManager.class);
            if(!primary.equals(identity(dm.getDisplay(0)))){fail("Physical primary unexpectedly changed");return;}"""),
("""            FixedDualPolicy.Panel visible=policy.update(flat,contact,Float.isFinite(raw)&&pose.directContactStatus>=0,!asleep);
            if(mirrorMode(service)&&!switching&&Float.isFinite(raw)&&raw>0f&&raw<180f){
                int held=policy.requestedState();
                if(held==6&&raw>SWITCH_OPEN_ANGLE)switchTo(5);
                else if(held==5&&raw<SWITCH_FOLD_ANGLE)switchTo(6);
            }""",
 """            FixedDualPolicy.Panel visible=policy.update(flat,contact,Float.isFinite(raw)&&pose.directContactStatus>=0,!asleep);"""),
("""            // Mirror outputs never curtain: staying lit across the whole fold is the point.
            for(FixedDualOutput output:outputs)output.frame(angle,!output.mirror&&(visible==FixedDualPolicy.Panel.NONE||visible==FixedDualPolicy.Panel.INNER&&!output.inner||visible==FixedDualPolicy.Panel.COVER&&output.inner));""",
 """            for(FixedDualOutput output:outputs)output.frame(angle,visible==FixedDualPolicy.Panel.NONE||visible==FixedDualPolicy.Panel.INNER&&!output.inner||visible==FixedDualPolicy.Panel.COVER&&output.inner);"""),
("""            if(now-statusAt>=250){statusAt=now;status="running primary="+primary+" visible="+visible+" angle="+raw+" content="+outputs.stream().map(o->String.valueOf(o.contentId)).reduce((a,b)->a+","+b).orElse("-")
                +" dpi="+outputs.stream().map(o->String.valueOf(o.density)).reduce((a,b)->a+"/"+b).orElse("-")+" "+densityInfo;}""",
 """            if(now-statusAt>=250){statusAt=now;status="running primary="+primary+" visible="+visible+" angle="+raw+" content="+outputs.get(0).contentId+","+outputs.get(1).contentId
                +" dpi="+outputs.get(0).density+"/"+outputs.get(1).density+" "+densityInfo;}"""),
# remove switch machinery + reuse hooks
("""    /** Re-point the live mirror VD at a new pane surface and size; -1 when none exists. */
    int reuseMirror(android.view.Surface input,int w,int h){
        int id=mirrorContentId;
        if(id<0)return -1;
        MobileHelper.resizeDualContent(id,w,h);
        MobileHelper.dualSurface(id,input);
        return id;
    }
    void adoptedMirror(int id){mirrorContentId=id;}
""", ""),
])
print('session core reverted')
