package floatapp.floatset;
import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public final class SettingsEntry {
    static { System.loadLibrary("amapextras"); }
    private static native String nativePageType();
    private static native boolean nativeOpenEntry(int action, CruiseButton target);
    private static FrameLayout host;
    private static CruiseButton cruiseButton;
    private static String adjustedPage = "";
    private static boolean backgroundBack, settingsBack;
    private static final Runnable refresh = () -> {
        if (cruiseButton != null) nativeOpenEntry(6, cruiseButton);
    };
    private static final Runnable settledRefresh = () -> refresh.run();
    private static final View.OnLayoutChangeListener resized = (view, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            IdleRender.wake();
            adjustedPage = "";
            schedule();
        }
    };
    public static void attach(Activity a, ViewGroup unused) {
        detach();
        HighRefresh.apply(a);
        IdleRender.start(a);
        host = a.findViewById(android.R.id.content);
        host.addOnLayoutChangeListener(resized);
        NativeSettings.bind(a);
        boolean dark = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        cruiseButton = new CruiseButton(a);
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? 0xff202630 : Color.WHITE);
        float density = a.getResources().getDisplayMetrics().density;
        background.setCornerRadius(16 * density);
        cruiseButton.setBackground(background);
        cruiseButton.setElevation(8 * density);
        cruiseButton.setClipToOutline(true);
        for (int side = 0; side < 2; side++) {
            final boolean search = side == 0;
            SearchButton button = new SearchButton(a, search, dark);
            button.setContentDescription(search ? "搜索目的地" : "展开导航面板");
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    dark ? 0x307e9fc7 : 0x182878ff), null, null));
            cruiseButton.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
            button.setOnClickListener(v -> {
                if (cruiseButton.opening) return;
                IdleRender.wake();
                cruiseButton.opening = true;
                if (nativeOpenEntry(search ? 1 : 0, cruiseButton)) {
                    cruiseButton.setVisibility(View.GONE);
                    cruiseButton.removeCallbacks(refresh);
                } else {
                    cruiseButton.opening = false;
                }
            });
            if (search) {
                View divider = new View(a);
                divider.setBackgroundColor(dark ? 0xff36404d : 0xffedf0f4);
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(
                        Math.max(1, Math.round(density)), Math.round(22 * density));
                line.gravity = Gravity.CENTER_VERTICAL;
                cruiseButton.addView(divider, line);
            }
        }
        cruiseButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                Math.round(104 * density), Math.round(48 * density), Gravity.TOP | Gravity.START);
        buttonParams.setMarginStart(Math.round(16 * density));
        buttonParams.topMargin = Math.round(16 * density);
        host.addView(cruiseButton, buttonParams);
        schedule();
    }
    // Called by Amap's own day/night notification, independently of Android uiMode.
    public static void themeChanged() {
        if (cruiseButton != null) {
            cruiseButton.removeCallbacks(refresh);
            cruiseButton.postOnAnimation(refresh);
        }
    }
    public static void touch(MotionEvent event) { if (event.getActionMasked() == MotionEvent.ACTION_UP) schedule(); }
    public static void key(KeyEvent event) { if (event.getAction() == KeyEvent.ACTION_UP) schedule(); }
    public static boolean back(Activity a, KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                settingsBack = NativeSettings.handlesBack();
                backgroundBack = !settingsBack && backgroundIfRoot(a, false);
            }
            return settingsBack || backgroundBack;
        }
        if (settingsBack) {
            settingsBack = false;
            if (!event.isCanceled()) NativeSettings.back();
            return true;
        }
        boolean handled = backgroundBack;
        backgroundBack = false;
        if (handled && !event.isCanceled()) a.moveTaskToBack(true);
        return handled;
    }
    public static boolean backgroundIfRoot(Activity a, boolean background) {
        String page = nativePageType();
        boolean root = page.contains("15CGIndexFragment") || page.contains("GFragmentCruiseE")
                || page.contains("CGNaviFragment");
        if (root && background) a.moveTaskToBack(true);
        return root;
    }
    public static void state(int state) {
        // Repeated day/night status is handled by the actual theme snapshot.
        if (state != 37 && state != 38) IdleRender.wake();
        if (cruiseButton != null && ((state >= 8 && state <= 12)
                || state == 24 || state == 25 || state == 46 || state == 47)) {
            cruiseButton.removeCallbacks(refresh);
            cruiseButton.postOnAnimation(refresh);
            cruiseButton.removeCallbacks(settledRefresh);
            cruiseButton.postDelayed(settledRefresh, 160);
        }
    }
    public static void schedule() {
        if (cruiseButton != null) {
            cruiseButton.removeCallbacks(refresh);
            cruiseButton.postOnAnimation(refresh);
            cruiseButton.removeCallbacks(settledRefresh);
            cruiseButton.postDelayed(settledRefresh, 160);
        }
    }
    public static void detach() {
        IdleRender.stop();
        NativeSettings.detach();
        if (host != null) host.removeOnLayoutChangeListener(resized);
        host = null;
        if (cruiseButton != null) {
            cruiseButton.removeCallbacks(refresh);
            cruiseButton.removeCallbacks(settledRefresh);
            ((ViewGroup) cruiseButton.getParent()).removeView(cruiseButton);
        }
        cruiseButton = null;
        adjustedPage = "";
        backgroundBack = settingsBack = false;
    }

    private static final class CruiseButton extends LinearLayout {
        private Boolean night;
        private boolean opening;

        CruiseButton(Context context) { super(context); }

        // The snapshot is queued on GFrame's thread, after its pending page work.
        public void pageChanged(String page, boolean dark, int speedRight, int speedTop) {
            post(() -> {
                if (getParent() == null) return;
                String name = page.toLowerCase(java.util.Locale.ROOT);
                boolean cruise = name.contains("gfragmentcruise");
                appearance(dark, speedRight, speedTop);
                setVisibility(cruise && !opening ? View.VISIBLE : View.GONE);
                if (!page.equals(adjustedPage)) {
                    adjustedPage = page;
                    IdleRender.wake();
                    if (name.contains("cgnavifragment")) {
                        nativeOpenEntry(2, null);
                        IdleRender.lowerVoicePriority();
                    }
                }
                boolean show = name.contains("settingmainfragment") || name.equals("settingmain") || name.equals("设置");
                NativeSettings.ensure(show);
            });
        }

        public void entryFinished(boolean changed) {
            post(() -> {
                if (getParent() == null) return;
                opening = false;
                cruiseButton.removeCallbacks(refresh);
                cruiseButton.postOnAnimation(refresh);
            });
        }

        @Override protected void onConfigurationChanged(Configuration configuration) {
            super.onConfigurationChanged(configuration);
            adjustedPage = "";
            schedule();
        }

        // Part of the same GFrame snapshot, applied before showing the button.
        private void appearance(boolean dark, int speedRight, int speedTop) {
            if (night == null || night != dark) {
                night = dark;
                IdleRender.wake();
                ((GradientDrawable) getBackground()).setColor(dark ? 0xff202630 : Color.WHITE);
                getChildAt(1).setBackgroundColor(dark ? 0xff36404d : 0xffedf0f4);
                for (int i = 0; i < 3; i += 2) {
                    SearchButton button = (SearchButton) getChildAt(i);
                    button.paint.setColor(dark ? 0xffdce6f3 : 0xff283442);
                    ((RippleDrawable) button.getBackground()).setColor(
                            ColorStateList.valueOf(dark ? 0x307e9fc7 : 0x182878ff));
                    button.invalidate();
                }
            }
            float density = getResources().getDisplayMetrics().density;
            int inset = Math.round(16 * density);
            int left = speedRight < 0 ? inset : Math.max(inset, speedRight + Math.round(12 * density));
            int top = speedTop < 0 ? inset : speedTop;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (lp.getMarginStart() != left || lp.topMargin != top) {
                lp.setMarginStart(left);
                lp.topMargin = top;
                setLayoutParams(lp);
            }
        }
    }

    private static final class SearchButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final boolean search;

        SearchButton(Context context, boolean search, boolean dark) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            this.search = search;
            paint.setColor(dark ? 0xffdce6f3 : 0xff283442);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) IdleRender.wake();
            if (action == MotionEvent.ACTION_UP && isEnabled() && isPressed()) {
                // Commit in this input dispatch instead of View's posted PerformClick.
                // CANCEL clears View's touch bookkeeping without scheduling a second click.
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel);
                cancel.recycle();
                performClick();
                return true;
            }
            boolean handled = super.onTouchEvent(event);
            if (action == MotionEvent.ACTION_DOWN) invalidate();
            return handled;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (isPressed()) canvas.drawColor((paint.getColor() & 0x00ffffff) | 0x18000000);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            if (search) {
                canvas.drawCircle(x - 2 * density, y - 2 * density, 7 * density, paint);
                canvas.drawLine(x + 3 * density, y + 3 * density,
                        x + 9 * density, y + 9 * density, paint);
            } else {
                canvas.drawLine(x - 7 * density, y - 3 * density, x, y + 4 * density, paint);
                canvas.drawLine(x, y + 4 * density, x + 7 * density, y - 3 * density, paint);
            }
        }
    }
}
