package io.github.sixzleo.tabfold.projection;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.List;

/** Launcher-style folder preview: up to four app icons on a rounded glass tile. */
final class HomeFolderIcon extends View {
    private final List<Bitmap> icons;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
    private final RectF target=new RectF();
    private final Path clip=new Path();
    HomeFolderIcon(Activity activity,List<Bitmap> icons){
        super(activity);this.icons=icons;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas){
        int size=Math.min(getWidth(),getHeight());
        int icon=FolderFan.iconSize(size),count=FolderFan.shown(icons.size());
        float corner=icon*0.23f;
        for(int i=0;i<count;i++){
            Bitmap bitmap=icons.get(i);if(bitmap==null||bitmap.isRecycled())continue;
            int[] at=FolderFan.origin(i,size);
            target.set(at[0],at[1],at[0]+icon,at[1]+icon);
            clip.rewind();clip.addRoundRect(target.left,target.top,target.right,target.bottom,corner,corner,Path.Direction.CW);
            int save=canvas.save();canvas.clipPath(clip);
            canvas.drawBitmap(bitmap,null,target,paint);
            canvas.restoreToCount(save);
        }
    }
}
