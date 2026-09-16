package io.github.sixzleo.tabfold.projection;

import java.util.*;

/** Stable desktop order, independent of either display's pixel dimensions. */
final class HomeLayout {
    static final int PAGE_SIZE=16, DOCK_SIZE=4;
    static final class Item {
        final String id;
        String title;
        int widgetId=-1,spanX=1,spanY=1;
        final List<String> apps=new ArrayList<>();
        Item(String id,String title,Collection<String> apps){this.id=id;this.title=title;this.apps.addAll(apps);}
        boolean folder(){return apps.size()>1;}
        boolean widget(){return widgetId>=0;}
    }
    static final class Cell {
        final Item item;final int page,x,y,width,height;
        Cell(Item item,int page,int x,int y,int width,int height){this.item=item;this.page=page;this.x=x;this.y=y;this.width=width;this.height=height;}
    }
    final List<Item> items=new ArrayList<>();
    final List<String> dock=new ArrayList<>();
    final Set<String> known=new HashSet<>();
    int page;
    Item find(String id){for(Item i:items)if(i.id.equals(id))return i;return null;}
    void discover(Collection<String> apps){for(String app:apps)if(known.add(app))add(app);clamp();}
    void add(String app){
        known.add(app);
        for(Item item:items)if(item.apps.contains(app))return;
        items.add(new Item(UUID.randomUUID().toString(),"",Collections.singletonList(app)));
    }
    List<Cell> cells(){
        List<Cell> result=new ArrayList<>();boolean[] occupied=new boolean[16];int current=0;
        for(Item item:items){
            int width=item.widget()?Math.max(1,Math.min(4,item.spanX)):1,height=item.widget()?Math.max(1,Math.min(4,item.spanY)):1;
            int found=-1;
            while(found<0){
                for(int i=0;i<16&&found<0;i++){
                    int x=i%4,y=i/4;if(x+width>4||y+height>4)continue;boolean free=true;
                    for(int dy=0;dy<height;dy++)for(int dx=0;dx<width;dx++)if(occupied[(y+dy)*4+x+dx])free=false;
                    if(free)found=i;
                }
                if(found<0){current++;occupied=new boolean[16];}
            }
            int x=found%4,y=found/4;
            for(int dy=0;dy<height;dy++)for(int dx=0;dx<width;dx++)occupied[(y+dy)*4+x+dx]=true;
            result.add(new Cell(item,current,x,y,width,height));
        }
        return result;
    }
    int pages(){List<Cell> cells=cells();return cells.isEmpty()?1:cells.get(cells.size()-1).page+1;}
    int pageOf(String id){for(Cell cell:cells())if(cell.item.id.equals(id))return cell.page;return 0;}
    int pageStart(int page){for(Cell cell:cells())if(cell.page>=page)return items.indexOf(cell.item);return items.size();}
    Item addWidget(int id,int page,int width,int height){
        for(Item item:items)if(item.widgetId==id)return item;
        Item item=new Item("widget:"+id,"",Collections.emptyList());item.widgetId=id;item.spanX=width;item.spanY=height;
        items.add(pageStart(page),item);return item;
    }
    void clamp(){page=Math.max(0,Math.min(page,pages()-1));}
    void remove(String id){items.removeIf(i->i.id.equals(id));clamp();}
    void move(String id,int target){
        Item item=find(id);if(item==null)return;
        int old=items.indexOf(item);items.remove(old);
        if(target>old)target--;
        items.add(Math.max(0,Math.min(target,items.size())),item);clamp();
    }
    boolean merge(String source,String target){
        Item a=find(source),b=find(target);
        if(a==null||b==null||a==b||a.widget()||b.widget())return false;
        for(String key:a.apps)if(!b.apps.contains(key))b.apps.add(key);
        if(b.title.isEmpty())b.title="文件夹";
        items.remove(a);clamp();return true;
    }
    void dissolve(String id){
        Item item=find(id);if(item==null||!item.folder())return;
        int index=items.indexOf(item);items.remove(index);
        for(String app:item.apps)items.add(index++,new Item(UUID.randomUUID().toString(),"",Collections.singletonList(app)));
        clamp();
    }
    void extract(String id,String app){
        Item item=find(id);if(item==null||!item.apps.remove(app))return;
        if(item.apps.isEmpty())items.remove(item);
        if(item.apps.size()==1)item.title="";
        add(app);clamp();
    }
    boolean pin(String app){if(dock.contains(app))return true;if(dock.size()>=DOCK_SIZE)return false;dock.add(app);return true;}
}
