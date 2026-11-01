package floatapp.floatset;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import floatapp.audio.NavVolume;
import floatapp.startup.BootStartup;
import floatapp.tts.ttsSettings;
import floatapp.tts.ttsService;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;

/** Preferences only. All controls, editing and scrolling are rendered by Amap's GFrame. */
public final class NativeSettings {
    static { System.loadLibrary("amapextras"); }
    private static native void nativeBind();
    private static native void nativeEnsure();
    private static native void nativeRender(boolean visible, int selected, int mode, boolean reset,
            String title, String draft, int inputType, String[] titles, String[] values, int[] kinds, int[] actions, int[] help);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Activity activity;
    private static boolean visible, settingsPage;
    private static int selected, mode;
    private static Setting editing;
    private static String draft = "", error = "";
    private static final ArrayList<Setting> settings = new ArrayList<>();
    private static final ArrayList<String> titles = new ArrayList<>(), values = new ArrayList<>();
    private static final ArrayList<Integer> kinds = new ArrayList<>(), actions = new ArrayList<>(), help = new ArrayList<>();

    private static final class Setting {
        final String key, title, initial, unit, description;
        final int kind;
        final double min, max;
        Setting(String key, String title, String initial, String unit, String description,
                int kind, double min, double max) {
            this.key = key; this.title = title; this.initial = initial; this.unit = unit;
            this.description = description; this.kind = kind; this.min = min; this.max = max;
        }
    }

    static void bind(Activity host) { activity = host; nativeBind(); }
    static void detach() { activity = null; settingsPage = false; }
    static void ensure(boolean page) {
        if (!page) {
            visible = false; mode = 0; settingsPage = false;
            return;
        }
        boolean entering = !settingsPage;
        settingsPage = true;
        if (entering && visible) render(false);
        else nativeEnsure();
    }
    static boolean handlesBack() { return settingsPage && visible; }
    static void back() {
        if (mode != 0) { mode = 0; error = ""; }
        else visible = false;
        render(false);
    }

    // Called on GFrame's thread; defer model changes until its input dispatch has returned.
    public static void onAction(int action, String input) {
        MAIN.post(() -> {
            if (activity == null) return;
            IdleRender.wake();
            if (mode == 1 && input != null) draft = input;
            if (action == 7) { render(false); return; }
            if (action == 8) { visible = true; mode = 0; render(true); return; }
            if (action == 1) { back(); return; }
            if (action == 16) { visible = false; mode = 0; render(false); return; }
            if (action >= 10 && action < 15) {
                selected = action - 10; mode = 0; render(true); return;
            }
            if (action == 4) { ttsService.c().e("前方路口请右转，请注意行车安全。"); return; }
            if (action == 5) { BootStartup.openStartupSettings(activity); return; }
            if (action == 6) { BootStartup.openOverlaySettings(activity); return; }
            if (action == 2) {
                draft = input == null ? draft : input.trim();
                String checked = validate(editing, draft);
                if (checked == null) { render(false); return; }
                save(editing, checked); mode = 0; render(false); return;
            }
            if (action == 3) { save(editing, editing.initial); mode = 0; render(false); return; }
            if (action == 9 || action == 15) {
                String number = validate(editing, draft);
                if (number == null) { render(false); return; }
                int n = Integer.parseInt(number) + (action == 9 ? -1 : 1);
                draft = String.valueOf(Math.max((int) editing.min, Math.min((int) editing.max, n)));
                render(false); return;
            }
            int index = action >= 1000 ? action - 1000 : action - 100;
            editing = settings.get(index);
            error = "";
            if (action >= 1000) mode = 2;
            else if (editing.kind == 0) save(editing, value(editing).equals("0") ? "1" : "0");
            else { mode = 1; draft = value(editing); }
            render(mode != 0);
        });
    }

    private static String validate(Setting setting, String text) {
        boolean numeric = setting.kind == 1 || setting.kind == 2 || setting.min >= 0;
        if (numeric && !(setting.kind == 3 && text.isEmpty())) {
            try {
                double number = Double.parseDouble(text);
                if ((Double.isNaN(number) || Double.isInfinite(number)) || number < setting.min || number > setting.max
                        || (setting.kind != 2 && number != Math.rint(number))) {
                    error = "请输入 " + setting.min + "—" + setting.max + " 范围内的" + (setting.kind == 2 ? "数值" : "整数");
                    return null;
                }
                return setting.kind == 2 ? String.valueOf((float) number) : String.valueOf((long) number);
            } catch (NumberFormatException invalid) { error = "请输入有效数值"; return null; }
        }
        return text;
    }

    private static void render(boolean reset) {
        titles.clear(); values.clear(); kinds.clear(); actions.clear(); help.clear(); settings.clear();
        if (mode == 0) showSection();
        else if (mode == 2) {
            add(editing.description, "", 4, 0, 0);
            add("默认值", display(editing, editing.initial), 0, 0, 0);
            if (editing.kind == 1 || editing.kind == 2 || editing.min >= 0) {
                String lower = editing.kind == 2 ? String.valueOf(editing.min) : String.valueOf((long) editing.min);
                String upper = editing.kind == 2 ? String.valueOf(editing.max) : String.valueOf((long) editing.max);
                add("可设范围", lower + "—" + upper + " " + editing.unit, 0, 0, 0);
            }
            action("恢复默认", 3);
        } else {
            add(editing.title, editing.unit, 1, 0, 0);
            add(error, "", 5, 0, 0);
            if (editing.key.equals("navPercent")) {
                action("−", 9); action("+", 15); action("试听导航语音", 4);
            }
            action("保存", 2); action("取消", 1);
        }
        int inputType = mode != 1 ? 0 : editing.kind == 2 ? 2 : editing.kind == 1 || editing.min >= 0 ? 1 : 0;
        nativeRender(visible, selected, mode, reset, mode == 0 ? "扩展设置" : editing.title, draft, inputType,
            titles.toArray(new String[0]), values.toArray(new String[0]), ints(kinds), ints(actions), ints(help));
    }
    private static int[] ints(ArrayList<Integer> items) {
        int[] out = new int[items.size()];
        for (int i = 0; i < out.length; i++) out[i] = items.get(i);
        return out;
    }
    private static void add(String title, String value, int kind, int action, int info) {
        titles.add(title); values.add(value); kinds.add(kind); actions.add(action); help.add(info);
    }
    private static void heading(String title) { add(title, "", 1, 0, 0); }
    private static void action(String title, int action) { add(title, "", 3, action, 0); }
    private static void number(String key, String title, String initial, String unit, String description, double min, double max, boolean decimal) {
        row(new Setting(key, title, initial, unit, description, decimal ? 2 : 1, min, max));
    }
    private static void toggle(String key, String title, boolean initial, String description) {
        row(new Setting(key, title, initial ? "1" : "0", "", description, 0, 0, 1));
    }
    private static void text(String key, String title, String initial, String description, double min, double max) {
        row(new Setting(key, title, initial, "", description, 3, min, max));
    }
    private static void row(Setting setting) {
        int index = settings.size(); settings.add(setting);
        add(setting.title, display(setting, value(setting)), setting.kind == 0 ? 2 : 0, 100 + index, 1000 + index);
    }
    private static void showSection() {
        if (selected == 0) {
            number("navPercent", "导航音量", "50", "%", "单独衰减导航语音，不改变系统媒体音量或音乐音量。0% 为静音；1% 为最低非零档；100% 为未衰减。每次调整 1%。", 0, 100, false);
            action("试听导航语音", 4);
            heading("道路播报");
            toggle("guideRoadAnnounce", "导航道路播报", true, "导航中播报道路信息。");
            toggle("guideSimpleAnnounce", "导航简洁播报", false, "精简导航中的自定义道路播报。");
            toggle("cruiseRoadAnnounce", "巡航道路播报", true, "巡航中播报道路信息。");
            toggle("cruiseSimpleAnnounce", "巡航简洁播报", false, "精简巡航中的自定义道路播报。");
            number("roadDurationThreshold", "道路播报间隔", "2000", "毫秒", "两次自定义道路播报之间的最短时间。仅控制额外道路播报，不改变高德转向提示。", 0, 60000, false);
            toggle("ignoreMusic", "忽略音乐播放状态", true, "允许原有定制播报逻辑忽略音乐播放状态；不改变系统媒体音量。");
            text("audioChannelLock", "锁定音频通道", "", "留空为自动。可指定 0—20 的通道编号；设备需支持对应通道。修改后重新启动高德。", 0, 20);
        } else if (selected == 1) {
            heading("导航红绿灯");
            toggle("guideTrafficLightAnnounce", "导航红绿灯播报", true, "启用导航中的红绿灯播报。");
            toggle("guideSimpleAnnounce2", "导航关闭绿灯播报", false, "开启后省略导航中的绿灯播报。");
            number("guideSpeedLimit", "导航播报速度", "25", "km/h", "无有效路口距离数据时，按此速度上限判断是否进行红绿灯播报。", 0, 200, false);
            number("guideDistanceLimit", "导航播报距离", "200", "米", "有有效路口距离数据时，在该距离内进行红绿灯播报。", 1, 2000, false);
            number("guideTrafficCountdown", "导航倒计时提示", "5", "秒", "红绿灯倒计时达到此秒数时触发提示。", 1, 120, false);
            heading("巡航红绿灯");
            toggle("cruiseTrafficLightAnnounce", "巡航红绿灯播报", true, "启用巡航中的红绿灯播报。");
            toggle("cruiseSimpleAnnounce2", "巡航关闭绿灯播报", false, "开启后省略巡航中的绿灯播报。");
            number("cruiseSpeedLimit", "巡航播报速度", "25", "km/h", "无有效路口距离数据时，按此速度上限判断是否进行红绿灯播报。", 0, 200, false);
            number("cruiseDistanceLimit", "巡航播报距离", "200", "米", "有有效路口距离数据时，在该距离内进行红绿灯播报。", 1, 2000, false);
            number("cruiseTrafficCountdown", "巡航倒计时提示", "5", "秒", "红绿灯倒计时达到此秒数时触发提示。", 1, 120, false);
            number("cruiseTrafficCountdownCombined", "组合灯播报窗口", "7", "秒", "把倒计时落在该时间窗口内的其他方向信号灯合并播报。", 1, 120, false);
            text("tliSdkUseId", "领航用户 ID", "", "用于原有路口距离服务。留空时使用速度限制条件。修改后重新启动高德。", -1, -1);
        } else if (selected == 2) {
            toggle("weatherAnnounce", "天气播报", true, "播报所在城市及天气信息。");
            toggle("weatherSimpleAnnounce", "天气简洁播报", true, "使用精简天气内容。");
            toggle("cityAnnounceProvince", "播报省份", false, "在城市名称中包含省份。");
            toggle("cityAnnounceCity", "播报城市", true, "在天气播报中包含城市名称。");
            toggle("cityAnnounceDistrict", "播报区县", true, "在天气播报中包含区县名称。");
            number("weatherDelay", "天气播报延迟", "1000", "毫秒", "首次天气播报前的等待时间。", 0, 60000, false);
            number("locationRefresh", "天气位置刷新间隔", "30", "秒", "自定义天气与地理编码的位置刷新间隔，不改变导航 GPS 更新频率。", 5, 3600, false);
            text("weatherGreeting", "天气问候语", "", "在天气播报开头加入的文字。", -1, -1);
            text("apiKey", "高德 Key", "", "自定义天气服务使用的 Key；留空沿用原包的备用接口。", -1, -1);
        } else if (selected == 3) {
            heading("启动");
            toggle("launchOnBoot", "开机启动", true, "系统开机完成并解锁后自动打开高德。部分设备需要允许自启动、后台弹出界面或显示在其他应用上层。首次安装后需手动打开一次。");
            action("自启动权限", 5);
            action("显示在其他应用上层", 6);
            heading("地图显示");
            toggle("auto3D", "自动 3D 视角", true, "保留原包的自动 3D 视角切换功能。");
            number("autoDayNightMode", "地图昼夜切换模式", "3", "", "0：关闭额外切换；1：亮度与时间；2：系统主题与时间；3：高德自动。扩展设置页跟随高德昼夜主题。", 0, 3, false);
            number("brightnessThreshold", "亮度切换阈值", "33", "", "模式 1 使用的系统亮度原始值阈值，不是百分比。亮度数值范围取决于设备。", 0, 65535, false);
            number("sunriseTime", "日出时间", "6", "时", "亮度或主题模式使用的白天开始小时，采用 24 小时制。", 0, 23, false);
            number("sunsetTime", "日落时间", "18", "时", "亮度或主题模式使用的夜间开始小时，采用 24 小时制。", 0, 23, false);
            text("dpiValue", "地图界面 DPI", "", "留空时沿用设备 DPI，再乘以 88%。输入自定义 DPI 时也会乘以 88%。数值越小，地图界面元素越小。修改后重新启动高德。", 80, 1000);
            toggle("useConfigFile", "使用配置文件", true, "保留原包的 ttsSettings.json 配置文件读写。设置保存属于配置写入，不是调试日志。");
        } else {
            number("floatWindowFontSize", "悬浮窗字体大小", "15", "sp", "原有导航小窗的字体大小；在小窗下次创建时应用。", 8, 48, false);
            number("floatWindowOpacity", "悬浮窗透明度", "0.7", "", "原有导航小窗的不透明度。0 为全透明，1 为不透明；在小窗下次创建时应用。", 0, 1, true);
            number("floatWindowScale", "悬浮窗缩放比例", "1.0", "", "原有导航小窗的缩放比例。1 为原尺寸；在小窗下次创建时应用。", 0.1, 1, true);
        }
    }

    private static String value(Setting setting) {
        if (setting.key.equals("navPercent")) return String.valueOf(NavVolume.getPercent());
        if (setting.key.equals("launchOnBoot")) return BootStartup.enabled(activity) ? "1" : "0";
        try {
            Field field = ttsSettings.class.getDeclaredField(setting.key);
            field.setAccessible(true);
            return String.valueOf(field.get(null));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(setting.key, error);
        }
    }

    private static String display(Setting setting, String value) {
        if (setting.kind == 0) return value.equals("0") ? "关闭" : "开启";
        if (value.isEmpty()) return "未设置";
        if (setting.key.equals("apiKey")) return "已设置";
        return value + (setting.unit.isEmpty() ? "" : " " + setting.unit);
    }

    private static void save(Setting setting, String value) {
        if (setting.key.equals("navPercent")) NavVolume.setPercent(Integer.parseInt(value));
        else if (setting.key.equals("launchOnBoot")) BootStartup.setEnabled(activity, value.equals("1"));
        else {
            ttsSettings.setDynamic(null, setting.key, value);
            activity.getSharedPreferences("enhanced_dynamic_settings", 0).edit().putString(setting.key, value).apply();
            new File(ttsSettings.CONFIG_DIR, ttsSettings.CONFIG_NAME).delete();
            ttsSettings.generateSettingsFile(activity);
        }
    }

}
