package com.smarthub.app;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import org.vosk.Model;
import org.vosk.Recognizer;
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

public class MainActivity extends Activity {
    private static final int SAMPLE_RATE = 16000;
    private AudioRecord audioRecord;
    private boolean isListening = false;
    private Thread recordingThread;
    
    private Model model;
    private Recognizer recognizer;
    
    private TextView statusText;
    private TextView volumeText;
    private TextView logText;

    private boolean wakeWordDetected = false;
    private File currentPcmFile;
    private FileOutputStream pcmOut;
    private int silenceFrames = 0;
    private int totalRecordingFrames = 0;
    
    // Auto-Calibrating Noise Floor
    private double baselineRMS = 100.0; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        volumeText = findViewById(R.id.volumeText);
        logText = findViewById(R.id.logText);

        logToScreen("Booting V3 System...");

        new Thread(() -> {
            try {
                String modelPath = copyAssets();
                model = new Model(modelPath);
                
                // GRAMMAR LOCK: Only loads "alexa" into RAM. Massive speed boost for J2.
                recognizer = new Recognizer(model, SAMPLE_RATE, "[\"alexa\", \"[unk]\"]");
                
                runOnUiThread(() -> {
                    statusText.setText("READY");
                    statusText.setTextColor(0xFF00FF00);
                });
                logToScreen("Grammar locked. Listening for 'alexa'...");
                startMicrophone();
            } catch (Exception e) {
                logToScreen("ERROR: " + e.getMessage());
            }
        }).start();
    }

    private void logToScreen(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> logText.append("[" + time + "] " + message + "\n"));
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

    private void copyAssetFolder(String src, String dst) {
        try {
            String[] list = getAssets().list(src);
            if (list == null || list.length == 0) return;
            for (String file : list) {
                String s = src + "/" + file;
                String d = dst + "/" + file;
                if (file.contains(".")) {
                    InputStream in = getAssets().open(s);
                    OutputStream out = new FileOutputStream(d);
                    byte[] buf = new byte[1024];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    in.close(); out.close();
                } else {
                    new File(d).mkdirs();
                    copyAssetFolder(s, d);
                }
            }
        } catch (Exception e) {}
    }

    private void startMicrophone() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        
        // HARDWARE AUDIO CLEANUP
        int audioSessionId = audioRecord.getAudioSessionId();
        if (NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(audioSessionId).setEnabled(true); }
        if (AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(audioSessionId).setEnabled(true); }

        audioRecord.startRecording();
        isListening = true;

        recordingThread = new Thread(() -> {
            short[] buffer = new short[512];
            int loopCounter = 0;
            
            while (isListening) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    double sum = 0;
                    for (int i = 0; i < read; i++) sum += buffer[i] * buffer[i];
                    int rms = (int) Math.sqrt(sum / read);
                    
                    loopCounter++;
                    if (loopCounter % 15 == 0) { 
                        runOnUiThread(() -> volumeText.setText(String.format("Vol: %d | Base: %d", rms, (int)baselineRMS)));
                    }

                    if (!wakeWordDetected) {
                        // Continuously calibrate background noise
                        baselineRMS = (baselineRMS * 0.95) + (rms * 0.05);

                        if (recognizer.acceptWaveForm(buffer, read)) {
                            if (recognizer.getResult().toLowerCase().contains("alexa")) startCommandRecording();
                        } else {
                            if (recognizer.getPartialResult().toLowerCase().contains("alexa")) startCommandRecording();
                        }
                    } else {
                        try {
                            byte[] bData = new byte[read * 2];
                            for (int i = 0; i < read; i++) {
                                bData[i * 2] = (byte) (buffer[i] & 0x00FF);
                                bData[(i * 2) + 1] = (byte) (buffer[i] >> 8);
                            }
                            pcmOut.write(bData);
                            totalRecordingFrames++;

                            // Dynamic Threshold: Silence is 50% louder than baseline
                            int dynamicThreshold = (int) (baselineRMS * 1.5) + 30;

                            if (rms < dynamicThreshold) {
                                silenceFrames++;
                            } else {
                                silenceFrames = 0; 
                            }

                            // ~1.5 Seconds of silence or 8 Seconds Max
                            if (silenceFrames > 47 || totalRecordingFrames > 250) {
                                stopCommandRecording();
                            }
                        } catch (Exception e) {}
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
            statusText.setText("RECORDING COMMAND");
            statusText.setTextColor(0xFF0000FF);
        });
        
        try {
            File dir = getExternalFilesDir(null);
            currentPcmFile = new File(dir, "temp.pcm");
            pcmOut = new FileOutputStream(currentPcmFile);
            logToScreen("Wake word hit. Recording audio...");
        } catch (Exception e) {}
    }

    private void stopCommandRecording() {
        wakeWordDetected = false;
        try { if (pcmOut != null) pcmOut.close(); } catch (Exception e) {}
        recognizer.reset();
        
        logToScreen("Speech ended. Converting to WAV...");
        
        File dir = getExternalFilesDir(null);
        File wavFile = new File(dir, "cmd_" + System.currentTimeMillis() + ".wav");
        
        try {
            rawToWave(currentPcmFile, wavFile);
            currentPcmFile.delete(); // Clean up temp file
            logToScreen("Saved: " + wavFile.getName());
        } catch (IOException e) {
            logToScreen("WAV Conversion failed.");
        }

        runOnUiThread(() -> {
            statusText.setText("READY");
            statusText.setTextColor(0xFF00FF00);
        });
    }

    // --- WAV CONVERTER UTILS ---
    private void rawToWave(final File rawFile, final File waveFile) throws IOException {
        byte[] rawData = new byte[(int) rawFile.length()];
        DataInputStream input = new DataInputStream(new FileInputStream(rawFile));
        input.readFully(rawData);
        input.close();

        DataOutputStream output = new DataOutputStream(new FileOutputStream(waveFile));
        writeString(output, "RIFF");
        writeInt(output, 36 + rawData.length);
        writeString(output, "WAVE");
        writeString(output, "fmt ");
        writeInt(output, 16);
        writeShort(output, (short) 1);
        writeShort(output, (short) 1);
        writeInt(output, SAMPLE_RATE);
        writeInt(output, SAMPLE_RATE * 2);
        writeShort(output, (short) 2);
        writeShort(output, (short) 16);
        writeString(output, "data");
        writeInt(output, rawData.length);
        output.write(rawData);
        output.close();
    }

    private void writeInt(final DataOutputStream out, final int val) throws IOException {
        out.write(val >> 0); out.write(val >> 8); out.write(val >> 16); out.write(val >> 24);
    }
    private void writeShort(final DataOutputStream out, final short val) throws IOException {
        out.write(val >> 0); out.write(val >> 8);
    }
    private void writeString(final DataOutputStream out, final String val) throws IOException {
        for (int i = 0; i < val.length(); i++) out.write(val.charAt(i));
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
