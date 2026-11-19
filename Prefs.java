package app.zizi.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/** تنظیمات سادهٔ زیزی — همه چیز روی خود گوشی ذخیره می‌شود. */
public class Prefs {
    private static final String FILE = "zizi_prefs";

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String wakeWord(Context c) {
        return get(c).getString("wake_word", "سجیو");
    }

    public static void setWakeWord(Context c, String w) {
        get(c).edit().putString("wake_word", w).apply();
    }

    public static String ownerName(Context c) {
        return get(c).getString("owner_name", "");
    }

    public static void setOwnerName(Context c, String n) {
        get(c).edit().putString("owner_name", n).apply();
    }

    public static String apiUrl(Context c) {
        return get(c).getString("api_url", "");
    }

    public static String apiKey(Context c) {
        return get(c).getString("api_key", "");
    }

    public static String model(Context c) {
        return get(c).getString("model", "gpt-4o-mini");
    }

    public static void setApi(Context c, String url, String key, String model) {
        get(c).edit()
                .putString("api_url", url)
                .putString("api_key", key)
                .putString("model", model)
                .apply();
    }

    /** چیزهایی که زیزی دربارهٔ کاربر یاد گرفته است. */
    public static String memory(Context c) {
        return get(c).getString("memory", "");
    }

    public static void setMemory(Context c, String m) {
        get(c).edit().putString("memory", m).apply();
    }

    public static void addMemory(Context c, String line) {
        String old = memory(c);
        String neu = old.isEmpty() ? line : old + "\n" + line;
        setMemory(c, neu);
    }

    public static void clearMemory(Context c) {
        get(c).edit().remove("memory").apply();
    }

    /** تاریخچهٔ گفت‌وگو به شکل JSON، برای اینکه زیزی یادش بماند چه گفتید. */
    public static String history(Context c) {
        return get(c).getString("history", "[]");
    }

    public static void setHistory(Context c, String json) {
        get(c).edit().putString("history", json).apply();
    }

    public static void clearHistory(Context c) {
        get(c).edit().remove("history").apply();
    }

    public static boolean isRunning(Context c) {
        return get(c).getBoolean("running", false);
    }

    public static void setRunning(Context c, boolean r) {
        get(c).edit().putBoolean("running", r).apply();
    }
}
