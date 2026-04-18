package com.smarthub.app;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.*;
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
    private boolean isRecording = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        volumeText = findViewById(R.id.volumeText);
        logText = findViewById(R.id.logText);
        sensLabel = findViewById(R.id.sensLabel);
        SeekBar sensSlider = findViewById(R.id.sensSlider);

        logToScreen("V5 Local Engine Booting...");

        sensSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                sensLabel.setText("Sensitivity Exp: 1e-" + Math.max(p, 1));
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
                runOnUiThread(() -> statusText.setText("SYNCING ASSETS..."));
                File assetDir = new File(getExternalFilesDir(null), "sync");
                copyAssets(assetDir);
                
                // Set threshold locally
                String kws = "alexa /1e-" + sensitivity + "/\n";
                File kwsFile = new File(assetDir, "keywords.kws");
                try (FileOutputStream fos = new FileOutputStream(kwsFile)) { fos.write(kws.getBytes()); }

                if (recognizer != null) { recognizer.cancel(); recognizer.shutdown(); }

                recognizer = SpeechRecognizerSetup.defaultSetup()
                        .setAcousticModel(new File(assetDir, "en-us-ptm"))
                        .setDictionary(new File(assetDir, "custom.dict"))
                        .getRecognizer();
                recognizer.addListener(this);
                recognizer.addKeywordSearch("WAKE", kwsFile);
                
                runOnUiThread(() -> {
                    recognizer.startListening("WAKE");
                    statusText.setText("READY");
                    statusText.setTextColor(0xFF00FF00);
                    logToScreen("Listening... (Exp: 1e-" + sensitivity + ")");
                });
            } catch (Exception e) { logToScreen("Init Error: " + e.getMessage()); }
        }).start();
    }

    @Override
    public void onPartialResult(Hypothesis hp) {
        if (hp != null && hp.getHypstr().contains("alexa")) {
            recognizer.cancel(); 
            startCommandRecording();
        }
    }

    private synchronized void startCommandRecording() {
        if (isRecording) return;
        isRecording = true;
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
            double rollingBase = 800.0; // Starting at your reported noisy floor

            while (isRecording) {
                int read = ar.read(buf, 0, buf.length);
                if (read <= 0) continue;
                
                double sum = 0;
                for (int i = 0; i < read; i++) sum += buf[i] * buf[i];
                int rms = (int) Math.sqrt(sum / read);

                // Update base for first 1s
                if (total < 30) rollingBase = (rollingBase * 0.95) + (rms * 0.05);

                byte[] bData = new byte[read * 2];
                for (int i = 0; i < read; i++) {
                    bData[i*2] = (byte)(buf[i] & 0xff);
                    bData[i*2+1] = (byte)(buf[i] >> 8);
                }
                out.write(bData);
                total++;

                // If volume is less than 20% above base, count as silence
                if (rms < (rollingBase * 1.2 + 100) && total > 20) {
                    silence++;
                } else {
                    silence = 0;
                }

                // Hard Stop: 2s silence or 10s max
                if (silence > 62 || total > 312) break;
                
                if (total % 10 == 0) {
                    runOnUiThread(() -> volumeText.setText("Vol: " + rms + " | Base: " + (int)rollingBase));
                }
            }
            ar.stop(); ar.release();
            
            File wav = new File(getExternalFilesDir(null), "cmd_" + System.currentTimeMillis() + ".wav");
            rawToWave(pcm, wav);
            logToScreen("Saved: " + wav.getName());
        } catch (Exception e) { logToScreen("Rec Fail"); }
        
        isRecording = false;
        runOnUiThread(() -> {
            recognizer.startListening("WAKE");
            statusText.setText("READY");
            statusText.setTextColor(0xFF00FF00);
            volumeText.setText("Volume: N/A");
        });
    }

    private void logToScreen(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> logText.append("[" + t + "] " + msg + "\n"));
    }

    private void copyAssets(File assetDir) throws IOException {
        String[] files = {"en-us-ptm/mdef", "en-us-ptm/means", "en-us-ptm/sendump", 
                          "en-us-ptm/variances", "en-us-ptm/transition_matrices", "custom.dict"};
        for (String f : files) {
            File dest = new File(assetDir, f);
            if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
            try (InputStream in = getAssets().open("sync/" + f); 
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] b = new byte[1024]; int r; while ((r = in.read(b)) != -1) out.write(b, 0, r);
            }
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
    @Override public void onError(Exception e) { logToScreen("PS Error: " + e.getMessage()); }
    @Override public void onTimeout() {}
}
