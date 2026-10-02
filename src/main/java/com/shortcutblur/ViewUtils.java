package com.shortcutblur;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

public final class ViewUtils {

    public static final int MAX_DEPTH = 50;

    private ViewUtils() {}

    public static View ancestorOfType(View v, String namePart) {
        View cur = v;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            try {
                if (cur.getClass().getName().contains(namePart)) return cur;
            } catch (Throwable ignored) { }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    public static View descendantJustBelow(View v, String namePart) {
        View cur = v;
        View prev = v;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            try {
                if (cur.getClass().getName().contains(namePart)) return prev;
            } catch (Throwable ignored) { }
            prev = cur;
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return prev;
    }

    public static ViewGroup ancestorGroupOfType(View v, String namePart) {
        View cur = v;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            try {
                if (cur instanceof ViewGroup && cur.getClass().getName().contains(namePart)) {
                    return (ViewGroup) cur;
                }
            } catch (Throwable ignored) { }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    public static Context contextOfType(Context ctx, String className) {
        Context c = ctx;
        int depth = 0;
        while (c != null && depth++ < MAX_DEPTH) {
            try {
                if (c.getClass().getName().equals(className)) return c;
            } catch (Throwable ignored) { }
            if (c instanceof ContextWrapper) {
                c = ((ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    public static View findByViewId(View root, int id) {
        return findByViewId(root, id, 0);
    }

    public static boolean isDescendantOrSelf(View ancestor, View v) {
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < MAX_DEPTH) {
            if (cur == ancestor) return true;
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    private static View findByViewId(View root, int id, int depth) {
        if (root == null || depth > MAX_DEPTH) return null;
        if (root.getId() == id) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                View r = findByViewId(g.getChildAt(i), id, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static String dumpViewChainNames(View view) {
        StringBuilder sb = new StringBuilder();
        try {
            ViewParent p = view == null ? null : view.getParent();
            int g = 0;
            while (p != null && g < 20) {
                sb.append(p.getClass().getSimpleName()).append(" > ");
                p = (p instanceof View) ? ((View) p).getParent() : null;
                g++;
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    public static String dumpChildViewNames(ViewGroup vg) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < vg.getChildCount(); i++) {
                sb.append(vg.getChildAt(i).getClass().getSimpleName()).append(" ");
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    public static boolean isReallyVisible(View v) {
        if (v == null) return false;
        try {
            if (!v.isAttachedToWindow()) return false;
            if (!v.isShown()) return false;
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;

            Rect r = new Rect();
            if (!v.getGlobalVisibleRect(r)) return false;
            if (r.width() < 4 || r.height() < 4) return false;
        } catch (Throwable t) {
            return true;
        }
        return true;
    }
}
