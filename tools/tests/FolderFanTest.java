package io.github.sixzleo.tabfold.projection;

public final class FolderFanTest {
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    private static boolean overlap(int[] a,int[] b,int size){
        int icon=FolderFan.iconSize(size);
        return a[0]<b[0]+icon&&b[0]<a[0]+icon&&a[1]<b[1]+icon&&b[1]<a[1]+icon;
    }
    public static void main(String[] args){
        check(FolderFan.shown(0)==0&&FolderFan.shown(3)==3&&FolderFan.shown(4)==4&&FolderFan.shown(9)==4,"preview caps at four icons");
        for(int container=24;container<=96;container+=4){
            int icon=FolderFan.iconSize(container);
            check(icon>0&&icon<container,"icon smaller than container at "+container);
            int[][] at=new int[4][];
            for(int i=0;i<4;i++){
                at[i]=FolderFan.origin(i,container);
                check(at[i][0]>=0&&at[i][1]>=0&&at[i][0]+icon<=container&&at[i][1]+icon<=container,"slot "+i+" inside container at "+container);
                for(int j=0;j<i;j++)check(!overlap(at[i],at[j],container),"slots "+i+","+j+" never overlap at "+container);
            }
            check(at[1][0]>at[0][0]&&at[2][0]==at[0][0],"second icon sits right of the first");
            check(at[2][1]>at[0][1]&&at[3][1]==at[2][1],"third icon sits below the first");
            check(at[3][0]>at[2][0]&&at[3][1]>at[1][1],"fourth icon fills the bottom-right slot");
        }
        System.out.println("PASS: folder preview geometry stays inside the tile, capped at four, never overlapping");
    }
}
