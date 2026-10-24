package com.oneclick.alert;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;
    private static final int REQ_PICK_VOICE = 2;
    private static final String PREFS = "settings";
    private static final String KEY_VOICE_URI = "voice_uri";
    private static final String KEY_VOICE_NAME = "voice_name";
    private static final String KEY_VOLUME = "volume";
    private static final String ASSET_VOICE = "guest-waiting.mp3";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private Button alertBtn, micBtn, permBtn, voicePick, voiceTest, voiceReset;
    private TextView micStatus, permStatus, voiceStatus, volVal;
    private ProgressBar meter;
    private SeekBar vol;

    private MediaPlayer voicePlayer;
    private AudioTrack chimeTrack;

    // 마이크 → 이어폰
    private volatile boolean listening = false;
    private volatile float gain = 1.5f;
    private Thread micThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        alertBtn = findViewById(R.id.alertBtn);
        micBtn = findViewById(R.id.micBtn);
        permBtn = findViewById(R.id.permBtn);
        voicePick = findViewById(R.id.voicePick);
        voiceTest = findViewById(R.id.voiceTest);
        voiceReset = findViewById(R.id.voiceReset);
        micStatus = findViewById(R.id.micStatus);
        permStatus = findViewById(R.id.permStatus);
        voiceStatus = findViewById(R.id.voiceStatus);
        volVal = findViewById(R.id.volVal);
        meter = findViewById(R.id.meter);
        vol = findViewById(R.id.vol);

        alertBtn.setOnClickListener(v -> playAlert());
        micBtn.setOnClickListener(v -> {
            if (listening) stopMic(); else tryStartMic();
        });
        // 권한 설정 버튼 → 이 앱의 권한 설정 화면으로 바로 이동
        permBtn.setOnClickListener(v -> openAppSettings());
        voicePick.setOnClickListener(v -> pickVoice());
        voiceTest.setOnClickListener(v -> playVoice());
        voiceReset.setOnClickListener(v -> resetVoice());

        int savedVol = prefs.getInt(KEY_VOLUME, 150);
        vol.setProgress(savedVol);
        applyVolume(savedVol);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { applyVolume(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(KEY_VOLUME, s.getProgress()).apply();
            }
        });

        refreshVoiceStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermStatus(); // 설정 화면에서 돌아오면 상태 갱신
    }

    @Override
    protected void onDestroy() {
        stopMic();
        releaseVoice();
        if (chimeTrack != null) { chimeTrack.release(); chimeTrack = null; }
        super.onDestroy();
    }

    /* ---------------- 알림 ---------------- */

    private void playAlert() {
        alertBtn.animate().cancel();
        alertBtn.setScaleX(0.94f);
        alertBtn.setScaleY(0.94f);
        alertBtn.animate().scaleX(1f).scaleY(1f).setDuration(220).start();

        playChime();
        ui.removeCallbacks(voiceRunnable);
        ui.postDelayed(voiceRunnable, 800);

        Vibrator vib = getSystemService(Vibrator.class);
        if (vib != null && vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200, 100, 200}, -1));
        }
    }

    private final Runnable voiceRunnable = this::playVoice;

    /** 웹 버전과 같은 '딩-동' 차임 (880Hz → 659Hz, 각각 0.6초 감쇠) */
    private void playChime() {
        final int sr = 44100;
        final int total = (int) (sr * 0.98);
        short[] pcm = new short[total];
        double[][] tones = {{880, 0.0}, {659, 0.35}};
        for (double[] t : tones) {
            int start = (int) (t[1] * sr);
            int len = (int) (0.6 * sr);
            for (int i = 0; i < len && start + i < total; i++) {
                double time = (double) i / sr;
                double attack = Math.min(1.0, time / 0.01);
                double env = attack * 0.5 * Math.pow(0.001 / 0.5, time / 0.6);
                double s = Math.sin(2 * Math.PI * t[0] * time) * env;
                int v = pcm[start + i] + (int) (s * 32767);
                pcm[start + i] = (short) Math.max(-32768, Math.min(32767, v));
            }
        }

        if (chimeTrack != null) { chimeTrack.release(); chimeTrack = null; }
        chimeTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        chimeTrack.write(pcm, 0, pcm.length);
        chimeTrack.play();
    }

    /* ---------------- 안내 음성 (mp3) ---------------- */

    private void pickVoice() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, REQ_PICK_VOICE);
        } catch (Exception e) {
            setStatus(voiceStatus, "파일 선택 화면을 열 수 없어요.", false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_VOICE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        prefs.edit()
                .putString(KEY_VOICE_URI, uri.toString())
                .putString(KEY_VOICE_NAME, displayName(uri))
                .apply();
        refreshVoiceStatus();
        playVoice();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "선택한 파일";
    }

    private boolean hasAssetVoice() {
        try (AssetFileDescriptor fd = getAssets().openFd(ASSET_VOICE)) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 직접 고른 파일을 지우고 기본 음성으로 되돌림 */
    private void resetVoice() {
        String saved = prefs.getString(KEY_VOICE_URI, null);
        if (saved != null) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(saved), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
        prefs.edit().remove(KEY_VOICE_URI).remove(KEY_VOICE_NAME).apply();
        refreshVoiceStatus();
        playVoice();
    }

    private void refreshVoiceStatus() {
        String name = prefs.getString(KEY_VOICE_NAME, null);
        boolean custom = prefs.getString(KEY_VOICE_URI, null) != null;
        voiceReset.setVisibility(custom && hasAssetVoice() ? View.VISIBLE : View.GONE);
        if (custom) {
            setStatus(voiceStatus, "사용 중: " + name, true);
        } else if (hasAssetVoice()) {
            setStatus(voiceStatus, "사용 중: 기본 음성", true);
        } else {
            setStatus(voiceStatus, "음성 파일이 없어요. mp3 선택을 눌러 주세요.", false);
        }
    }

    private void playVoice() {
        releaseVoice();
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        try {
            String saved = prefs.getString(KEY_VOICE_URI, null);
            if (saved != null) {
                mp.setDataSource(this, Uri.parse(saved));
            } else {
                try (AssetFileDescriptor fd = getAssets().openFd(ASSET_VOICE)) {
                    mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                }
            }
            mp.setOnCompletionListener(p -> releaseVoice());
            mp.prepare();
            mp.start();
            voicePlayer = mp;
        } catch (Exception e) {
            mp.release();
            if (prefs.getString(KEY_VOICE_URI, null) != null) {
                setStatus(voiceStatus, "저장된 파일을 열 수 없어요. mp3를 다시 선택해 주세요.", false);
            }
        }
    }

    private void releaseVoice() {
        if (voicePlayer != null) {
            try { voicePlayer.release(); } catch (Exception ignored) { }
            voicePlayer = null;
        }
    }

    /* ---------------- 마이크 권한 ---------------- */

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermStatus() {
        if (hasMicPermission()) setStatus(permStatus, "허용됨", true);
        else setStatus(permStatus, "허용 안 됨 · 권한 설정을 눌러 허용해 주세요", false);
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        refreshPermStatus();
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            tryStartMic();
        } else {
            setStatus(micStatus, "마이크 권한이 필요해요. 아래 권한 설정을 눌러 주세요.", false);
        }
    }

    /* ---------------- 마이크 → 이어폰 ---------------- */

    private boolean headsetConnected() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    return true;
            }
        }
        return false;
    }

    private void tryStartMic() {
        if (!hasMicPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!headsetConnected()) {
            setStatus(micStatus, "이어폰이 연결되지 않았어요. 하울링 방지를 위해 이어폰을 먼저 연결해 주세요.", false);
            return;
        }
        startMic();
    }

    private void applyVolume(int percent) {
        gain = percent / 100f;
        volVal.setText(percent + "%");
    }

    @SuppressLint("MissingPermission")
    private void startMic() {
        listening = true;
        micBtn.setText("끄기");
        micBtn.setBackgroundResource(R.drawable.bg_pill_on);
        micBtn.setTextColor(0xFFFFFFFF);
        setStatus(micStatus, "듣는 중", true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        micThread = new Thread(() -> {
            final int sr = 48000;
            int recMin = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int trkMin = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec = null;
            AudioTrack trk = null;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, sr,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recMin * 2);
                trk = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(sr)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(trkMin)
                        .build();

                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
                rec.startRecording();
                trk.play();

                short[] buf = new short[256];
                long lastMeter = 0;
                int peak = 0;
                while (listening) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    float g = gain;
                    for (int i = 0; i < n; i++) {
                        int v = (int) (buf[i] * g);
                        if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                        buf[i] = (short) v;
                        int a = Math.abs(v);
                        if (a > peak) peak = a;
                    }
                    trk.write(buf, 0, n);
                    long now = System.currentTimeMillis();
                    if (now - lastMeter > 60) {
                        final int level = Math.min(100, (int) (peak / 32768f * 140));
                        ui.post(() -> meter.setProgress(level));
                        peak = 0;
                        lastMeter = now;
                    }
                }
            } catch (Exception e) {
                ui.post(() -> {
                    stopMic();
                    setStatus(micStatus, "마이크를 열 수 없어요. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요.", false);
                });
            } finally {
                if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
                if (trk != null) { try { trk.stop(); } catch (Exception ignored) { } trk.release(); }
            }
        }, "mic-passthrough");
        micThread.start();
    }

    private void stopMic() {
        listening = false;
        if (micThread != null) {
            try { micThread.join(500); } catch (InterruptedException ignored) { }
            micThread = null;
        }
        if (micBtn == null) return;
        micBtn.setText("켜기");
        micBtn.setBackgroundResource(R.drawable.bg_pill);
        micBtn.setTextColor(getColor(R.color.fg));
        meter.setProgress(0);
        setStatus(micStatus, "꺼짐", null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /* ---------------- 공통 ---------------- */

    private void setStatus(TextView tv, String text, Boolean ok) {
        tv.setText(text);
        if (ok == null) {
            tv.setTextColor(getColor(R.color.muted));
            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        } else {
            tv.setTextColor(getColor(ok ? R.color.live : R.color.alert));
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        tv.setVisibility(View.VISIBLE);
    }
}
