package com.smarthub.app;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizer;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int SAMPLE_RATE = 16000;
    private SpeechRecognizer recognizer;
    private TextView statusText, volumeText, logText, sensLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        volumeText = findViewById(R.id.volumeText);
        logText = findViewById(R.id.logText);
        sensLabel = findViewById(R.id.sensLabel);
        SeekBar sensSlider = findViewById(R.id.sensSlider);

        logToScreen("Booting V5 (PocketSphinx)...");

        sensSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                int val = Math.max(p, 1);
                sensLabel.setText("Sensitivity Exp: 1e-" + val);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                initPocketSphinx(Math.max(s.getProgress(), 1));
            }
        });

        initPocketSphinx(sensSlider.getProgress());
    }

    private void initPocketSphinx(int sensitivity) {
        new Thread(() -> {
            try {
                runOnUiThread(() -> statusText.setText("LOADING..."));
                File assetDir = new File(getExternalFilesDir(null), "sync");
                if (!new File(assetDir, "en-us-ptm").exists()) { copyAssets(); }
                
                // Set threshold
                String kws = "alexa /1e-" + sensitivity + "/\n";
                FileOutputStream fos = new FileOutputStream(new File(assetDir, "keywords.kws"));
                fos.write(kws.getBytes()); fos.close();

                if (recognizer != null) { recognizer.cancel(); recognizer.shutdown(); }

                recognizer = SpeechRecognizerSetup.defaultSetup()
                        .setAcousticModel(new File(assetDir, "en-us-ptm"))
                        .setDictionary(new File(assetDir, "custom.dict"))
                        .getRecognizer();
                recognizer.addListener(this);
                recognizer.addKeywordSearch("WAKE", new File(assetDir, "keywords.kws"));
                
                runOnUiThread(() -> {
                    recognizer.startListening("WAKE");
                    statusText.setText("READY");
                    statusText.setTextColor(0xFF00FF00);
                });
            } catch (Exception e) { logToScreen("Error: " + e.getMessage()); }
        }).start();
    }

    @Override
    public void onPartialResult(Hypothesis hp) {
        if (hp != null && hp.getHypstr().equals("alexa")) {
            recognizer.cancel();
            startCommandRecording();
        }
    }

    private void startCommandRecording() {
        runOnUiThread(() -> {
            statusText.setText("RECORDING");
            statusText.setTextColor(0xFF0000FF);
        });
        new Thread(this::runAudioLoop).start();
    }

    private void runAudioLoop() {
        int bSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bSize);
        if (NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(ar.getAudioSessionId()).setEnabled(true); }

        File pcm = new File(getExternalFilesDir(null), "temp.pcm");
        try (FileOutputStream out = new FileOutputStream(pcm)) {
            ar.startRecording();
            short[] buf = new short[512];
            int silence = 0, total = 0;
            double base = 100.0;

            while (true) {
                int read = ar.read(buf, 0, buf.length);
                if (read <= 0) continue;
                
                double sum = 0;
                for (int i = 0; i < read; i++) sum += buf[i] * buf[i];
                int rms = (int) Math.sqrt(sum / read);
                if (total < 10) base = (base * 0.8) + (rms * 0.2);

                byte[] bData = new byte[read * 2];
                for (int i = 0; i < read; i++) {
                    bData[i*2] = (byte)(buf[i] & 0xff);
                    bData[i*2+1] = (byte)(buf[i] >> 8);
                }
                out.write(bData);
                total++;

                if (rms < (base * 1.5 + 50) && total > 10) silence++; else silence = 0;
                if (silence > 45 || total > 300) break;
            }
            ar.stop(); ar.release();
            
            File wav = new File(getExternalFilesDir(null), "cmd_" + System.currentTimeMillis() + ".wav");
            rawToWave(pcm, wav);
            logToScreen("Saved: " + wav.getName());
        } catch (Exception e) { logToScreen("Rec Error"); }
        
        runOnUiThread(() -> { recognizer.startListening("WAKE"); statusText.setText("READY"); statusText.setTextColor(0xFF00FF00); });
    }

    private void logToScreen(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> logText.append("[" + t + "] " + msg + "\n"));
    }

    private void copyAssets() {
        File dir = new File(getExternalFilesDir(null), "sync");
        dir.mkdirs();
        try {
            for (String f : getAssets().list("sync")) {
                if (f.equals("en-us-ptm")) {
                    new File(dir, f).mkdirs();
                    for (String sub : getAssets().list("sync/" + f)) {
                        copyFile("sync/" + f + "/" + sub, new File(dir, f + "/" + sub));
                    }
                } else { copyFile("sync/" + f, new File(dir, f)); }
            }
        } catch (Exception e) {}
    }

    private void copyFile(String src, File dst) throws IOException {
        try (InputStream in = getAssets().open(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[1024]; int r; while ((r = in.read(b)) != -1) out.write(b, 0, r);
        }
    }

    private void rawToWave(File raw, File wav) throws IOException {
        byte[] data = new byte[(int) raw.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(raw))) { in.readFully(data); }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(wav))) {
            out.writeBytes("RIFF"); out.writeInt(Integer.reverseBytes(36 + data.length));
            out.writeBytes("WAVE"); out.writeBytes("fmt "); out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1)); out.writeShort(Short.reverseBytes((short) 1));
            out.writeInt(Integer.reverseBytes(SAMPLE_RATE)); out.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2));
            out.writeShort(Short.reverseBytes((short) 2)); out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data"); out.writeInt(Integer.reverseBytes(data.length));
            out.write(data);
        }
    }

    @Override public void onResult(Hypothesis h) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onEndOfSpeech() {}
    @Override public void onError(Exception e) {}
    @Override public void onTimeout() {}
}
