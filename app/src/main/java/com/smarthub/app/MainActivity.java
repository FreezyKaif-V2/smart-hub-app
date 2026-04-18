package com.smarthub.app;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;

public class MainActivity extends Activity implements edu.cmu.pocketsphinx.RecognitionListener {
    private static final int SAMPLE_RATE = 16000;
    private edu.cmu.pocketsphinx.SpeechRecognizer wakeRecognizer;
    private SpeechRecognizer systemRecognizer;
    private Intent speechIntent;
    
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

        // Initialize Native Speech Recognizer (For Phase 2 Transcription)
        systemRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // Setting multi-language support for Hinglish
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_SUPPORTED, new String[]{"en-IN", "hi-IN"});
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        setupSystemSpeechListener();

        logToScreen("V6 Hub Booting (Transcription Enabled)...");

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
                File assetDir = new File(getExternalFilesDir(null), "sync");
                copyAssets(assetDir);
                File kwsFile = new File(assetDir, "keywords.kws");
                String kws = "alexa /1e-" + sensitivity + "/\n";
                try (FileOutputStream fos = new FileOutputStream(kwsFile)) { fos.write(kws.getBytes()); }

                if (wakeRecognizer != null) { wakeRecognizer.cancel(); wakeRecognizer.shutdown(); }

                wakeRecognizer = SpeechRecognizerSetup.defaultSetup()
                        .setAcousticModel(new File(assetDir, "en-us-ptm"))
                        .setDictionary(new File(assetDir, "custom.dict"))
                        .getRecognizer();
                wakeRecognizer.addListener(this);
                wakeRecognizer.addKeywordSearch("WAKE", kwsFile);
                
                runOnUiThread(() -> {
                    wakeRecognizer.startListening("WAKE");
                    statusText.setText("READY");
                    statusText.setTextColor(0xFF00FF00);
                    logToScreen("Listening for Alexa...");
                });
            } catch (Exception e) { logToScreen("Init Error: " + e.getMessage()); }
        }).start();
    }

    private void setupSystemSpeechListener() {
        systemRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle b) {
                ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    logToScreen("TRANSCRIPTION: " + matches.get(0));
                }
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    runOnUiThread(() -> volumeText.setText("Live: " + matches.get(0)));
                }
            }
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) { logToScreen("STT Code: " + error); }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    @Override
    public void onPartialResult(Hypothesis hp) {
        if (hp != null && hp.getHypstr().contains("alexa")) {
            wakeRecognizer.cancel(); 
            startCommandRecording();
        }
    }

    private synchronized void startCommandRecording() {
        if (isRecording) return;
        isRecording = true;
        runOnUiThread(() -> {
            statusText.setText("LISTENING...");
            statusText.setTextColor(0xFF0000FF);
        });
        
        logToScreen(">>> Alexa detected. Recording + Transcribing...");
        
        // Start System Speech-to-Text simultaneously
        runOnUiThread(() -> systemRecognizer.startListening(speechIntent));
        
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
            double rollingBase = 800.0;

            while (isRecording) {
                int read = ar.read(buf, 0, buf.length);
                if (read <= 0) continue;
                double sum = 0;
                for (int i = 0; i < read; i++) sum += buf[i] * buf[i];
                int rms = (int) Math.sqrt(sum / read);

                if (total < 20) rollingBase = (rollingBase * 0.9) + (rms * 0.1);
                
                byte[] bData = new byte[read * 2];
                for (int i = 0; i < read; i++) {
                    bData[i*2] = (byte)(buf[i] & 0xff);
                    bData[i*2+1] = (byte)(buf[i] >> 8);
                }
                out.write(bData);
                total++;

                // Wait time reduced: silence > 25 frames (~0.7 seconds)
                if (rms < (rollingBase * 1.3 + 80) && total > 15) {
                    silence++;
                } else {
                    silence = 0;
                }

                if (silence > 25 || total > 350) break;
            }
            ar.stop(); ar.release();
            
            // Stop transcription engine
            runOnUiThread(() -> systemRecognizer.stopListening());
            
            File wav = new File(getExternalFilesDir(null), "cmd_" + System.currentTimeMillis() + ".wav");
            rawToWave(pcm, wav);
            logToScreen("File Saved: " + wav.getName());
        } catch (Exception e) { logToScreen("Rec Fail"); }
        
        isRecording = false;
        runOnUiThread(() -> {
            wakeRecognizer.startListening("WAKE");
            statusText.setText("READY");
            statusText.setTextColor(0xFF00FF00);
        });
    }

    private void logToScreen(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> logText.append("[" + t + "] " + msg + "\n"));
    }

    private void copyAssets(File assetDir) throws IOException {
        String[] files = {"en-us-ptm/mdef", "en-us-ptm/means", "en-us-ptm/sendump", 
                          "en-us-ptm/variances", "en-us-ptm/transition_matrices", 
                          "en-us-ptm/noisedict", "en-us-ptm/feat.params", "custom.dict"};
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
    @Override public void onError(Exception e) {}
    @Override public void onTimeout() {}

    @Override protected void onDestroy() {
        super.onDestroy();
        if (wakeRecognizer != null) wakeRecognizer.shutdown();
        if (systemRecognizer != null) systemRecognizer.destroy();
    }
}
