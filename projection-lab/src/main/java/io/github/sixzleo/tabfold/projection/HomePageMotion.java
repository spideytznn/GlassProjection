package io.github.sixzleo.tabfold.projection;

import androidx.viewpager.widget.ViewPager;

final class HomePageMotion {
    private HomePageMotion(){}
    static void select(ViewPager pager,int requested,boolean animate){
        if(pager.getAdapter()==null||pager.getAdapter().getCount()==0)return;
        int target=Math.max(0,Math.min(requested,pager.getAdapter().getCount()-1));
        pager.setCurrentItem(target,animate);
    }
}
