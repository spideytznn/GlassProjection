package io.github.sixzleo.tabfold.projection;

/** Duo keeps both content pipelines alive. Only physical endpoints mask an inactive panel. */
public final class FixedDualPolicy {
    public enum Panel { INNER, COVER, BOTH, NONE }
    private final int state;
    public FixedDualPolicy(boolean primaryInner){state=primaryInner?5:6;}
    public int requestedState(){return state;}
    // Endpoint signals must come from physical pose, never the stock desktop's angle thresholds.
    // This result controls curtains only; it must not suspend either content/animation pipeline.
    public Panel update(boolean fullyOpened,boolean contactClosed,boolean poseAvailable,boolean interactive){
        if(!interactive||!poseAvailable||fullyOpened&&contactClosed)return Panel.NONE;
        if(contactClosed)return Panel.COVER;
        if(fullyOpened)return Panel.INNER;
        return Panel.BOTH;
    }
}
