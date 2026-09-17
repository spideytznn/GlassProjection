package io.github.sixzleo.tabfold.projection;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Desktop data is separate from animation preferences and helper state. */
final class HomeStore {
    private final SharedPreferences prefs;
    HomeStore(Context context){this(context,"duo_home");}
    HomeStore(Context context,String name){
        prefs=context.getSharedPreferences(name,0);
        if("duo_home".equals(name))return;
        // Panels mirror the main desktop (MiDuo shares one state): whenever duo_home saved a
        // newer layout, re-seed this panel from it and keep only the panel's own widgets.
        HomeStore main=new HomeStore(context);
        long stamp=main.stamp();
        if(prefs.getLong("seeded_stamp",-1)==stamp&&prefs.contains("panel_seeded"))return;
        HomeLayout source=main.read();
        source.items.removeIf(HomeLayout.Item::widget);
        HomeLayout previous=read();
        for(HomeLayout.Item item:previous.items)if(item.widget()){
            HomeLayout.Item copy=new HomeLayout.Item(item.id,"",Collections.emptyList());
            copy.widgetId=item.widgetId;copy.spanX=item.spanX;copy.spanY=item.spanY;copy.slot=-1;
            source.items.add(copy);
        }
        source.clamp();
        save(source);
        prefs.edit().putBoolean("panel_seeded",true).putBoolean("dock_seeded",true)
            .putBoolean("classified_seeded",true).putLong("seeded_stamp",stamp).apply();
    }
    long stamp(){return prefs.getLong("layout_rev",0);}
    boolean dockSeeded(){return prefs.getBoolean("dock_seeded",false);}
    void markDockSeeded(){prefs.edit().putBoolean("dock_seeded",true).apply();}
    boolean classifiedSeeded(){return prefs.getBoolean("classified_seeded",false);}
    void markClassifiedSeeded(){prefs.edit().putBoolean("classified_seeded",true).apply();}
    HomeLayout read(){
        HomeLayout layout=new HomeLayout();
        try{
            JSONObject root=new JSONObject(prefs.getString("layout","{}"));
            JSONArray items=root.optJSONArray("items");Set<String> placed=new HashSet<>(),ids=new HashSet<>();
            if(items!=null)for(int n=0;n<items.length();n++){
                JSONObject value=items.optJSONObject(n);if(value==null)continue;
                List<String> apps=new ArrayList<>();JSONArray keys=value.optJSONArray("apps");
                if(keys!=null)for(int k=0;k<keys.length();k++){String app=keys.optString(k,"");if(!app.isEmpty()&&placed.add(app))apps.add(app);}
                String id=value.optString("id",UUID.randomUUID().toString());
                if(!ids.add(id))id=UUID.randomUUID().toString();
                int widget=value.optInt("widget",-1);
                if(widget>=0){
                    boolean duplicate=false;for(HomeLayout.Item existing:layout.items)if(existing.widgetId==widget)duplicate=true;
                    if(!duplicate){HomeLayout.Item item=new HomeLayout.Item(id,"",Collections.emptyList());item.widgetId=widget;
                        item.spanX=Math.max(1,Math.min(HomeLayout.COLUMNS,value.optInt("spanX",2)));
                        item.spanY=Math.max(1,Math.min(HomeLayout.ROWS,value.optInt("spanY",2)));
                        item.slot=value.optInt("slot",-1);layout.items.add(item);}
                }else if(!apps.isEmpty()){HomeLayout.Item item=new HomeLayout.Item(id,value.optString("title",""),apps);
                    item.slot=value.optInt("slot",-1);layout.items.add(item);}
            }
            String grid=root.optString("grid","");
            if(!grid.isEmpty()&&!grid.equals(HomeLayout.COLUMNS+"x"+HomeLayout.ROWS)){
                // Grid density changed since this layout was written: keep the visual order,
                // drop the stale coordinates and let ensurePlaced repack at today's density.
                layout.items.sort(java.util.Comparator.comparingInt(item->item.slot));
                for(HomeLayout.Item item:layout.items)item.slot=-1;
            }
            JSONArray dock=root.optJSONArray("dock");if(dock!=null)for(int n=0;n<dock.length();n++){String app=dock.optString(n,"");if(!app.isEmpty())layout.pin(app);}
            JSONArray known=root.optJSONArray("known");if(known!=null)for(int n=0;n<known.length();n++)layout.known.add(known.optString(n));
            layout.known.addAll(placed);layout.page=root.optInt("page",0);layout.clamp();
        }catch(JSONException ignored){prefs.edit().putString("layout_recovery",prefs.getString("layout","{}")).apply();}
        return layout;
    }
    void save(HomeLayout layout){
        prefs.edit().putString("layout",encode(layout)).putLong("layout_rev",prefs.getLong("layout_rev",0)+1).apply();
    }
    private String encode(HomeLayout layout){
        try{
            JSONObject root=new JSONObject();JSONArray items=new JSONArray();
            for(HomeLayout.Item item:layout.items)items.put(new JSONObject().put("id",item.id).put("title",item.title).put("apps",new JSONArray(item.apps))
                .put("widget",item.widgetId).put("spanX",item.spanX).put("spanY",item.spanY).put("slot",item.slot));
            root.put("items",items).put("dock",new JSONArray(layout.dock)).put("known",new JSONArray(layout.known)).put("page",layout.page)
                .put("grid",HomeLayout.COLUMNS+"x"+HomeLayout.ROWS);
            return root.toString();
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    List<Integer> widgets(){
        List<Integer> result=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString("widgets","[]"));
            for(int n=0;n<a.length();n++){int id=a.optInt(n,-1);if(id>=0&&!result.contains(id))result.add(id);}
        }catch(JSONException ignored){}return result;
    }
    void widgets(List<Integer> ids){prefs.edit().putString("widgets",new JSONArray(ids).toString()).apply();}
    void reconcileWidgets(Set<Integer> allocated){
        List<Integer> ids=widgets();boolean changed=ids.removeIf(id->!allocated.contains(id));
        HomeLayout layout=read();changed|=layout.items.removeIf(item->item.widget()&&!ids.contains(item.widgetId));
        if(changed){layout.clamp();prefs.edit().putString("widgets",new JSONArray(ids).toString()).putString("layout",encode(layout)).apply();}
    }
    int pendingWidget(){return prefs.getInt("pending_widget",-1);}
    int pendingWidgetPage(){return prefs.getInt("pending_widget_page",-1);}
    void pendingWidgetPage(int page){prefs.edit().putInt("pending_widget_page",page).apply();}
    @android.annotation.SuppressLint("ApplySharedPref") // Durable before leaving for another app's configuration activity.
    void pendingWidget(int id){prefs.edit().putInt("pending_widget",id).commit();}
    @android.annotation.SuppressLint("ApplySharedPref")
    boolean beginWidget(int id,int page){return prefs.edit().putInt("pending_widget",id).putInt("pending_widget_page",page).commit();}
    @android.annotation.SuppressLint("ApplySharedPref")
    boolean cancelWidget(){return prefs.edit().putInt("pending_widget",-1).putInt("pending_widget_page",-1).commit();}
    boolean finishWidget(int id){return finishWidget(id,2,2);}
    @android.annotation.SuppressLint("ApplySharedPref")
    boolean finishWidget(int id,int width,int height){
        if(pendingWidget()!=id)return false;
        List<Integer> ids=widgets();if(!ids.contains(id))ids.add(id);
        HomeLayout layout=read();
        if(pendingWidgetPage()>=0){HomeLayout.Item item=layout.addWidget(id,pendingWidgetPage(),width,height);layout.page=layout.pageOf(item.id);}
        // One durable commit: an interruption cannot leave a hosted widget without its page placement.
        return prefs.edit().putString("widgets",new JSONArray(ids).toString()).putString("layout",encode(layout))
            .putInt("pending_widget",-1).putInt("pending_widget_page",-1).commit();
    }
    int widgetHeight(int id){return prefs.getInt("widget_height_"+id,0);}
    void widgetHeight(int id,int height){prefs.edit().putInt("widget_height_"+id,height).apply();}
}
