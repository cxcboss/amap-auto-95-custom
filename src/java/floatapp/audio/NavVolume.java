package floatapp.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import java.util.HashMap;

public final class NavVolume {
    private static volatile int percent = 50;
    private static SharedPreferences preferences;
    private static final ThreadLocal<byte[]> pcmScratch = new ThreadLocal<>();
    public static void init(Context context) {
        preferences = context.getSharedPreferences("navigation_audio", Context.MODE_PRIVATE);
        percent = Math.max(0, Math.min(100, preferences.getInt("percent", 50)));
        context.getSharedPreferences("SharedPreferences", 0).edit()
            .putBoolean("isWriteLog", false).putBoolean("isWriteProtocolLog", false).apply();
    }
    public static int getPercent() { return percent; }
    public static void setPercent(int value) {
        percent = value;
        preferences.edit().putInt("percent", value).apply();
    }
    public static int scaleDpi(int original) { return Math.round(original * 0.88f); }
    public static void play(AudioTrack track) {
        track.play();
    }
    public static int setStereoVolume(AudioTrack track, float left, float right) {
        return track.setStereoVolume(left, right);
    }
    private static byte[] pcm(byte[] input, int offset, int length) {
        byte[] out = pcmScratch.get();
        if (out == null || out.length < length) {
            out = new byte[length]; pcmScratch.set(out);
        }
        int gain = percent;
        for (int i = 0; i < length; i += 2) {
            int sample = (short)((input[offset + i] & 255) | (input[offset + i + 1] << 8));
            int scaled = sample * gain / 100;
            out[i] = (byte)scaled; out[i + 1] = (byte)(scaled >> 8);
        }
        return out;
    }
    public static int write(AudioTrack track, byte[] input, int offset, int length) {
        return track.write(pcm(input, offset, length), 0, length);
    }
    public static int write(AudioTrack track, byte[] input, int offset, int length, int mode) {
        return track.write(pcm(input, offset, length), 0, length, mode);
    }
    public static void setVolume(MediaPlayer player, float left, float right) {
        float gain = percent / 100f;
        player.setVolume(left * gain, right * gain);
    }
    public static int speak(TextToSpeech tts, CharSequence text, int mode, Bundle params, String id) {
        Bundle adjusted = params == null ? new Bundle() : new Bundle(params);
        adjusted.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
            adjusted.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) * percent / 100f);
        return tts.speak(text, mode, adjusted, id);
    }
    public static int speak(TextToSpeech tts, String text, int mode, HashMap<String, String> params) {
        HashMap<String, String> adjusted = new HashMap<>(params);
        adjusted.put(TextToSpeech.Engine.KEY_PARAM_VOLUME, String.valueOf(percent / 100f));
        return tts.speak(text, mode, adjusted);
    }
}
