package floatapp.input;

import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import com.autonavi.eaglet.uisurface.AutoEventProcessor;
import floatapp.floatset.IdleRender;

public final class TouchQueue {
    private final AutoEventProcessor processor;
    private final Handler handler;
    private Event pendingMove;

    public TouchQueue(AutoEventProcessor processor, Handler handler) {
        this.processor = processor;
        this.handler = handler;
    }

    public synchronized void offer(MotionEvent event) {
        long when = SystemClock.elapsedRealtimeNanos();
        IdleRender.wake();
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && pendingMove != null) {
            pendingMove.event.recycle();
            pendingMove.event = MotionEvent.obtainNoHistory(event);
            pendingMove.when = when;
            return;
        }
        Event next = new Event(MotionEvent.obtainNoHistory(event), when);
        // A pointer change or release closes the batch; its preceding move stays before it.
        pendingMove = event.getActionMasked() == MotionEvent.ACTION_MOVE ? next : null;
        handler.post(next);
    }

    private final class Event implements Runnable {
        private MotionEvent event;
        private long when;

        Event(MotionEvent event, long when) {
            this.event = event;
            this.when = when;
        }

        @Override public void run() {
            MotionEvent touch;
            long timestamp;
            synchronized (TouchQueue.this) {
                if (pendingMove == this) pendingMove = null;
                touch = event;
                timestamp = when;
            }
            processor.dispatchTouchNow(touch, timestamp);
            touch.recycle();
        }
    }
}
