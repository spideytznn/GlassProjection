package io.github.sixzleo.tabfold.projection;

/** A real secondary HOME: Android owns one instance in each display's home task. */
public final class DuoSecondaryActivity extends DuoHomeActivity {
    private String displayName(){return getDisplay()==null?"external":getDisplay().getName();}
    @Override boolean dualPanel(){return true;}
    @Override String panelStore(){
        String name=displayName();
        if("Duo inner content".equals(name))return "duo_inner";
        if("Duo cover content".equals(name))return "duo_cover";
        return "duo_external_"+Integer.toHexString(name.hashCode());
    }
    @Override int widgetHostId(){
        String name=displayName();
        if("Duo inner content".equals(name))return 2702;
        if("Duo cover content".equals(name))return 2703;
        return 10000+(name.hashCode()&0x3fffffff);
    }
}
