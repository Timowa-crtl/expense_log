package de.timowa.expenselog;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays its children out left to right and starts a new row when the next one does not fit, like
 * words in a paragraph. Used for the export dialog's filter chips.
 *
 * <p>Hand-rolled because the obvious components are unavailable here: Material's ChipGroup and
 * Chip require a Theme.MaterialComponents app theme (this app is AppCompat), and ConstraintLayout's
 * Flow helper would add a dependency for one row of chips.
 *
 * <p>Children are measured against the full available width, so a single child wider than a row
 * gets that width and can ellipsize. Child margins are ignored; spacing comes from
 * {@link #setSpacing}.
 */
public class WrapRowLayout extends ViewGroup {

    private int horizontalSpacing;
    private int verticalSpacing;

    public WrapRowLayout(Context context) {
        super(context);
    }

    public WrapRowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setSpacing(int horizontalPx, int verticalPx) {
        horizontalSpacing = horizontalPx;
        verticalSpacing = verticalPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int childWidthSpec = MeasureSpec.makeMeasureSpec(Math.max(available, 0),
                MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                        ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int x = 0, rowHeight = 0, height = 0, widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE)
                continue;
            child.measure(childWidthSpec, childHeightSpec);
            int w = child.getMeasuredWidth();
            if (x > 0 && x + horizontalSpacing + w > available) {
                height += rowHeight + verticalSpacing;
                x = 0;
                rowHeight = 0;
            }
            if (x > 0)
                x += horizontalSpacing;
            x += w;
            widest = Math.max(widest, x);
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        height += rowHeight;

        setMeasuredDimension(
                resolveSize(widest + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int available = r - l - getPaddingLeft() - getPaddingRight();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int x = 0, y = getPaddingTop(), rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE)
                continue;
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + horizontalSpacing + w > available) {
                y += rowHeight + verticalSpacing;
                x = 0;
                rowHeight = 0;
            }
            if (x > 0)
                x += horizontalSpacing;
            int left = rtl ? r - l - getPaddingRight() - x - w : getPaddingLeft() + x;
            child.layout(left, y, left + w, y + h);
            x += w;
            rowHeight = Math.max(rowHeight, h);
        }
    }
}
