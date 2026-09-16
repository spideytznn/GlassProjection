package io.github.sixzleo.tabfold.probe;

import android.view.SurfaceControl;

/** Prepainted old/new-layout snapshots; cannot override a physical OFF or blank layer stack. */
final class DualSnapshotMasks implements AutoCloseable {
    final DualLiveRenderer renderer;
    final DualLiveRenderer.Output[] outputs=new DualLiveRenderer.Output[4];
    boolean captured,remapped,finished;
    long readySince;
    int readyFrames;

    DualSnapshotMasks(DualLiveRenderer renderer,DualLiveRenderer.Output inner,
                      int coverWidth,int coverHeight,int coverRotation)throws Exception {
        this.renderer=renderer;
        try{
            outputs[0]=new DualLiveRenderer.Output(renderer,coverWidth,coverHeight,coverRotation,0,false);
            outputs[1]=new DualLiveRenderer.Output(renderer,inner.width,inner.height,inner.rotation,1,true);
            // Precompute the measured upright inner-primary geometry before OFF.
            outputs[2]=new DualLiveRenderer.Output(renderer,inner.viewWidth,inner.viewHeight,inner.viewRotation,0,true);
            outputs[3]=new DualLiveRenderer.Output(renderer,coverWidth,coverHeight,coverRotation,1,false);
            try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
                for(DualLiveRenderer.Output out:outputs)t.setLayer(out.layer,2000001);
                t.apply();
            }
        }catch(Exception failure){close();throw failure;}
    }
    void capture(){
        renderer.freezeFrame();
        for(DualLiveRenderer.Output out:outputs)renderer.drawSnapshot(out);
        captured=true;
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            t.setVisibility(outputs[0].layer,true).setVisibility(outputs[1].layer,true).apply();
        }
    }
    void remap(){
        if(!captured||remapped||finished)return;
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            t.setVisibility(outputs[0].layer,false).setVisibility(outputs[1].layer,false)
                .setVisibility(outputs[2].layer,true).setVisibility(outputs[3].layer,true).apply();
        }
        remapped=true;
    }
    boolean settle(boolean ready,long now,int frames){
        if(!captured||!remapped||finished)return false;
        if(!ready){readySince=0;return false;}
        if(readySince==0){readySince=now;readyFrames=frames;}
        if(now-readySince<200||frames-readyFrames<3)return false;
        float alpha=1f-Math.min(1f,(now-readySince-200)/160f);
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            t.setAlpha(outputs[2].layer,alpha).setAlpha(outputs[3].layer,alpha);
            if(alpha==0f)t.setVisibility(outputs[2].layer,false).setVisibility(outputs[3].layer,false);
            t.apply();
        }
        if(alpha==0f){finished=true;return true;}
        return false;
    }
    public void close(){
        for(int i=0;i<outputs.length;i++)if(outputs[i]!=null){try{outputs[i].close();}catch(Exception ignored){}outputs[i]=null;}
    }
}
