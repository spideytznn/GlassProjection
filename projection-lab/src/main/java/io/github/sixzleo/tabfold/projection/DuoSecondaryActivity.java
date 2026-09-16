package io.github.sixzleo.tabfold.projection;

/** A real secondary HOME: Android owns one instance in each display's home task. */
public final class DuoSecondaryActivity extends DuoHomeActivity {
    private String displayName(){return getDisplay()==null?"external":getDisplay().getName();}
    @Override boolean dualPanel(){return true;}
    @Override String panelStore(){return DuoHomeActivity.storeForDisplay(displayName());}
    @Override int widgetHostId(){return DuoHomeActivity.hostIdForDisplay(displayName());}
}
