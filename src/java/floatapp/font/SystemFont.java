package floatapp.font;

import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public final class SystemFont {
    private static final String path = resolve();

    public static String path() { return path; }

    private static String resolve() {
        if (Build.VERSION.SDK_INT >= 31) {
            TextPaint paint = new TextPaint();
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(32);
            android.graphics.text.PositionedGlyphs glyphs = android.graphics.text.TextRunShaper.shapeTextRun(
                "高德地图", 0, 4, 0, 4, 0, 0, false, paint);
            File file = glyphs.getFont(0).getFile();
            if (file != null && file.canRead()) return file.getAbsolutePath();
        }
        // Older car units expose their default Chinese family through fonts.xml.
        try (FileInputStream stream = new FileInputStream("/system/etc/fonts.xml")) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, "UTF-8");
            boolean chinese = false;
            String best = null;
            int difference = Integer.MAX_VALUE;
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && parser.getName().equals("family")) {
                    String language = parser.getAttributeValue(null, "lang");
                    chinese = language != null && (language.contains("zh-Hans") || language.equals("zh"));
                } else if (event == XmlPullParser.END_TAG && parser.getName().equals("family")) {
                    if (chinese && best != null) return best;
                    chinese = false;
                } else if (chinese && event == XmlPullParser.START_TAG && parser.getName().equals("font")) {
                    String weight = parser.getAttributeValue(null, "weight");
                    String style = parser.getAttributeValue(null, "style");
                    int distance = Math.abs((weight == null ? 400 : Integer.parseInt(weight)) - 400);
                    StringBuilder filename = new StringBuilder();
                    int depth = parser.getDepth();
                    while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
                        if (event == XmlPullParser.TEXT && parser.getDepth() == depth) filename.append(parser.getText());
                    }
                    File file = new File("/system/fonts", filename.toString().trim());
                    if (!"italic".equals(style) && distance < difference && file.canRead()) {
                        best = file.getAbsolutePath();
                        difference = distance;
                    }
                }
            }
        } catch (IOException | XmlPullParserException | NumberFormatException error) {
            // Pre-Android 5 font configuration or a device-specific XML format.
        }
        for (String name : new String[]{"NotoSansCJK-Regular.ttc", "NotoSansSC-Regular.otf",
                "NotoSansHans-Regular.otf", "DroidSansFallback.ttf", "DroidSansFallbackBBK.ttf"}) {
            File file = new File("/system/fonts", name);
            if (file.canRead()) return file.getAbsolutePath();
        }
        return null;
    }
}
