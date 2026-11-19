package app.zizi.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

/** بعد از روشن‌شدن گوشی، زیزی خودش دوباره فعال می‌شود. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent i = new Intent(context, ZiziService.class);
            ContextCompat.startForegroundService(context, i);
        }
    }
}
