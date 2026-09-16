package io.github.sixzleo.tabfold.projection;

import java.util.*;

public final class HomeLayoutTest {
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    private static Set<String> contents(HomeLayout model){
        Set<String> apps=new HashSet<>(),ids=new HashSet<>();
        for(HomeLayout.Item item:model.items){check(ids.add(item.id),"unique item IDs");check(!item.apps.isEmpty(),"no empty folder");
            for(String app:item.apps)check(apps.add(app),"one workspace location per app");}
        check(model.page>=0&&model.page<model.pages(),"valid page after mutation");return apps;
    }
    public static void main(String[] args){
        HomeLayout mixed=new HomeLayout();for(int i=0;i<36;i++)mixed.add("mixed/"+i);
        HomeLayout.Item widget=mixed.addWidget(101,0,2,2);mixed.addWidget(102,1,4,2);
        check(mixed.merge(widget.id,mixed.items.get(1).id)==false,"widgets never merge into folders");
        for(int iteration=0;iteration<100;iteration++){
            widget.spanX=iteration%2==0?2:4;widget.spanY=2+iteration%3;
            mixed.move(widget.id,iteration%mixed.items.size());
            Set<String> occupied=new HashSet<>();Set<String> itemIds=new HashSet<>();
            for(HomeLayout.Cell cell:mixed.cells()){
                check(itemIds.add(cell.item.id),"each item has one placement");
                check(cell.x+cell.width<=4&&cell.y+cell.height<=4,"no widget crosses page bounds");
                for(int y=cell.y;y<cell.y+cell.height;y++)for(int x=cell.x;x<cell.x+cell.width;x++)
                    check(occupied.add(cell.page+":"+x+":"+y),"widgets and applications never overlap");
                check(mixed.pageOf(cell.item.id)==cell.page,"page lookup follows mixed packing");
            }
            check(itemIds.size()==mixed.items.size(),"all mixed items preserved");
        }
        HomeLayout model=new HomeLayout();List<String> initial=new ArrayList<>();for(int n=0;n<49;n++)initial.add("app/"+n+"@0");model.discover(initial);
        check(model.pages()==4,"partial last page");String removed=model.items.get(3).apps.get(0);model.remove(model.items.get(3).id);
        model.discover(initial);check(!contents(model).contains(removed),"removed shortcut stays removed after catalog refresh");
        model.add(removed);Set<String> expected=new HashSet<>(initial);Random random=new Random(42);
        for(int n=0;n<1500;n++){
            HomeLayout.Item a=model.items.get(random.nextInt(model.items.size()));int action=random.nextInt(4);
            if(action==0)model.move(a.id,random.nextInt(70)-10);
            else if(action==1)model.merge(a.id,model.items.get(random.nextInt(model.items.size())).id);
            else if(action==2)model.dissolve(a.id);
            else if(a.folder())model.extract(a.id,a.apps.get(0));
            check(contents(model).equals(expected),"reorder/folder operations conserve all apps");
        }
        for(int n=0;n<4;n++)check(model.pin(initial.get(n)),"four dock slots");
        check(!model.pin(initial.get(4))&&model.dock.size()==4,"full dock rejects without data loss");
        check(model.pin(initial.get(0))&&model.dock.size()==4,"dock deduplicates");
        model.page=model.pages()-1;while(model.items.size()>1)model.remove(model.items.get(0).id);check(model.page==0,"page clamped after removal");
        System.out.println("PASS: desktop pages, removed shortcut persistence, 1500 folder/reorder conservation operations, dock capacity");
    }
}
