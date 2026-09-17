package io.github.sixzleo.tabfold.projection;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.os.*;
import android.widget.Toast;
import java.util.*;

/** Hosts installed widgets; their providers retain ownership of content and updates. */
final class HomeWidgets {
    static final int BIND=4101, CONFIGURE=4102, RECONFIGURE=4103;
    private final Activity activity;
    private final HomeStore store;
    final AppWidgetManager manager;
    final Host host;
    final List<Integer> ids;
    private final Runnable changed;
    private HomeWidgetPicker picker;
    private AlertDialog recoveryDialog;
    private final Map<Integer,AppWidgetHostView> views=new HashMap<>();
    HomeWidgets(Activity activity,HomeStore store,Runnable changed){
        this.activity=activity;this.store=store;this.changed=changed;
        manager=AppWidgetManager.getInstance(activity);
        host=new Host(activity);
        Set<Integer> allocated=new HashSet<>();for(int id:host.getAppWidgetIds())allocated.add(id);
        store.reconcileWidgets(allocated);ids=store.widgets();
        // Only clean this launcher's allocations, never another home application's widgets.
        for(int id:host.getAppWidgetIds())if(!ids.contains(id)&&id!=store.pendingWidget())host.deleteAppWidgetId(id);
    }
    final class Host extends AppWidgetHost {
        Host(Context context){super(context,context instanceof DuoHomeActivity?((DuoHomeActivity)context).widgetHostId():2701);}
        void resetViews(){clearViews();}
        @Override public void onAppWidgetRemoved(int id){super.onAppWidgetRemoved(id);views.remove(id);
            List<Integer> latest=store.widgets();latest.remove(Integer.valueOf(id));ids.clear();ids.addAll(latest);store.widgets(latest);changed.run();}
    }
    void start(){try{host.startListening();}catch(RuntimeException e){message("小组件暂时无法更新，请重新打开桌面");}}
    HomeStore store(){return store;}
    void stop(){host.stopListening();}
    void close(){dismissPicker();host.resetViews();views.clear();}
    AppWidgetHostView view(int id,AppWidgetProviderInfo info){
        AppWidgetHostView view=views.get(id);if(view==null){view=host.createView(activity,id,info);views.put(id,view);}return view;
    }
    void choose(){
        choose(-1);
    }
    void choose(int page){
        dismissPicker();int pending=store.pendingWidget();
        if(pending>=0){
            AppWidgetProviderInfo info=manager.getAppWidgetInfo(pending);
            AlertDialog.Builder recovery=new AlertDialog.Builder(activity).setTitle("上次添加尚未完成")
                .setMessage(info==null?"上次没有完成系统授权，可以重新选择组件。":"继续完成上次的组件设置，或重新选择组件。")
                .setNegativeButton("稍后",null).setNeutralButton("重新选择",(dialog,which)->{if(cancelPending())openPicker(page);});
            if(info!=null)recovery.setPositiveButton("继续添加",(dialog,which)->configure(pending));
            AlertDialog dialog=recovery.create();recoveryDialog=dialog;
            dialog.setOnDismissListener(ignored->{if(recoveryDialog==dialog)recoveryDialog=null;});
            dialog.show();return;
        }
        openPicker(page);
    }
    private void openPicker(int page){picker=new HomeWidgetPicker(activity,provider->{bind(provider,page);});picker.show();}
    void dismissPicker(){
        if(picker!=null){picker.dismiss();picker=null;}
        if(recoveryDialog!=null){AlertDialog dialog=recoveryDialog;recoveryDialog=null;dialog.dismiss();}
    }
    /** Migration entry: runs the standard bind flow, opening the system confirmation. */
    void addForMigration(AppWidgetProviderInfo provider,int page){bind(provider,page);}
    private void bind(AppWidgetProviderInfo provider,int page){        if(!cancelPending())return;int id=host.allocateAppWidgetId();
        if(!store.beginWidget(id,page)){host.deleteAppWidgetId(id);message("无法保存组件位置，请稍后重试");return;}
        Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        try{
            if(manager.bindAppWidgetIdIfAllowed(id,provider.getProfile(),provider.provider,options))configure(id);
            else activity.startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,provider.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,provider.getProfile())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS,options),BIND);
        }catch(RuntimeException e){cancelPending();message("系统未允许添加这个小组件");}
    }
    private void configure(int id){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(id);
        if(info==null){cancelPending();message("小组件绑定未完成");return;}
        // Providers may flag their configure step as optional (MiDuo does the same check).
        boolean optional=(info.widgetFeatures&AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL)!=0;
        if(info.configure!=null&&!optional){
            try{host.startAppWidgetConfigureActivityForResult(activity,id,0,CONFIGURE,null);}
            catch(RuntimeException e){cancelPending();message("无法打开小组件设置");}
        }else complete(id);
    }
    void reconfigure(int id){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(id);
        if(info==null||info.configure==null){message("这个小组件没有设置页");return;}
        try{host.startAppWidgetConfigureActivityForResult(activity,id,0,RECONFIGURE,null);}
        catch(RuntimeException e){message("无法打开小组件设置");}
    }
    boolean result(int request,int result,Intent data){
        if(request==RECONFIGURE){changed.run();return true;}
        if(request!=BIND&&request!=CONFIGURE)return false;
        int id=store.pendingWidget();if(id<0)return true;
        if(data!=null&&data.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)&&data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)!=id)return true;
        if(result!=Activity.RESULT_OK){cancelPending();return true;}
        if(request==BIND)configure(id);else complete(id);return true;
    }
    private void complete(int id){
        AppWidgetProviderInfo info=manager.getAppWidgetInfo(id);
        if(info==null){cancelPending();message("小组件已不可用");return;}
        int[] span=defaultSpan(info);
        if(!store.finishWidget(id,span[0],span[1])){message("组件位置未能保存，请稍后重试");return;}
        if(!ids.contains(id))ids.add(id);changed.run();
    }
    int[] defaultSpan(AppWidgetProviderInfo info){
        float[] geometry=gridGeometry();
        return new int[]{defaultSpan(info.targetCellWidth,info.minWidth,geometry[0],geometry[2],HomeLayout.COLUMNS),
            defaultSpan(info.targetCellHeight,info.minHeight,geometry[1],geometry[2],HomeLayout.ROWS)};
    }
    private float[] gridGeometry(){
        float density=activity.getResources().getDisplayMetrics().density;
        android.view.View grid=activity.getWindow().getDecorView().findViewWithTag("home-app-grid");
        android.view.View viewport=grid!=null&&grid.getParent() instanceof android.view.View?(android.view.View)grid.getParent():null;
        float cellWidth=viewport!=null&&viewport.getWidth()>0?viewport.getWidth()/(float)HomeLayout.COLUMNS:80*density;
        float rowPitch=96*density;
        float cellHeight=viewport!=null&&viewport.getHeight()>0?Math.min(rowPitch,viewport.getHeight()/(float)HomeLayout.ROWS):rowPitch;
        return new float[]{cellWidth,cellHeight,(HomeLayout.COLUMNS)*density};
    }
    List<int[]> resizeChoices(AppWidgetProviderInfo info,HomeLayout.Item item){
        float[] geometry=gridGeometry();List<int[]> result=new ArrayList<>();
        for(int y=1;y<=HomeLayout.ROWS;y++)for(int x=1;x<=HomeLayout.COLUMNS;x++){
            if(HomeWidgetResize.allows(x,item.spanX,(info.resizeMode&AppWidgetProviderInfo.RESIZE_HORIZONTAL)!=0,info.minWidth,info.minResizeWidth,info.maxResizeWidth,geometry[0],geometry[2])
                &&HomeWidgetResize.allows(y,item.spanY,(info.resizeMode&AppWidgetProviderInfo.RESIZE_VERTICAL)!=0,info.minHeight,info.minResizeHeight,info.maxResizeHeight,geometry[1],geometry[2]))result.add(new int[]{x,y});
        }
        return result;
    }
    static int defaultSpan(int target,int pixels,float cell,float margin,int max){return Math.max(1,Math.min(max,target>0?target:(int)Math.ceil((pixels+margin)/Math.max(1,cell))));}
    private boolean cancelPending(){
        int id=store.pendingWidget();
        if(!store.cancelWidget()){message("无法保存组件状态，请稍后重试");return false;}
        if(id>=0)host.deleteAppWidgetId(id);return true;
    }
    void remove(int id){ids.remove(Integer.valueOf(id));views.remove(id);store.widgets(ids);store.widgetHeight(id,0);host.deleteAppWidgetId(id);changed.run();}
    void move(int id,int delta){int index=ids.indexOf(id),target=index+delta;if(index<0||target<0||target>=ids.size())return;
        Collections.swap(ids,index,target);store.widgets(ids);changed.run();}
    private void message(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
}
