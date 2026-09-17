package io.github.sixzleo.tabfold.projection;

import java.util.*;

/**
 * MiDuo-style slot model: every item owns a flat cell (page*PAGE_SIZE+y*COLUMNS+x), widgets
 * claim their whole span rectangle and can never be displaced, and dragging onto an occupied
 * app cell shifts the chain toward the source instead of swapping (参考/MiDuo-实现分析.md §三.1).
 */
final class HomeLayout {
    /** Workspace grid: 4 columns × 5 rows per page (MiDuo slot semantics, original density). */
    static final int COLUMNS=4,ROWS=5,PAGE_SIZE=COLUMNS*ROWS,DOCK_SIZE=4;
    static final class Item {
        final String id;
        String title;
        int widgetId=-1,spanX=1,spanY=1;
        int slot=-1;
        final List<String> apps=new ArrayList<>();
        Item(String id,String title,Collection<String> apps){this.id=id;this.title=title;this.apps.addAll(apps);}
        boolean folder(){return apps.size()>1;}
        boolean widget(){return widgetId>=0;}
        int width(){return widget()?Math.max(1,Math.min(COLUMNS,spanX)):1;}
        int height(){return widget()?Math.max(1,Math.min(ROWS,spanY)):1;}
    }
    static final class Cell {
        final Item item;final int page,x,y,width,height;
        Cell(Item item,int page,int x,int y,int width,int height){this.item=item;this.page=page;this.x=x;this.y=y;this.width=width;this.height=height;}
    }
    final List<Item> items=new ArrayList<>();
    final List<String> dock=new ArrayList<>();
    final Set<String> known=new HashSet<>();
    int page;

    private final Map<Integer,Item> occupied=new HashMap<>();
    private boolean dirty=true;
    private void touch(){dirty=true;}
    private Map<Integer,Item> grid(){
        if(!dirty)return occupied;
        occupied.clear();
        for(Item item:items){
            if(item.slot<0)continue; // not yet placed: never fake-occupy cell 0
            int base=item.slot,w=item.width(),h=item.height();
            int x=base%PAGE_SIZE%COLUMNS,y=base%PAGE_SIZE/COLUMNS;
            for(int dy=0;dy<h&&y+dy<ROWS;dy++)for(int dx=0;dx<w&&x+dx<COLUMNS;dx++)
                occupied.put(base/PAGE_SIZE*PAGE_SIZE+(y+dy)*COLUMNS+x+dx,item);
        }
        dirty=false;return occupied;
    }
    /** Packs any never-placed item (store migration, importer) row-major from the first page. */
    void ensurePlaced(){
        for(Item item:items)if(item.slot<0){item.slot=firstFree(item.width(),item.height(),0);touch();}
    }
    private boolean rectFree(int slot,int w,int h,Item ignore){
        int base=Math.max(0,slot),x=base%PAGE_SIZE%COLUMNS,y=base%PAGE_SIZE/COLUMNS;
        if(x+w>COLUMNS||y+h>ROWS)return false;
        Map<Integer,Item> cells=grid();
        for(int dy=0;dy<h;dy++)for(int dx=0;dx<w;dx++){
            Item owner=cells.get(base/PAGE_SIZE*PAGE_SIZE+(y+dy)*COLUMNS+x+dx);
            if(owner!=null&&owner!=ignore)return false;
        }
        return true;
    }
    private int firstFree(int w,int h,int fromPage){
        for(int p=Math.max(0,fromPage);p<=pages();p++)
            for(int y=0;y+h<=ROWS;y++)for(int x=0;x+w<=COLUMNS;x++){
                int slot=p*PAGE_SIZE+y*COLUMNS+x;
                if(rectFree(slot,w,h,null))return slot;
            }
        return pages()*PAGE_SIZE;
    }
    private int nextFreeFrom(int from){
        Map<Integer,Item> cells=grid();
        for(int s=Math.max(0,from);s<(pages()+1)*PAGE_SIZE;s++)if(cells.get(s)==null)return s;
        return (pages()+1)*PAGE_SIZE;
    }

    Item find(String id){for(Item i:items)if(i.id.equals(id))return i;return null;}
    void discover(Collection<String> apps){for(String app:apps)if(known.add(app))add(app);clamp();}
    void add(String app){
        known.add(app);
        for(Item item:items)if(item.apps.contains(app))return;
        Item item=new Item(UUID.randomUUID().toString(),"",Collections.singletonList(app));
        item.slot=firstFree(1,1,0);items.add(item);touch();
    }
    List<Cell> cells(){
        ensurePlaced();
        List<Cell> result=new ArrayList<>();
        for(Item item:items){
            int base=Math.max(0,item.slot);
            result.add(new Cell(item,base/PAGE_SIZE,base%PAGE_SIZE%COLUMNS,base%PAGE_SIZE/COLUMNS,item.width(),item.height()));
        }
        result.sort(Comparator.comparingInt(c->c.page*PAGE_SIZE+c.y*COLUMNS+c.x));
        return result;
    }
    int pages(){
        int count=1;
        for(Item item:items){
            if(item.slot<0)continue;
            int base=item.slot,bottom=base%PAGE_SIZE/COLUMNS+item.height()-1;
            count=Math.max(count,base/PAGE_SIZE+bottom/ROWS+1);
        }
        return count;
    }
    int pageOf(String id){Item item=find(id);return item==null?0:Math.max(0,item.slot)/PAGE_SIZE;}
    int pageStart(int page){return page*PAGE_SIZE;}
    Item addWidget(int id,int page,int width,int height){
        for(Item item:items)if(item.widgetId==id)return item;
        Item item=new Item("widget:"+id,"",Collections.emptyList());item.widgetId=id;
        item.spanX=Math.max(1,Math.min(COLUMNS,width));item.spanY=Math.max(1,Math.min(ROWS,height));
        item.slot=firstFree(item.spanX,item.spanY,Math.max(0,page));items.add(item);return item;
    }
    void clamp(){ensurePlaced();page=Math.max(0,Math.min(page,pages()-1));}
    void remove(String id){if(items.removeIf(i->i.id.equals(id)))touch();clamp();}

    /**
     * MiDuo A2.d: a free target is taken directly; an occupied app cell shifts every item
     * between source and target one step toward the source; a widget target is rejected and
     * any widget in between blocks the whole shift. Widgets may only land on free rectangles.
     */
    void move(String id,int target){
        Item item=find(id);if(item==null)return;
        ensurePlaced();
        int limit=(pages()+1)*PAGE_SIZE;
        target=Math.max(0,Math.min(target,limit-1));
        if(item.widget()){
            if(rectFree(target,item.width(),item.height(),item)){item.slot=target;touch();}
            clamp();return;
        }
        int from=Math.max(0,item.slot);
        if(target==from){clamp();return;}
        Map<Integer,Item> cells=grid();
        Item owner=cells.get(target);
        if(owner==null||owner==item){item.slot=target;touch();clamp();return;}
        if(owner.widget()){clamp();return;}
        boolean forward=target>from;
        for(int s=from+(forward?1:-1);forward?s<=target:s>=target;s+=forward?1:-1){
            Item between=cells.get(s);
            if(between!=null&&between!=item&&between.widget())return; // nothing moves past a widget
        }
        int free=from;
        for(int s=from+(forward?1:-1);forward?s<=target:s>=target;s+=forward?1:-1){
            Item between=cells.get(s);
            if(between==null||between==item)continue;
            int previous=between.slot;between.slot=free;free=previous;
        }
        item.slot=target;touch();clamp();
    }

    boolean merge(String source,String target){
        Item a=find(source),b=find(target);
        if(a==null||b==null||a==b||a.widget()||b.widget())return false;
        for(String key:a.apps)if(!b.apps.contains(key))b.apps.add(key);
        if(b.title.isEmpty())b.title="文件夹";
        items.remove(a);touch();clamp();return true;
    }
    /** MiDuo dissolve: the first member keeps the folder's cell, the rest fill following free cells. */
    void dissolve(String id){
        Item item=find(id);if(item==null||!item.folder())return;
        int slot=Math.max(0,item.slot);
        items.remove(item);
        int at=slot;
        for(String app:item.apps){
            Item single=new Item(UUID.randomUUID().toString(),"",Collections.singletonList(app));
            single.slot=at;items.add(single);touch();
            at=nextFreeFrom(at+1);
        }
        clamp();
    }
    /** MiDuo k0: an extracted app lands right after its folder, or in the folder's own cell. */
    void extract(String id,String app){
        Item item=find(id);if(item==null||!item.apps.remove(app))return;
        int at=Math.max(0,item.slot);
        boolean gone=item.apps.isEmpty();
        if(gone)items.remove(item);
        else if(item.apps.size()==1)item.title="";
        Item single=new Item(UUID.randomUUID().toString(),"",Collections.singletonList(app));
        known.add(app);
        single.slot=gone?at:nextFreeFrom(at+1);
        items.add(single);touch();clamp();
    }
    private void removeFromGrid(String app){
        for(Item item:new ArrayList<>(items))if(item.apps.contains(app)){
            if(item.folder()){
                item.apps.remove(app);
                if(item.apps.size()==1)item.title="";
            }else items.remove(item);
            touch();
        }
    }
    /** Dock membership is exclusive with the desktop (MiDuo): pinning pulls the app off the grid. */
    boolean pin(String app){
        if(dock.contains(app))return true;
        if(dock.size()>=DOCK_SIZE)return false;
        removeFromGrid(app);
        dock.add(app);touch();return true;
    }
    /** MiDuo off-desktop drop: a free target is taken, an occupied one shifts to the next free cell. */
    void undock(String app,int target){
        dock.remove(app);
        for(Item item:new ArrayList<>(items))if(item.apps.contains(app)){move(item.id,target);return;}
        Item single=new Item(UUID.randomUUID().toString(),"",Collections.singletonList(app));
        known.add(app);
        target=Math.max(0,Math.min(target,(pages()+1)*PAGE_SIZE-1));
        single.slot=grid().get(target)==null?target:nextFreeFrom(target);
        items.add(single);touch();clamp();
    }
    /** Widgets resize only within their free rectangle (MiDuo p validation). */
    boolean resize(String id,int spanX,int spanY){
        Item item=find(id);if(item==null||!item.widget())return false;
        int w=Math.max(1,Math.min(COLUMNS,spanX)),h=Math.max(1,Math.min(ROWS,spanY));
        int base=Math.max(0,item.slot),x=base%PAGE_SIZE%COLUMNS,y=base%PAGE_SIZE/COLUMNS;
        if(x+w>COLUMNS||y+h>ROWS||!rectFree(base,w,h,item))return false;
        item.spanX=w;item.spanY=h;touch();return true;
    }
}
