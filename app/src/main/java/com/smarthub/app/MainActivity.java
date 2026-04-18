package com.smarthub.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private edu.cmu.pocketsphinx.SpeechRecognizer wakeRecognizer;
    private SpeechRecognizer systemRecognizer;
    private Intent speechIntent;
    
    private TextView statusText, volumeText, logText, sensLabel;
    private boolean isRecording = false;
    private boolean hasLoggedRms = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        volumeText = findViewById(R.id.volumeText);
        logText = findViewById(R.id.logText);
        sensLabel = findViewById(R.id.sensLabel);
        SeekBar sensSlider = findViewById(R.id.sensSlider);

        logToScreen("=== V9 Deep Hardware Release ===");

        boolean isAvailable = SpeechRecognizer.isRecognitionAvailable(this);
        logToScreen("STT Engine Available: " + isAvailable);

        if (isAvailable) {
            systemRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            speechIntent.putExtra("android.speech.extra.PREFER_OFFLINE", true); 
            setupSystemSpeechListener();
        } else {
            logToScreen("CRITICAL: STT App is missing!");
        }

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

                if (wakeRecognizer != null) { 
                    wakeRecognizer.cancel(); 
                    wakeRecognizer.shutdown(); 
                }

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
                    logToScreen("PS: Listening for Alexa...");
                });
            } catch (Exception e) { logToScreen("PS Init Error: " + e.getMessage()); }
        }).start();
    }

    private void setupSystemSpeechListener() {
        systemRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                logToScreen("STT: Mic successfully opened!");
            }
            @Override public void onBeginningOfSpeech() {
                logToScreen("STT: Hearing speech...");
            }
            @Override public void onRmsChanged(float rmsdB) {
                if (!hasLoggedRms) {
                    logToScreen("STT: Audio flowing smoothly.");
                    hasLoggedRms = true;
                }
            }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() {
                logToScreen("STT: Silence detected. Processing.");
                runOnUiThread(() -> {
                    statusText.setText("PROCESSING...");
                    statusText.setTextColor(0xFFFFA500); 
                });
            }
            @Override public void onError(int error) { 
                String message = "Code " + error;
                switch (error) {
                    case SpeechRecognizer.ERROR_AUDIO: message = "Audio recording error (Hardware Locked)"; break;
                    case SpeechRecognizer.ERROR_CLIENT: message = "Client error"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: message = "Insufficient permissions"; break;
                    case SpeechRecognizer.ERROR_NETWORK: message = "Network error"; break;
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: message = "Network timeout"; break;
                    case SpeechRecognizer.ERROR_NO_MATCH: message = "No match"; break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: message = "Service busy"; break;
                    case SpeechRecognizer.ERROR_SERVER: message = "Server error"; break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: message = "Speech timeout"; break;
                }
                logToScreen("STT ERROR: " + message); 
                restartWakeWord();
            }
            @Override public void onResults(Bundle b) {
                ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String transcription = matches.get(0);
                    logToScreen("STT Result: " + transcription);
                    runOnUiThread(() -> volumeText.setText("Cmd: " + transcription));
                } else {
                    logToScreen("STT Result: EMPTY");
                }
                restartWakeWord();
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    runOnUiThread(() -> volumeText.setText("Live: " + matches.get(0)));
                }
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    @Override
    public void onPartialResult(Hypothesis hp) {
        if (hp != null && hp.getHypstr().contains("alexa")) {
            // CRITICAL CHANGE: Completely destroy the engine to force hardware mic release
            wakeRecognizer.cancel();
            wakeRecognizer.shutdown();
            wakeRecognizer = null;
            startCommandTranscription();
        }
    }

    private synchronized void startCommandTranscription() {
        if (isRecording) return;
        isRecording = true;
        hasLoggedRms = false;
        
        runOnUiThread(() -> {
            statusText.setText("LISTENING (Google STT)");
            statusText.setTextColor(0xFF0000FF);
        });
        
        logToScreen(">>> Alexa detected.");
        logToScreen("Step 1: PS Engine DESTROYED.");
        
        // Wait a full 1.0 seconds to guarantee OS-level mic reset
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            logToScreen("Step 2: Triggering systemRecognizer...");
            try {
                systemRecognizer.startListening(speechIntent);
                logToScreen("Step 3: Intent fired. Waiting...");
            } catch (Exception e) {
                logToScreen("CRITICAL STT CRASH: " + e.getMessage());
                restartWakeWord();
            }
        }, 1000); 
    }

    private void restartWakeWord() {
        isRecording = false;
        runOnUiThread(() -> {
            statusText.setText("REBOOTING ENGINE...");
            statusText.setTextColor(0xFFFFA500);
            volumeText.setText("Volume: N/A");
        });
        
        // We have to completely rebuild the PS engine since we destroyed it
        SeekBar sensSlider = findViewById(R.id.sensSlider);
        initPocketSphinx(Math.max(sensSlider.getProgress(), 1));
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
