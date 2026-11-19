package app.zizi.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * مغز سجیو.
 *
 * کارش ساده است: متن کاربر را می‌گیرد، همراه با شخصیت و حافظه به سرویس
 * هوش مصنوعی می‌فرستد، و جواب را برمی‌گرداند.
 *
 * تمام هوش از مدل می‌آید، نه از این کلاس. اینجا فقط «شخصیت» را می‌سازیم.
 */
public class Brain {

    public interface Callback {
        void onAnswer(String text);
        void onError(String message);
    }

    private static final int MAX_TURNS = 12; // چند پیام آخر را یادش می‌ماند

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Brain(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** شخصیت سجیو — مهم‌ترین بخش کل برنامه. */
    private String systemPrompt() {
        String name = Prefs.ownerName(ctx);
        String memory = Prefs.memory(ctx);

        StringBuilder sb = new StringBuilder();
        sb.append("تو «سجیو» هستی، دستیار صوتی شخصی یک آدم ایرانی. ")
          .append("تو مثل «نیکو» هستی: یک همراه، نه یک ربات پاسخ‌گو.\n\n");

        sb.append("## لحن و شخصیت\n")
          .append("- گرم، صمیمی، خودی. مثل یک دوست قدیمی حرف بزن، نه مثل دستیار رسمی.\n")
          .append("- فارسی محاوره‌ای و طبیعی. کلمه‌های کتابی و اداری به کار نبر.\n")
          .append("- کمی شوخ‌طبع. گاهی یک طعنهٔ مهربان یا یک جملهٔ بامزه بگو، ولی زیاده‌روی نکن.\n")
          .append("- همدل. وقتی طرفت حالش بد است، اول بشنو و تأیید کن، بعد راه‌حل بده.\n")
          .append("- هرگز توهین، تحقیر یا فحش نده. هرگز.\n")
          .append("- خودت را «من» بنام و طرفت را «تو\".\n\n");

        sb.append("## چطور جواب بدهی\n")
          .append("- این یک گفت‌وگوی صوتی است، پس جواب‌ها باید کوتاه و قابل شنیدن باشند. ")
          .append("معمولاً ۲ تا ۴ جمله. اگر طرفت گفت «بیشتر توضیح بده» یا «کامل بگو»، آن‌وقت مفصل حرف بزن.\n")
          .append("- اگر کسی از تو می‌خواهد «هزار تا روش» بدهد، لازم نیست هزارتا بشماری؛ ")
          .append("۵ تا ۷ روش واقعی و کاربردی بده و بگو اگر خواست ادامه می‌دهی.\n")
          .append("- وقتی کسی درد دل می‌کند، اول یک جملهٔ همدلانه، بعد سؤال بپرس. نصیحت نکن مگر بخواهد.\n")
          .append("- وقتی کسی می‌خواهد با کسی قهر کند یا مشکلی دارد، طرف را قضاوت نکن؛ ")
          .append("گزینه‌های مختلف را با خوبی و بدیشان بگو و انتخاب را به خودش بسپار.\n")
          .append("- اگر چیزی را نمی‌دانی، بگو نمی‌دانی. الکی نساز.\n")
          .append("- از علامت‌های مارک‌داون، ستاره و فهرست‌های طولانی استفاده نکن؛ ")
          .append("چون متنت با صدا خوانده می‌شود و این چیزها شنیده نمی‌شوند.\n\n");

        sb.append("## حافظه\n");
        if (!name.isEmpty()) {
            sb.append("اسم طرفت «").append(name).append("» است. گاهی اسمش را صدا بزن، ولی نه در هر جمله.\n");
        }
        if (memory != null && !memory.trim().isEmpty()) {
            sb.append("این چیزها را دربارهٔ او می‌دانی (از گفت‌وگوهای قبلی):\n")
              .append(memory.trim()).append("\n")
              .append("از این‌ها طبیعی استفاده کن، طوری که انگار خودت یادت مانده. ")
              .append("لازم نیست هر بار بگویی «یادم است که...».\n");
        } else {
            sb.append("هنوز چیز خاصی دربارهٔ او نمی‌دانی.\n");
        }

        return sb.toString();
    }

    /** ساخت آرایهٔ پیام‌ها: شخصیت + چند دور آخر گفت‌وگو + پیام جدید. */
    private JSONArray buildMessages(String userText) throws Exception {
        JSONArray arr = new JSONArray();

        arr.put(new JSONObject()
                .put("role", "system")
                .put("content", systemPrompt()));

        // تاریخچهٔ قبلی
        List<JSONObject> old = new ArrayList<>();
        try {
            JSONArray h = new JSONArray(Prefs.history(ctx));
            for (int i = 0; i < h.length(); i++) old.add(h.getJSONObject(i));
        } catch (Exception ignored) { }

        int from = Math.max(0, old.size() - MAX_TURNS);
        for (int i = from; i < old.size(); i++) arr.put(old.get(i));

        arr.put(new JSONObject()
                .put("role", "user")
                .put("content", userText));

        return arr;
    }

    /** ذخیرهٔ این دور در تاریخچه تا دور بعد یادش بماند. */
    private void saveTurn(String userText, String answer) {
        try {
            JSONArray h = new JSONArray(Prefs.history(ctx));
            JSONArray neu = new JSONArray();
            // فقط آخرین ۲۰ پیام را نگه می‌داریم تا حافظه پر نشود
            int start = Math.max(0, h.length() - 18);
            for (int i = start; i < h.length(); i++) neu.put(h.get(i));
            neu.put(new JSONObject().put("role", "user").put("content", userText));
            neu.put(new JSONObject().put("role", "assistant").put("content", answer));
            Prefs.setHistory(ctx, neu.toString());
        } catch (Exception ignored) { }
    }

    /** پرسیدن از مدل. در ترد جدا اجرا می‌شود. */
    public void ask(String userText, Callback cb) {
        String url = Prefs.apiUrl(ctx);
        String key = Prefs.apiKey(ctx);

        if (url.isEmpty() || key.isEmpty()) {
            cb.onError("سرویس هوش مصنوعی تنظیم نشده. برو در تنظیمات آدرس و کلید را بگذار.");
            return;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject()
                        .put("model", Prefs.model(ctx))
                        .put("messages", buildMessages(userText))
                        .put("temperature", 0.85)
                        .put("max_tokens", 700);

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    String err = read(conn.getErrorStream());
                    String msg;
                    if (code == 401) msg = "کلید API درست نیست.";
                    else if (code == 429) msg = "فعلاً خیلی سریع پرسیدی. یک لحظه صبر کن.";
                    else msg = "سرویس جواب نداد. کد " + code;
                    final String m = msg;
                    main.post(() -> cb.onError(m));
                    return;
                }

                String raw = read(conn.getInputStream());
                String answer = new JSONObject(raw)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim();

                // پاک‌سازی: مارک‌داون و ستاره‌ها را بردار چون با صدا خوانده می‌شود
                answer = answer.replaceAll("[*_#`>]", "")
                               .replaceAll("\\n{2,}", "\n")
                               .trim();

                saveTurn(userText, answer);

                final String a = answer;
                main.post(() -> cb.onAnswer(a));

            } catch (Exception e) {
                final String m = "به سرویس وصل نشدم. اینترنت را چک کن.";
                main.post(() -> cb.onError(m));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String read(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
