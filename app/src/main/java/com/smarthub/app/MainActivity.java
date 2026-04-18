package com.smarthub.app;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.TextView;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int SAMPLE_RATE = 16000;
    private AudioRecord audioRecord;
    private boolean isListening = false;
    private Thread recordingThread;
    
    private Model model;
    private Recognizer recognizer;
    
    // UI Elements
    private TextView statusText;
    private TextView volumeText;
    private TextView logText;

    private boolean wakeWordDetected = false;
    private FileOutputStream pcmOut;
    private int silenceFrames = 0;
    private int totalRecordingFrames = 0;
    
    // RMS threshold is usually between 50 and 300 for a quiet room.
    private final int SILENCE_RMS_THRESHOLD = 200; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        volumeText = findViewById(R.id.volumeText);
        logText = findViewById(R.id.logText);

        logToScreen("System Booting...");

        new Thread(() -> {
            try {
                logToScreen("Unpacking AI Model (May take a minute on J2)...");
                String modelPath = copyAssets();
                model = new Model(modelPath);
                recognizer = new Recognizer(model, SAMPLE_RATE);
                
                runOnUiThread(() -> {
                    statusText.setText("READY");
                    statusText.setTextColor(0xFF00FF00); // Green
                });
                logToScreen("Model loaded. Listening for 'alexa'...");
                startMicrophone();
            } catch (Exception e) {
                logToScreen("ERROR: " + e.getMessage());
                runOnUiThread(() -> {
                    statusText.setText("ERROR");
                    statusText.setTextColor(0xFFFF0000); // Red
                });
            }
        }).start();
    }

    private void logToScreen(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = "[" + time + "] " + message + "\n";
        runOnUiThread(() -> {
            logText.append(logEntry);
            // Optional: Log to standard logcat too
            Log.d("SmartHub", message); 
        });
    }

    private void updateVolumeUI(int volume) {
        runOnUiThread(() -> volumeText.setText("Current Volume (RMS): " + volume));
    }

    // Asset copying remains the same
    private String copyAssets() {
        File assetDir = getExternalFilesDir(null);
        File modelDir = new File(assetDir, "model");
        if (!modelDir.exists()) {
            modelDir.mkdirs();
            copyAssetFolder("sync/model", modelDir.getAbsolutePath());
        }
        return modelDir.getAbsolutePath();
    }

    private void copyAssetFolder(String srcName, String dstName) {
        try {
            String[] list = getAssets().list(srcName);
            if (list == null || list.length == 0) return;
            for (String file : list) {
                String src = srcName + "/" + file;
                String dest = dstName + "/" + file;
                if (file.contains(".")) {
                    InputStream in = getAssets().open(src);
                    OutputStream out = new FileOutputStream(dest);
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) { out.write(buffer, 0, read); }
                    in.close(); out.close();
                } else {
                    new File(dest).mkdirs();
                    copyAssetFolder(src, dest);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startMicrophone() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioRecord.startRecording();
        isListening = true;

        recordingThread = new Thread(() -> {
            short[] buffer = new short[512];
            int loopCounter = 0;
            
            while (isListening) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    
                    // Calculate RMS (Root Mean Square) for volume
                    double sum = 0;
                    for (int i = 0; i < read; i++) {
                        sum += buffer[i] * buffer[i];
                    }
                    int rms = (int) Math.sqrt(sum / read);
                    
                    // Update UI volume every ~15 frames to prevent UI lag
                    loopCounter++;
                    if (loopCounter % 15 == 0) { updateVolumeUI(rms); }

                    if (!wakeWordDetected) {
                        // Phase 1: Wake Word Spotting
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            if (recognizer.getResult().toLowerCase().contains("alexa")) startCommandRecording();
                        } else {
                            if (recognizer.getPartialResult().toLowerCase().contains("alexa")) startCommandRecording();
                        }
                    } else {
                        // Phase 2: Command Recording & Silence Detection
                        try {
                            byte[] bData = new byte[read * 2];
                            for (int i = 0; i < read; i++) {
                                bData[i * 2] = (byte) (buffer[i] & 0x00FF);
                                bData[(i * 2) + 1] = (byte) (buffer[i] >> 8);
                            }
                            pcmOut.write(bData);
                            totalRecordingFrames++;

                            if (rms < SILENCE_RMS_THRESHOLD) {
                                silenceFrames++;
                            } else {
                                silenceFrames = 0; 
                            }

                            // 1.5 Seconds of silence = (16000 / 512) * 1.5 ≈ 47 frames
                            // Hard timeout: 8 seconds maximum recording ≈ 250 frames
                            if (silenceFrames > 47) {
                                logToScreen("Silence detected. Stopping.");
                                stopCommandRecording();
                            } else if (totalRecordingFrames > 250) {
                                logToScreen("Max time reached. Force stopping.");
                                stopCommandRecording();
                            }
                            
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
            }
        });
        recordingThread.start();
    }

    private void startCommandRecording() {
        wakeWordDetected = true;
        silenceFrames = 0;
        totalRecordingFrames = 0;
        
        runOnUiThread(() -> {
            statusText.setText("LISTENING TO COMMAND");
            statusText.setTextColor(0xFF0000FF); // Blue
        });
        logToScreen(">>> Wake word detected!");
        
        try {
            File dir = getExternalFilesDir(null); // Safer storage location
            File file = new File(dir, "cmd_" + System.currentTimeMillis() + ".pcm");
            pcmOut = new FileOutputStream(file);
            logToScreen("Recording to: " + file.getName());
        } catch (Exception e) { logToScreen("Storage Error: " + e.getMessage()); }
    }

    private void stopCommandRecording() {
        wakeWordDetected = false;
        try { if (pcmOut != null) pcmOut.close(); } catch (Exception e) {}
        recognizer.reset();
        
        runOnUiThread(() -> {
            statusText.setText("READY");
            statusText.setTextColor(0xFF00FF00); // Green
        });
        logToScreen("<<< Saved command. Resuming wake word search.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isListening = false;
        if (audioRecord != null) audioRecord.release();
        if (recognizer != null) recognizer.close();
        if (model != null) model.close();
    }
}
