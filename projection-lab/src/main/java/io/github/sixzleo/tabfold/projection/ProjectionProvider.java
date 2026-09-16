package io.github.sixzleo.tabfold.projection;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

public final class ProjectionProvider extends ContentProvider {
    public boolean onCreate(){AnimationSettings.init(getContext());return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        if(Binder.getCallingUid()!=2000 && Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("Own app or ADB shell only");
        Bundle b=new Bundle();
        if("fixed-dual-session".equals(method)){
            if(arg!=null)new Handler(Looper.getMainLooper()).post(()->{if("0".equals(arg))FixedDualSession.stop();else if("1".equals(arg))FixedDualSession.start(ProjectionService.instance);});
            b.putString("status",FixedDualSession.status);b.putString("innerRenderer",FixedDualGpu.innerStats);b.putString("coverRenderer",FixedDualGpu.coverStats);return b;
        }
        if("fixed-dual-test".equals(method)){
            if(arg!=null){
                final int seconds;
                try{seconds=Integer.parseInt(arg);}catch(NumberFormatException e){b.putString("status","ERROR invalid duration");return b;}
                new Handler(Looper.getMainLooper()).post(()->FixedDualTrial.start(ProjectionService.instance,seconds));
            }
            b.putString("status",FixedDualTrial.status);return b;
        }
        if("helper-connect".equals(method)){
            if(Binder.getCallingUid()!=2000)throw new SecurityException("Shell host only");
            MobileHelper.init(getContext());
            try{
                boolean accepted=MobileHelper.prefersWireless()&&extras!=null&&extras.getBinder("host")!=null&&extras.getLong("version")==getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),0).getLongVersionCode();
                b.putBoolean("accepted",accepted);if(accepted)MobileHelper.acceptWireless(getContext(),extras.getBinder("host"));
            }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            return b;
        }
        if("mirror-live".equals(method)){return ProjectionService.mirrorLive(!"0".equals(arg));}
        if("mirror-preview".equals(method)){ProjectionService.mirrorTest(arg==null?15:Integer.parseInt(arg));return b;}
        if("mirror-lease".equals(method)){return ProjectionService.mirrorLease();}
        if("mirror-observe".equals(method)){ProjectionService.mirrorObserve(arg==null?30:Integer.parseInt(arg));return b;}
        if("mirror-fold".equals(method)){ProjectionService.mirrorFoldTest(arg==null?55:Integer.parseInt(arg));return b;}
        if("mirror-frame".equals(method)){return ProjectionService.mirrorFrame();}
        if("mirror-listen".equals(method)){
            if(Binder.getCallingUid()!=2000)throw new SecurityException("Shell renderer only");
            ProjectionService.listenRenderer(extras==null?null:extras.getBinder("listener"));
            b.putBoolean("supported",true);b.putBoolean("pushFrames",true);return b;
        }
        if("mirror-blackout-prepared".equals(method)){ProjectionService.prepareBlackout(Long.parseLong(arg));return b;}
        if("mirror-cover-content-ready".equals(method)){ProjectionService.markCoverReady(arg);return b;}
        if("desktop-disable".equals(method)){ProjectionService.stop();return b;}
        if("desktop-telemetry".equals(method)) {
            if(Binder.getCallingUid()==2000&&extras!=null&&extras.containsKey("directContactAvailable"))
                ProjectionService.deliverDirectContact(extras);
            ProjectionService.helperAt=SystemClock.uptimeMillis();
            b.putLong("updatedAt",ProjectionService.updatedAt);b.putBoolean("allowed",ProjectionService.allowed);
            b.putBoolean("primaryInner",ProjectionService.primaryInner);ProjectionService.putFoldPose(b);
            b.putBoolean("lockScreen",ProjectionService.lockScreen);
            b.putBoolean("standby",ProjectionService.standby);
            b.putInt("openAngle",AnimationSettings.openAngle);b.putInt("closeAngle",AnimationSettings.closeAngle);
            b.putInt("startAngle",AnimationSettings.startAngle);
            b.putFloat("blurStrength",AnimationSettings.blurPercent/100f);
            b.putInt("stretchPercent",AnimationSettings.stretchPercent);
            b.putString("status",ProjectionService.status);return b;
        }
        throw new IllegalArgumentException("Unknown method");
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
    public String getType(Uri u){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
