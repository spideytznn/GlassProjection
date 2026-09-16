package io.github.sixzleo.tabfold.projection;

public final class HomeWidgetResizeTest {
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    public static void main(String[] args){
        check(HomeWidgetResize.allows(2,2,false,300,100,400,100,4),"fixed current span preserved on a smaller display");
        check(!HomeWidgetResize.allows(3,2,false,180,80,400,100,4),"fixed axis cannot resize");
        check(!HomeWidgetResize.allows(1,2,true,180,100,400,100,4),"gap counts toward minimum content size");
        check(HomeWidgetResize.allows(3,2,true,180,100,300,100,4),"allowed content size");
        check(!HomeWidgetResize.allows(4,2,true,180,100,300,100,4),"maximum enforced");
        check(HomeWidgetResize.allows(4,2,true,180,100,120,100,4),"invalid maximum below default ignored");
        check(HomeWidgetResize.allows(3,2,true,180,400,0,100,4),"minimum above default ignored");
        check(!HomeWidgetResize.allows(0,2,true,0,0,0,100,4)&&!HomeWidgetResize.allows(5,2,true,0,0,0,100,4),"grid boundary");
        for(int span=1;span<=4;span++)for(int current=1;current<=4;current++){
            boolean allowed=HomeWidgetResize.allows(span,current,true,150,90,350,100,4);
            if(span!=current&&allowed)check(span*100-4>=90&&span*100-4<=350,"all generated alternatives meet provider bounds");
        }
        System.out.println("PASS HomeWidgetResize: fixed axes, bounds, gaps, invalid provider limits and display changes");
    }
}
