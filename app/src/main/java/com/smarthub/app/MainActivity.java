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

public class MainActivity extends Activity {
    private static final int SAMPLE_RATE = 16000;
    private AudioRecord audioRecord;
    private boolean isListening = false;
    private Thread recordingThread;
    
    private Model model;
    private Recognizer recognizer;
    private TextView statusText;

    private boolean wakeWordDetected = false;
    private FileOutputStream pcmOut;
    private int silenceFrames = 0;
    // Lower threshold = requires more absolute silence to trigger stop
    private final int SILENCE_THRESHOLD = 500; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusText = new TextView(this);
        statusText.setText("Initializing System...");
        statusText.setTextSize(24f);
        setContentView(statusText);

        new Thread(() -> {
            try {
                String modelPath = copyAssets();
                model = new Model(modelPath);
                recognizer = new Recognizer(model, SAMPLE_RATE);
                runOnUiThread(() -> statusText.setText("Ready. Listening for 'alexa'"));
                startMicrophone();
            } catch (Exception e) {
                Log.e("SmartHub", "Init error", e);
                runOnUiThread(() -> statusText.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

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
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    out.close();
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
            while (isListening) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    if (!wakeWordDetected) {
                        // Phase: Wake Word Spotting
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            if (recognizer.getResult().toLowerCase().contains("alexa")) startCommandRecording();
                        } else {
                            if (recognizer.getPartialResult().toLowerCase().contains("alexa")) startCommandRecording();
                        }
                    } else {
                        // Phase: Command Recording & Silence Detection
                        try {
                            byte[] bData = new byte[read * 2];
                            int maxAmp = 0;
                            for (int i = 0; i < read; i++) {
                                maxAmp = Math.max(maxAmp, Math.abs(buffer[i]));
                                bData[i * 2] = (byte) (buffer[i] & 0x00FF);
                                bData[(i * 2) + 1] = (byte) (buffer[i] >> 8);
                            }
                            pcmOut.write(bData);

                            if (maxAmp < SILENCE_THRESHOLD) {
                                silenceFrames++;
                            } else {
                                silenceFrames = 0;
                            }

                            // 2 Seconds of silence = (16000 / 512) * 2 ≈ 62 frames
                            if (silenceFrames > 62) {
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
        runOnUiThread(() -> statusText.setText("Listening to Command..."));
        try {
            File dir = Environment.getExternalStorageDirectory();
            File file = new File(dir, "command_" + System.currentTimeMillis() + ".pcm");
            pcmOut = new FileOutputStream(file);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void stopCommandRecording() {
        wakeWordDetected = false;
        try {
            if (pcmOut != null) pcmOut.close();
        } catch (Exception e) {}
        recognizer.reset();
        runOnUiThread(() -> statusText.setText("Saved command. Listening for 'alexa'"));
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
