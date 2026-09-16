package io.github.sixzleo.tabfold.projection;

import java.io.*;
import java.security.*;
import java.util.*;

public final class UpdateTrust {
    public static final String REPO="https://github.com/spideytznn/GlassProjection";
    public static final String RELEASES=REPO+"/releases/latest";
    public static final String API="https://api.github.com/repos/spideytznn/GlassProjection/releases/latest";
    public static final String[] SOURCES={"", "https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/"};
    public static final String[] SOURCE_NAMES={"GitHub", "镜像 1 · ghfast.top", "镜像 2 · gh-proxy.com", "镜像 3 · ghproxy.net"};
    public static final long MAX_APK=100*1024*1024;
    public static boolean ownDownload(String url){return url.matches("https://github\\.com/spideytznn/GlassProjection/releases/download/[A-Za-z0-9][A-Za-z0-9._-]{0,79}/[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.apk");}
    public static boolean ownRelease(String url){return url.equals(RELEASES)||url.matches("https://github\\.com/spideytznn/GlassProjection/releases/tag/[A-Za-z0-9][A-Za-z0-9._-]{0,79}");}
    public static int compareVersion(String left,String right){
        Version a=version(left),b=version(right);
        for(int i=0;i<Math.max(a.core.length,b.core.length);i++){
            int result=Integer.compare(i<a.core.length?Integer.parseInt(a.core[i]):0,i<b.core.length?Integer.parseInt(b.core[i]):0);
            if(result!=0)return result;
        }
        // A final release follows its previews, but a 0.5 preview still follows 0.4.
        if(a.pre.length==0)return b.pre.length==0?0:1;
        if(b.pre.length==0)return -1;
        for(int i=0;i<Math.min(a.pre.length,b.pre.length);i++){
            String x=a.pre[i],y=b.pre[i];boolean nx=x.matches("[0-9]+"),ny=y.matches("[0-9]+");
            int result;
            if(nx&&ny){result=Integer.compare(x.length(),y.length());if(result==0)result=x.compareTo(y);}
            else result=nx!=ny?(nx?-1:1):x.compareTo(y);
            if(result!=0)return result;
        }
        return Integer.compare(a.pre.length,b.pre.length);
    }
    public static boolean isStableVersion(String value){return version(value).pre.length==0;}
    private static Version version(String value){
        if(value==null||value.length()>128||!value.matches("v?[0-9]{1,6}(\\.[0-9]{1,6}){1,3}(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?"))
            throw new IllegalArgumentException("无法识别发布版本号");
        String[] parts=value.replaceFirst("^v","").split("\\+",2)[0].split("-",2);
        String[] pre=parts.length==1?new String[0]:parts[1].split("\\.");
        for(String id:pre)if(id.matches("0[0-9]+"))throw new IllegalArgumentException("预发布版本序号不能有前导零");
        return new Version(parts[0].split("\\."),pre);
    }
    private static final class Version {
        final String[] core,pre;
        Version(String[] core,String[] pre){this.core=core;this.pre=pre;}
    }
    public static String sha256(File file)throws IOException {
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)hash.update(bytes,0,n);}
            StringBuilder result=new StringBuilder();for(byte b:hash.digest())result.append(String.format(Locale.ROOT,"%02x",b));return result.toString();
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static void checkFile(File file,long size,String hash)throws IOException {
        if(file.length()!=size||!hash.isEmpty()&&!sha256(file).equals(hash))throw new IOException("APK 下载不完整或校验失败");
    }
}
