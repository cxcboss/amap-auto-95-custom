package floatapp.floatset;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.ArrayList;

public final class HighRefresh {
    public static void apply(Activity activity) {
        if (Build.VERSION.SDK_INT < 23) return;
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == best.getPhysicalWidth()
                    && mode.getPhysicalHeight() == best.getPhysicalHeight()
                    && mode.getRefreshRate() > best.getRefreshRate()) best = mode;
        }
        final float rate = best.getRefreshRate();
        if (best.getModeId() != current.getModeId()) {
            WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            params.preferredDisplayModeId = best.getModeId();
            params.preferredRefreshRate = rate;
            activity.getWindow().setAttributes(params);
        }
        if (Build.VERSION.SDK_INT < 30) return;
        View root = activity.findViewById(android.R.id.content);
        root.post(() -> {
            ArrayList<View> views = new ArrayList<>(); views.add(root);
            for (int i = 0; i < views.size(); i++) {
                View view = views.get(i);
                if (view instanceof SurfaceView) {
                    SurfaceHolder holder = ((SurfaceView) view).getHolder();
                    if (holder.getSurface().isValid()) {
                        holder.getSurface().setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                    } else holder.addCallback(new SurfaceHolder.Callback() {
                        public void surfaceCreated(SurfaceHolder h) {
                            h.getSurface().setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                            h.removeCallback(this);
                        }
                        public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {}
                        public void surfaceDestroyed(SurfaceHolder h) {}
                    });
                } else if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int j = 0; j < group.getChildCount(); j++) views.add(group.getChildAt(j));
                }
            }
        });
    }
}
