package io.github.sixzleo.tabfold.projection;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Process-wide update state; screens observe it, and the foreground service owns transfers. */
final class UpdateCoordinator {
    private static UpdateCoordinator instance;
    static synchronized UpdateCoordinator get(Context context){if(instance==null)instance=new UpdateCoordinator(context);return instance;}
    final Context app;
    final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService checks=Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners=new HashSet<>();
    private final SharedPreferences prefs;
    private WeakReference<Activity> visible=new WeakReference<>(null);
    private long checkedAt;
    private final String currentVersion;
    ApkRelease latest,job;
    boolean checking,downloading,ready,installPending,installing;
    String checkStatus="从 GitHub 检查最新 APK",downloadStatus="",error="";
    long received;
    int failureId;
    private UpdateCoordinator(Context context){
        app=context.getApplicationContext();String version;
        try{version=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;}catch(Exception e){version="0.0.0";}
        currentVersion=version;prefs=app.getSharedPreferences("apk_updates",0);
        latest=parse(prefs.getString("latest",null));job=parse(prefs.getString("job",null));
        if(job!=null&&isNewer(job)){
            ready=prefs.getBoolean("ready",false)&&apk().isFile()&&apk().length()==job.size;
            received=ready?job.size:partial().length();installPending=ready&&prefs.getBoolean("installPending",false);
            downloadStatus=ready?"APK 已下载，校验通过":received>0?"下载已暂停，可继续":"";
        }else{job=null;prefs.edit().remove("job").putBoolean("ready",false).putBoolean("installPending",false).apply();}
    }
    private ApkRelease parse(String json){try{return json==null?null:new ApkRelease(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){return null;}}
    File folder(){File dir=new File(app.getFilesDir(),"updates");if(!dir.isDirectory())dir.mkdirs();return dir;}
    File apk(){return new File(folder(),"verified.apk");}
    File partial(){return new File(folder(),"verified.apk.partial");}
    String version(){return currentVersion;}
    boolean isNewer(ApkRelease release){
        if(release==null)return false;
        try{return UpdateTrust.compareVersion(release.tag,version())>0;}
        catch(IllegalArgumentException invalidVersion){return false;} // An unknown build label must not crash an activity or offer an unverified downgrade.
    }
    boolean hasUpdate(){return isNewer(latest)||isNewer(job);}
    ApkRelease offered(){return isNewer(job)&&((downloading||ready||received>0)||!isNewer(latest))?job:latest;}
    String releasePage(){ApkRelease r=offered();return r==null?UpdateTrust.RELEASES:r.page;}
    void observe(Runnable listener){listeners.add(listener);listener.run();}
    void unobserve(Runnable listener){listeners.remove(listener);}
    void changed(){for(Runnable listener:new ArrayList<>(listeners))listener.run();}
    void check(boolean manual){
        long now=SystemClock.elapsedRealtime();if(checking||downloading||installing||!manual&&checkedAt!=0&&now-checkedAt<60_000)return;
        checkedAt=now;checking=true;error="";checkStatus="正在检查更新";changed();
        checks.execute(()->{
            try{
                final ApkRelease[] found=new ApkRelease[1];
                new UpdateTransport().download(UpdateTrust.API,new File(app.getCacheDir(),"update-release.json"),-1,1024*1024,
                    file->found[0]=new ApkRelease(Files.readAllBytes(file.toPath())),message->main.post(()->{checkStatus=message;changed();}));
                main.post(()->{latest=found[0];prefs.edit().putString("latest",latest.json()).apply();checking=false;
                    checkStatus=isNewer(latest)?"发现新版本 "+latest.tag:"暂无新版 · GitHub 最新 "+latest.tag;changed();});
            }catch(Exception failed){main.post(()->{checking=false;checkStatus="暂时无法检查更新，稍后可重试";
                if(manual){error=failed.getMessage();failureId++;}changed();});}
        });
    }
    void begin(ApkRelease release){
        boolean same=job!=null&&job.url.equals(release.url)&&job.size==release.size&&job.hash.equals(release.hash);
        job=release;downloading=true;ready=false;installPending=false;error="";
        // ResumableUpdate checks URL, size and digest before reusing this file.
        received=same?Math.min(partial().length(),release.size):0;downloadStatus="正在准备下载";
        prefs.edit().putString("job",release.json()).putBoolean("ready",false).putBoolean("installPending",false).apply();changed();
    }
    void progress(String message){downloadStatus=message;changed();}
    void bytes(long value){received=value;changed();}
    void completed(){
        downloading=false;ready=true;installPending=true;received=job.size;downloadStatus="APK 已下载，校验通过";
        prefs.edit().putBoolean("ready",true).putBoolean("installPending",true).apply();changed();
    }
    void failed(Exception failure){
        downloading=false;received=partial().length();ready=false;installPending=false;
        boolean paused=failure instanceof InterruptedIOException;
        downloadStatus=paused?"下载已暂停，进度已保存":"下载未完成，进度已保存";
        if(!paused){error=failure.getMessage();failureId++;}changed();
    }
    void installOpened(){installPending=false;installing=true;prefs.edit().putBoolean("installPending",false).apply();changed();}
    void installClosed(){installing=false;changed();}
    void foreground(Activity activity){
        visible=new WeakReference<>(activity);
        if(!(activity instanceof UpdateInstallActivity)&&installPending&&!installing)main.post(()->tryAutoInstall(false));
    }
    void background(Activity activity){if(visible.get()==activity)visible.clear();}
    void tryAutoInstall(boolean fromCompletion){
        if(!ready||!installPending||installing||app.getSystemService(KeyguardManager.class).isKeyguardLocked())return;
        Activity activity=visible.get();
        Intent intent=new Intent(app,UpdateInstallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // A notification remains available even when Android silently rejects a background launch.
        try{
            if(activity!=null&&!activity.isFinishing())activity.startActivity(intent);
            else if(fromCompletion)app.startActivity(intent);
        }catch(ActivityNotFoundException|SecurityException ignored){}
    }
    void verifyApk(File apk,ApkRelease release)throws Exception {
        UpdateTrust.checkFile(apk,release.size,release.hash);
        ApkVerifier.Result verified=new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(33).build().verify();
        if(!verified.isVerified())throw new IOException("APK 签名验证失败");
        PackageManager pm=app.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(apk.getPath(),0);
        PackageInfo installed=pm.getPackageInfo(app.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null||!app.getPackageName().equals(archive.packageName) )throw new IOException("APK 包名不符");
        if(archive.getLongVersionCode()<=installed.getLongVersionCode()||UpdateTrust.compareVersion(archive.versionName,release.tag)!=0)
            throw new IOException("APK 版本与更新不符");
        if(installed.signingInfo==null)throw new IOException("无法读取当前应用签名");
        Set<String> old=new HashSet<>(),fresh=new HashSet<>();
        for(android.content.pm.Signature value:installed.signingInfo.getApkContentsSigners())old.add(Base64.getEncoder().encodeToString(value.toByteArray()));
        for(java.security.cert.X509Certificate cert:verified.getSignerCertificates())fresh.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
        if(old.isEmpty()||!old.equals(fresh))throw new IOException("新版签名与当前版本不同，无法覆盖安装");
    }
}
