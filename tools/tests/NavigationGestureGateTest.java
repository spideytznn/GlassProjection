package io.github.sixzleo.tabfold.projection;

public final class NavigationGestureGateTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        NavigationGestureGate gate=new NavigationGestureGate();
        gate.down(NavigationGestureGate.Origin.LEFT,0,500,0);gate.move(68,504);
        check(gate.up(70,504,160,54,430)==NavigationGestureGate.Action.BACK,"left edge should go back");
        gate.down(NavigationGestureGate.Origin.RIGHT,1000,500,0);
        check(gate.up(930,505,140,54,430)==NavigationGestureGate.Action.BACK,"right edge should go back");
        gate.down(NavigationGestureGate.Origin.LEFT,0,500,0);
        check(gate.up(15,590,200,54,430)==NavigationGestureGate.Action.NONE,"vertical edge scroll must cancel");
        gate.down(NavigationGestureGate.Origin.BOTTOM,500,1000,0);
        check(gate.up(504,925,220,62,430)==NavigationGestureGate.Action.HOME,"quick upward swipe should go home");
        gate.down(NavigationGestureGate.Origin.BOTTOM,500,1000,0);gate.move(501,925);
        check(gate.recentsReady(500,62,430),"held swipe should be ready for recents");
        check(gate.up(501,925,500,62,430)==NavigationGestureGate.Action.RECENTS,"held upward swipe should open recents");
        gate.down(NavigationGestureGate.Origin.BOTTOM,500,1000,0);
        check(gate.up(590,985,180,62,430)==NavigationGestureGate.Action.NONE,"horizontal bottom swipe must cancel");
        System.out.println("NavigationGestureGateTest passed");
        gate.down(NavigationGestureGate.Origin.BOTTOM,500,1000,0);gate.move(500,800);gate.reset();
        check(!gate.recentsReady(1000,62,430),"canceled or detached bottom strip cannot fire hold");
        check(gate.up(500,800,1000,62,430)==NavigationGestureGate.Action.NONE,"late release after cancellation cannot navigate");
    }
}
