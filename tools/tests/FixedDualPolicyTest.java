import io.github.sixzleo.tabfold.projection.FixedDualPolicy;
public class FixedDualPolicyTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        for(boolean primary:new boolean[]{false,true}){
            FixedDualPolicy p=new FixedDualPolicy(primary);int fixed=primary?5:6;
            for(int cycle=0;cycle<100;cycle++){
                check(p.update(false,true,true,true)==FixedDualPolicy.Panel.COVER);
                for(int sample=0;sample<180;sample++){
                    check(p.update(false,false,true,true)==FixedDualPolicy.Panel.BOTH);
                    check(p.requestedState()==fixed);
                }
                check(p.update(true,false,true,true)==FixedDualPolicy.Panel.INNER);
                check(p.update(false,false,true,true)==FixedDualPolicy.Panel.BOTH);
                check(p.update(false,true,true,true)==FixedDualPolicy.Panel.COVER);
                check(p.update(false,false,false,true)==FixedDualPolicy.Panel.NONE);
                check(p.update(true,false,true,false)==FixedDualPolicy.Panel.NONE);
                check(p.update(true,true,true,true)==FixedDualPolicy.Panel.NONE);
                check(p.requestedState()==fixed);
            }
        }
        System.out.println("PASS fixed primary; both pipelines visible between endpoints; sleep and invalid pose gates");
    }
}
