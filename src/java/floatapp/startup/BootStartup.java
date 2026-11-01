package floatapp.startup;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

public final class BootStartup {
    public static boolean enabled(Context context) {
        return context.getSharedPreferences("startup", 0).getBoolean("enabled", true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("startup", 0).edit().putBoolean("enabled", enabled).apply();
    }

    public static boolean receive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return false;
        if (enabled(context)) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                context.startActivity(launch);
            } catch (SecurityException error) {
                // The device's background-launch permission is controlled by its owner.
            }
        }
        return true;
    }

    public static void openOverlaySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.getPackageName()));
        context.startActivity(intent);
    }

    public static void openStartupSettings(Context context) {
        Intent intent = new Intent().setClassName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity");
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
        }
        context.startActivity(intent);
    }
}
