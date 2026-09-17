package io.github.sixzleo.tabfold.projection;

import java.util.*;

/**
 * First-launch desktop seeding, ported from MiDuo's O6 tables: leading singles fill page one,
 * category folders with fixed ids open page two, and a folder is only created for >=2 matches.
 */
final class HomeClassify {
    private HomeClassify(){}
    /** Page-one singles in MiDuo's order; each group lists interchangeable packages. */
    private static final String[][] LEADING={
        {"com.miui.gallery","com.google.android.apps.photos","com.sec.android.gallery3d"},
        {"com.android.settings"},
        {"com.android.deskclock","com.google.android.deskclock","com.sec.android.app.clockpackage"},
        {"com.android.fileexplorer","com.mi.android.globalFileexplorer","com.sec.android.app.myfiles"},
        {"com.tencent.mm"},
        {"com.eg.android.AlipayGphone"},
        {"com.xingin.xhs"},
        {"com.autonavi.minimap","com.baidu.BaiduMap"},
        {"com.xiaomi.smarthome"},
        {"com.xiaomi.market"},
        {"com.android.calendar","com.google.android.calendar"},
        {"com.miui.weather2"},
    };
    private static final class Category{
        final String id,title;final String[][] groups;
        Category(String id,String title,String[][] groups){this.id=id;this.title=title;this.groups=groups;}
    }
    private static final Category[] CATEGORIES={
        new Category("22000000-0000-0000-0000-000000000001","AI",new String[][]{
            {"com.larus.nova"},{"com.openai.chatgpt"},{"com.miui.voiceassistProxy"}}),
        new Category("22000000-0000-0000-0000-000000000002","影音视听",new String[][]{
            {"tv.danmaku.bili"},{"com.youku.phone"},{"com.tencent.qqlive"},{"com.trim.media"},
            {"com.ss.android.ugc.aweme"},{"com.qiyi.video"},{"com.cmcc.cmvideo"},{"com.trim.app"},
            {"com.hunantv.imgo.activity"},{"com.miui.video"},{"com.miui.player"}}),
        new Category("22000000-0000-0000-0000-000000000003","体育运动",new String[][]{
            {"com.hupu.games"},{"com.dongqiudi.news"},{"com.hupu.shihuo"},{"com.mi.health"},{"com.huawei.health"}}),
        new Category("22000000-0000-0000-0000-000000000004","时尚购物",new String[][]{
            {"com.xiaomi.shop"},{"com.taobao.taobao"},{"com.xunmeng.pinduoduo"},{"com.smzdm.client.android"},
            {"com.wudaokou.hippo"},{"com.jingdong.app.mall"},{"com.mcdonalds.gma.cn"},{"com.dianping.v1"},
            {"com.taobao.idlefish"},{"com.alibaba.wireless"}}),
        new Category("22000000-0000-0000-0000-000000000005","效率办公",new String[][]{
            {"com.android.email"},{"com.alibaba.android.rimet"},{"com.netease.mail"},{"com.baidu.netdisk"},
            {"com.qq.qcloud"},{"md.obsidian"},{"cn.wps.moffice_eng"},{"com.alibaba.aliyun"}}),
        new Category("22000000-0000-0000-0000-000000000006","聊天社交",new String[][]{
            {"com.tencent.mm"},{"com.tencent.mobileqq"},{"com.tencent.wework"},{"com.ss.android.lark"}}),
        new Category("22000000-0000-0000-0000-000000000007","新闻资讯",new String[][]{
            {"com.sina.weibo"},{"com.coolapk.market"},{"com.tencent.weread"},{"com.douban.frodo"},
            {"cn.com.sina.finance"},{"cn.damai"},{"com.duokan.reader"},{"com.zhihu.android"},{"com.quark.browser"}}),
        new Category("22000000-0000-0000-0000-000000000008","实用工具",new String[][]{
            {"com.miui.calculator"},{"com.miui.notes"},{"com.android.soundrecorder"},{"com.xiaomi.scanner"},
            {"com.miui.compass"},{"com.miui.securitycenter","com.miui.securitymanager"},{"com.duokan.phone.remotecontroller"},
            {"com.android.providers.downloads.ui"},{"com.android.virtualization.terminal"},{"bin.mt.plus"}}),
        new Category("22000000-0000-0000-0000-000000000009","旅行交通",new String[][]{
            {"com.sdu.didi.psnger"},{"ctrip.android.view"},{"com.umetrip.android.msky.app"},{"com.china3s.android"},
            {"com.autonavi.minimap"},{"com.baidu.BaiduMap"},{"com.MobileTicket"},{"com.plateno.botaoota"},
            {"com.disney.shanghaidisneyland_goo"},{"com.taobao.trip"},{"com.zuzuChe"}}),
        new Category("22000000-0000-0000-0000-000000000010","金融理财",new String[][]{
            {"com.antfortune.wealth"},{"com.unionpay"},{"cmb.pb"},{"cn.com.cmbc.newmbank"},
            {"com.pingan.paces.ccms"},{"com.chinamworld.main"},{"com.android.bankabc"},
            {"com.ecitic.bank.mobile"},{"com.zxscnew"},{"com.mipay.wallet"}}),
    };

    /** Regroups a freshly discovered all-singles layout; shared matches keep MiDuo's leading-first priority. */
    static void apply(HomeLayout layout,Map<String,HomeApps.App> apps){
        Map<String,String> keyByPackage=new LinkedHashMap<>();
        for(HomeApps.App app:apps.values())keyByPackage.putIfAbsent(app.component.getPackageName(),app.key);
        Map<String,HomeLayout.Item> singles=new HashMap<>();
        for(HomeLayout.Item item:layout.items)
            if(!item.widget()&&!item.folder())singles.put(item.apps.get(0),item);
        Set<String> used=new HashSet<>();
        List<HomeLayout.Item> leadingList=new ArrayList<>();
        for(String[] group:LEADING){HomeLayout.Item found=pick(group,keyByPackage,singles,used);if(found!=null)leadingList.add(found);}
        List<HomeLayout.Item> folders=new ArrayList<>();
        for(Category category:CATEGORIES){
            List<String> members=new ArrayList<>();
            for(String[] group:category.groups){HomeLayout.Item found=pick(group,keyByPackage,singles,used);if(found!=null)members.add(found.apps.get(0));}
            if(members.size()>=2)folders.add(new HomeLayout.Item(category.id,category.title,members));
        }
        List<HomeLayout.Item> rest=new ArrayList<>();
        for(HomeLayout.Item item:layout.items)if(!used.contains(item.id))rest.add(item);
        // Page one: leading singles padded with the pinyin-ordered remainder; folders open page two.
        List<HomeLayout.Item> ordered=new ArrayList<>(leadingList);
        int pad=Math.max(0,HomeLayout.PAGE_SIZE-ordered.size());
        int split=Math.min(pad,rest.size());
        ordered.addAll(rest.subList(0,split));
        ordered.addAll(folders);
        ordered.addAll(rest.subList(split,rest.size()));
        layout.items.clear();
        for(int i=0;i<ordered.size();i++){HomeLayout.Item item=ordered.get(i);item.slot=i;layout.items.add(item);}
        layout.clamp();
    }
    /** First unused installed app among a group's interchangeable packages, MiDuo's d() rule. */
    private static HomeLayout.Item pick(String[] group,Map<String,String> keyByPackage,Map<String,HomeLayout.Item> singles,Set<String> used){
        for(String pkg:group){
            HomeLayout.Item item=singles.get(keyByPackage.get(pkg));
            if(item!=null&&!used.contains(item.id)){used.add(item.id);return item;}
        }
        return null;
    }
}
