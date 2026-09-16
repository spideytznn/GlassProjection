package io.github.sixzleo.tabfold.projection;

import org.json.*;
import java.io.IOException;

/** Uses normal GitHub Releases and APK assets, with no custom update manifest. */
final class ApkRelease {
    final String tag,url,page,notes,hash,name;
    final long size;
    ApkRelease(byte[] json)throws Exception {
        JSONObject release=new JSONObject(new String(json,java.nio.charset.StandardCharsets.UTF_8));
        if(release.optBoolean("draft")||release.optBoolean("prerelease"))throw new IOException("不是正式发布版本");
        tag=release.getString("tag_name");
        if(!UpdateTrust.isStableVersion(tag))throw new IOException("不是正式发布版本");
        page=release.getString("html_url");if(!UpdateTrust.ownRelease(page))throw new IOException("发布链接不属于本项目");
        String body=release.optString("body","");notes=body.length()>12000?body.substring(0,12000)+"\n更多内容请查看 Release。":body;
        JSONArray assets=release.getJSONArray("assets");JSONObject selected=null,onlyApk=null;int apkCount=0;
        String expected="GlassProjection-"+tag.replaceFirst("^v","")+".apk";
        for(int i=0;i<assets.length();i++){
            JSONObject asset=assets.getJSONObject(i);String file=asset.optString("name","");
            if(expected.equals(file))selected=asset;
            if(file.endsWith(".apk")){onlyApk=asset;apkCount++;}
        }
        if(selected==null&&apkCount==1)selected=onlyApk;
        if(selected==null)throw new IOException(apkCount==0?"该 Release 尚未提供 APK":"该 Release 有多个 APK，请从发布页选择");
        name=selected.getString("name");url=selected.getString("browser_download_url");size=selected.getLong("size");
        if(!UpdateTrust.ownDownload(url)||size<=0||size>UpdateTrust.MAX_APK)throw new IOException("APK 下载信息无效");
        String digest=selected.optString("digest","");
        if(digest.isEmpty()||digest.equals("null"))hash="";
        else if(digest.matches("sha256:[0-9a-f]{64}"))hash=digest.substring(7);
        else throw new IOException("无法识别 APK 校验值");
    }
    String json(){
        try{
            JSONObject asset=new JSONObject().put("name",name).put("browser_download_url",url).put("size",size);
            if(!hash.isEmpty())asset.put("digest","sha256:"+hash);
            return new JSONObject().put("tag_name",tag).put("html_url",page).put("body",notes)
                .put("assets",new JSONArray().put(asset)).toString();
        }catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
}
