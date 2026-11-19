package app.zizi.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * سرویس اصلی سجیو.
 *
 * چرخه:
 *   انتظار → شنیدن «سجیو» → «بله؟» → شنیدن فرمان → جواب → چند ثانیه گوش می‌دهد
 *   (اگر ادامه دادی، ادامهٔ گفت‌وگو؛ اگر سکوت کردی، برمی‌گردد به حالت انتظار)
 */
public class ZiziService extends Service {

    private static final String TAG = "Zizi";
    private static final String CHANNEL = "zizi_channel";
    private static final int NOTIF_ID = 1101;

    /** چند ثانیه بعد از جواب، بدون گفتن «سجیو» گوش بدهد (ادامهٔ گفت‌وگو). */
    private static final long FOLLOW_UP_MS = 9000;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private Brain brain;

    private volatile boolean listening = false;
    private volatile boolean awake = false;      // بعد از «سجیو» true می‌شود
    private volatile boolean followUp = false;   // در حالت ادامهٔ گفت‌وگو
    private volatile boolean busy = false;       // منتظر جواب هوش مصنوعی

    private Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        brain = new Brain(this);
        createChannel();
        startForeground(NOTIF_ID, buildNotification(idleText()));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zizi:listen");
            wakeLock.setReferenceCounted(false);
        }

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("fa", "IR"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setSpeechRate(0.98f);
                ttsReady = true;
            }
        });

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("روی این گوشی موتور تشخیص گفتار نصب نیست.");
            return;
        }
        Prefs.setRunning(this, true);
        startListening();
    }

    private String idleText() {
        return "در حال انتظار برای «" + Prefs.wakeWord(this) + "»";
    }

    // ---------------- چرخهٔ شنود ----------------

    private void startListening() {
        if (listening || busy) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // بعد از چند ثانیه سکوت، خودش تمام می‌کند
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800);

        listening = true;
        try {
            recognizer.startListening(i);
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60000);
        } catch (Exception e) {
            Log.e(TAG, "startListening", e);
            listening = false;
            retryLater(1500);
        }
    }

    private void stopListening() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    private void retryLater(long ms) {
        handler.postDelayed(this::startListening, ms);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            listening = false;
            // سکوت یا نبود صدا → بی‌سروصدا دوباره
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    || error == SpeechRecognizer.ERROR_CLIENT) {
                retryLater(500);
            } else {
                // خطای جدی‌تر (مثلاً مشغول بودن موتور) → کمی صبر
                retryLater(2000);
            }
        }

        @Override
        public void onResults(Bundle results) {
            listening = false;
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) { retryLater(400); return; }
            handleText(list.get(0));
        }

        @Override
        public void onPartialResults(Bundle partial) {
            ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) return;
            String text = list.get(0);

            // در حالت انتظار: به‌محض شنیدن «سجیو» بیدار شو، منتظر پایان جمله نمان
            if (!awake && !followUp && containsWakeWord(text)) {
                awake = true;
                stopListening();
                speak("بله؟");
                handler.postDelayed(ZiziService.this::startListening, 900);
            }
        }

        @Override public void onEvent(int t, Bundle b) {}
    };

    private boolean containsWakeWord(String text) {
        if (text == null) return false;
        String t = normalize(text);
        String w = normalize(Prefs.wakeWord(ZiziService.this));
        return !w.isEmpty() && t.contains(w);
    }

    private String normalize(String s) {
        return s.replace('ي', 'ی').replace('ك', 'ک')
                .replace("\u200c", " ")
                .replaceAll("[\\p{Punct}؟!،,:;.]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    // ---------------- پردازش ----------------

    private void handleText(String raw) {
        String text = normalize(raw);
        Log.d(TAG, "heard: " + text);
        if (text.isEmpty()) { retryLater(300); return; }

        boolean hadWake = containsWakeWord(text);
        if (hadWake) text = text.replace(normalize(Prefs.wakeWord(this)), " ").trim();

        // نه بیدار بودیم، نه ادامهٔ گفت‌وگو، نه کلمهٔ بیدارباش داشت → نادیده
        if (!awake && !followUp && !hadWake) {
            retryLater(300);
            return;
        }
        awake = false;
        followUp = false;

        // «یادت باشه ...» → ذخیره در حافظه، بدون فرستادن به مدل
        String memReply = memoryCommand(text);
        if (memReply != null) {
            speak(memReply);
            scheduleFollowUp();
            return;
        }

        // فرمان‌های آفلاین
        String reply = localCommand(text);
        if (reply != null) {
            speak(reply);
            scheduleFollowUp();
            return;
        }

        // بقیه → هوش مصنوعی
        busy = true;
        updateNotification("دارم فکر می‌کنم…");
        brain.ask(text, new Brain.Callback() {
            @Override public void onAnswer(String answer) {
                busy = false;
                speak(answer);
                scheduleFollowUp();
            }
            @Override public void onError(String message) {
                busy = false;
                speak(message);
                scheduleFollowUp();
            }
        });
    }

    /** بعد از جواب، چند ثانیه بدون «سجیو» گوش می‌دهد تا گفت‌وگو قطع نشود. */
    private void scheduleFollowUp() {
        updateNotification(idleText());
        followUp = true;
        handler.postDelayed(() -> {
            if (followUp) {
                followUp = false;          // زمان تمام شد → برگرد به حالت انتظار
            }
            startListening();
        }, FOLLOW_UP_MS);
    }

    /** «یادت باشه که ...» / «این رو یادت نگه دار» */
    private String memoryCommand(String t) {
        String[] triggers = {"یادت باشه", "یادت بماند", "یادت بمونه", "به یاد داشته باش", "ثبت کن که"};
        for (String tr : triggers) {
            int idx = t.indexOf(tr);
            if (idx >= 0) {
                String note = t.substring(idx + tr.length()).trim();
                note = note.replaceFirst("^(که|این که|اینو)\\s+", "").trim();
                if (note.isEmpty()) return "چی رو یادم باشه؟";
                Prefs.addMemory(this, "- " + note);
                return "باشه، یادم موند.";
            }
        }
        return null;
    }

    private String localCommand(String t) {
        if (t.contains("ساعت چنده") || t.contains("ساعت چند") || t.contains("چه ساعتی")) {
            Calendar c = Calendar.getInstance();
            return String.format(Locale.US, "الان ساعت %d و %d دقیقه‌ست.",
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        }
        if (t.contains("تاریخ") || t.contains("چندمه") || t.contains("چه روزی")) {
            Calendar c = Calendar.getInstance();
            String[] days = {"یکشنبه","دوشنبه","سه‌شنبه","چهارشنبه","پنجشنبه","جمعه","شنبه"};
            return "امروز " + days[c.get(Calendar.DAY_OF_WEEK) - 1] + " است.";
        }
        if (t.contains("خداحافظ") || t.contains("بای بای")) {
            return "باشه، هر وقت خواستی صدام کن.";
        }
        if (t.contains("اسمت چیه") || t.contains("تو کی هستی")) {
            return "من " + Prefs.wakeWord(this) + " هستم، دستیار خودت.";
        }
        if (t.contains("حافظه رو پاک کن") || t.contains("همه چیز رو فراموش کن")) {
            Prefs.clearMemory(this);
            Prefs.clearHistory(this);
            return "همه چیز رو فراموش کردم. از صفر شروع می‌کنیم.";
        }
        return null;
    }

    // ---------------- صدا ----------------

    private void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ttsReady) { handler.postDelayed(() -> speak(text), 400); return; }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zizi");
    }

    // ---------------- اعلان ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "سجیو", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        return b.setContentTitle("سجیو فعال است")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ---------------- چرخهٔ عمر ----------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startListening();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Prefs.setRunning(this, false);
        stopListening();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
