package floatapp.floatset;

import android.app.Activity;
import android.os.Bundle;

/** Legacy manifest target. Settings now live in the existing GFrame settings page. */
public class FloatSetActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        finish();
    }
}
