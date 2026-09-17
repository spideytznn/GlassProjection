package io.github.sixzleo.tabfold.projection;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * Wraps a hosted widget: providers that refuse to shrink below their preferred size are
 * scaled down to fit (adapted from MiDuo's host-view wrapper), and touches that land on
 * interactive widget content claim the gesture so pager/scroll parents cannot steal them.
 */
final class HomeWidgetScale extends FrameLayout {
    private final AppWidgetHostView hosted;
    private final int minWidth, minHeight;
    private float scale = 1f;

    HomeWidgetScale(Context context, AppWidgetHostView hosted, AppWidgetProviderInfo info) {
        super(context);
        this.hosted = hosted;
        float density = context.getResources().getDisplayMetrics().density;
        minWidth = Math.round(info.minWidth / density);
        minHeight = Math.round(info.minHeight / density);
        hosted.setPadding(0, 0, 0, 0);
        addView(hosted, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Unconstrained first pass: some providers only report their real size without limits.
        hosted.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int wantedWidth = Math.max(hosted.getMeasuredWidth(), minWidth);
        int wantedHeight = Math.max(hosted.getMeasuredHeight(), minHeight);
        hosted.measure(MeasureSpec.makeMeasureSpec(wantedWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(wantedHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(resolveSize(wantedWidth, widthSpec), resolveSize(wantedHeight, heightSpec));
        scale = Math.min(1f, Math.min(getMeasuredWidth() / (float) wantedWidth, getMeasuredHeight() / (float) wantedHeight));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = getMeasuredWidth(), height = getMeasuredHeight();
        int drawn = Math.round(hosted.getMeasuredWidth() * scale), drawnHeight = Math.round(hosted.getMeasuredHeight() * scale);
        hosted.layout(0, 0, hosted.getMeasuredWidth(), hosted.getMeasuredHeight());
        hosted.setPivotX(0f);
        hosted.setPivotY(0f);
        hosted.setScaleX(scale);
        hosted.setScaleY(scale);
        hosted.setTranslationX((width - drawn) / 2f);
        hosted.setTranslationY((height - drawnHeight) / 2f);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float localX = (event.getX() - hosted.getLeft() - hosted.getTranslationX()) / Math.max(scale, .01f);
            float localY = (event.getY() - hosted.getTop() - hosted.getTranslationY()) / Math.max(scale, .01f);
            if (interactiveAt(deepestAt(hosted, localX, localY)))
                requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            requestDisallowInterceptTouchEvent(false);
        return super.dispatchTouchEvent(event);
    }

    private static View deepestAt(View root, float x, float y) {
        View deepest = root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (x >= child.getLeft() && x < child.getRight() && y >= child.getTop() && y < child.getBottom())
                    return deepestAt(child, x - child.getLeft(), y - child.getTop());
            }
        }
        return deepest;
    }

    /** Any clickable, scroll-capable or scroll-container view between the touched leaf and the host root claims the gesture. */
    private boolean interactiveAt(View touched) {
        for (View view = touched; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.isClickable() || view.isLongClickable() || view.isFocusableInTouchMode()) return true;
            if (view instanceof ScrollView || view instanceof HorizontalScrollView
                || view instanceof android.widget.AbsListView || view instanceof android.webkit.WebView) return true;
            if (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)
                || view.canScrollVertically(-1) || view.canScrollVertically(1)) return true;
            if (view == hosted) break;
        }
        return false;
    }
}
