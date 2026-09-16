package io.github.sixzleo.tabfold.projection;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Shared search skeleton: query field, live count, filtered list and empty state. */
final class HomeSearchList<T> extends LinearLayout {
    final EditText query;
    final Button clear;
    final TextView count,empty;
    final ListView list;
    final List<T> visible=new ArrayList<>();
    Runnable filter;
    HomeSearchList(Activity activity,String queryTag,String listTag,String emptyTag,String hint){
        super(activity);
        setOrientation(LinearLayout.VERTICAL);
        int dp=HomeStyle.dp(activity,1);
        LinearLayout field=new LinearLayout(activity);field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(HomeStyle.surface(field,HomeStyle.FIELD,HomeStyle.FIELD_RADIUS));
        query=new EditText(activity);query.setTag(queryTag);query.setSingleLine(true);query.setHint(hint);
        query.setTextColor(HomeStyle.TEXT);query.setHintTextColor(HomeStyle.MUTED);query.setTextSize(16);
        query.setPadding(dp*14,0,dp*4,0);query.setBackgroundColor(0);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.addView(query,new LinearLayout.LayoutParams(0,dp*52,1));
        clear=new Button(activity);clear.setText("×");clear.setTextColor(HomeStyle.TEXT);clear.setMinWidth(0);clear.setMinimumWidth(0);
        clear.setPadding(0,0,0,0);clear.setBackgroundColor(0);clear.setContentDescription("清空搜索");
        clear.setOnClickListener(v->query.setText(""));clear.setEnabled(false);
        field.addView(clear,new LinearLayout.LayoutParams(dp*48,dp*48));
        addView(field,new LinearLayout.LayoutParams(-1,dp*52));
        count=HomeStyle.text(activity,"",12,HomeStyle.MUTED);count.setPadding(dp*4,dp*10,0,dp*6);
        addView(count,new LinearLayout.LayoutParams(-1,dp*36));
        FrameLayout results=new FrameLayout(activity);addView(results,new LinearLayout.LayoutParams(-1,0,1));
        list=new ListView(activity);list.setTag(listTag);list.setDivider(null);list.setVerticalScrollBarEnabled(false);
        results.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=HomeStyle.text(activity,"",15,HomeStyle.MUTED);empty.setTag(emptyTag);empty.setGravity(Gravity.CENTER);
        results.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int removed,int added){}
            public void onTextChanged(CharSequence s,int start,int before,int added){refresh();}
            public void afterTextChanged(Editable s){}
        });
    }
    void refresh(){if(filter!=null)filter.run();}
}
