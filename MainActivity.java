package app.zizi.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MIC = 10;

    private EditText etName, etWake, etUrl, etKey, etModel, etMemory;
    private TextView tvStatus;
    private Button btnStart, btnStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etName = findViewById(R.id.etName);
        etWake = findViewById(R.id.etWake);
        etUrl = findViewById(R.id.etUrl);
        etKey = findViewById(R.id.etKey);
        etModel = findViewById(R.id.etModel);
        etMemory = findViewById(R.id.etMemory);
        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        etName.setText(Prefs.ownerName(this));
        etWake.setText(Prefs.wakeWord(this));
        etUrl.setText(Prefs.apiUrl(this));
        etKey.setText(Prefs.apiKey(this));
        etModel.setText(Prefs.model(this));
        etMemory.setText(Prefs.memory(this));

        btnStart.setOnClickListener(v -> {
            save();
            if (hasMic()) startZizi();
            else askMic();
        });

        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, ZiziService.class);
            i.setAction("STOP");
            startService(i);
            stopService(new Intent(this, ZiziService.class));
            tvStatus.setText("سجیو خاموش است");
        });

        askNotificationIfNeeded();
        refreshStatus();
    }

    private void save() {
        Prefs.setOwnerName(this, etName.getText().toString().trim());
        String w = etWake.getText().toString().trim();
        Prefs.setWakeWord(this, w.isEmpty() ? "سجیو" : w);
        String model = etModel.getText().toString().trim();
        Prefs.setApi(this, etUrl.getText().toString().trim(),
                etKey.getText().toString().trim(),
                model.isEmpty() ? "gpt-4o-mini" : model);
        Prefs.get(this).edit().putString("memory", etMemory.getText().toString().trim()).apply();
    }

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askMic() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    private void askNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    private void startZizi() {
        Intent i = new Intent(this, ZiziService.class);
        ContextCompat.startForegroundService(this, i);
        tvStatus.setText("سجیو روشن شد — بگو «" + Prefs.wakeWord(this) + "»");
        Toast.makeText(this, "سجیو فعال شد", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        tvStatus.setText(Prefs.isRunning(this) ? "سجیو روشن است" : "سجیو خاموش است");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startZizi();
        } else if (requestCode == REQ_MIC) {
            Toast.makeText(this, "بدون اجازهٔ میکروفون سجیو نمی‌تواند بشنود", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }
}
