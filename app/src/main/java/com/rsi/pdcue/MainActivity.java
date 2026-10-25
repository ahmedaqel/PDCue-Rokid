package com.rsi.pdcue;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 4101;
    private static final int REQ_MIC = 4102;

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraSession;
    private ImageReader imageReader;

    private MediaRecorder recorder;
    private String lastAudioPath;

    private SensorManager sensorManager;
    private SensorEventListener imuListener;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new NativeBridge(), "PDCueNative");
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void jsResult(String type, boolean ok, String message) {
        final String js = "window.nativeDiagnosticResult && nativeDiagnosticResult("
                + JSONObject.quote(type) + "," + ok + "," + JSONObject.quote(message == null ? "" : message) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            jsResult("key", true, "KEYCODE " + event.getKeyCode() + " · " + KeyEvent.keyCodeToString(event.getKeyCode()));
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        stopRecorder(false);
        stopImu();
        super.onDestroy();
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("PDCueCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCamera() {
        try { if (cameraSession != null) cameraSession.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        cameraSession = null;
        cameraDevice = null;
        imageReader = null;

        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private Size chooseJpegSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);

        Size best = sizes[0];
        long bestArea = 0;

        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area <= 2_500_000L && area > bestArea) {
                best = s;
                bestArea = area;
            }
        }

        if (bestArea == 0) {
            for (Size s : sizes) {
                if ((long)s.getWidth() * s.getHeight() < (long)best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        return best;
    }

    private void startCameraTest() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            jsResult("camera", false, "Camera permission requested. Allow it, then press CAPTURE again.");
            return;
        }

        stopCamera();
        startCameraThread();

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();

            if (ids.length == 0) {
                jsResult("camera", false, "No camera exposed to Android.");
                return;
            }

            String selected = ids[0];

            for (String id : ids) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing != CameraCharacteristics.LENS_FACING_FRONT) {
                    selected = id;
                    break;
                }
            }

            CameraCharacteristics cc = manager.getCameraCharacteristics(selected);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseJpegSize(map == null ? null : map.getOutputSizes(ImageFormat.JPEG));

            imageReader = ImageReader.newInstance(
                    size.getWidth(),
                    size.getHeight(),
                    ImageFormat.JPEG,
                    1
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;

                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    if (dir == null) dir = getFilesDir();

                    File out = new File(dir, "PDCue_CAM_" + stamp() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(bytes);
                    }

                    jsResult(
                            "camera",
                            true,
                            "Captured " + size.getWidth() + "×" + size.getHeight()
                                    + " · " + out.getAbsolutePath()
                    );
                } catch (Exception e) {
                    jsResult("camera", false, "Capture save failed: " + e.getMessage());
                } finally {
                    if (image != null) image.close();
                    mainHandler.postDelayed(this::stopCamera, 300);
                }
            }, cameraHandler);

            final String cameraId = selected;

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;

                    try {
                        CaptureRequest.Builder builder =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

                        builder.addTarget(imageReader.getSurface());
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

                        camera.createCaptureSession(
                                Arrays.asList(imageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        cameraSession = session;
                                        try {
                                            session.capture(
                                                    builder.build(),
                                                    new CameraCaptureSession.CaptureCallback() {
                                                        @Override
                                                        public void onCaptureCompleted(
                                                                CameraCaptureSession s,
                                                                CaptureRequest r,
                                                                TotalCaptureResult result
                                                        ) {
                                                            jsResult(
                                                                    "camera",
                                                                    true,
                                                                    "Camera capture completed; saving image…"
                                                            );
                                                        }
                                                    },
                                                    cameraHandler
                                            );
                                        } catch (CameraAccessException e) {
                                            jsResult("camera", false, "Capture failed: " + e.getMessage());
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        jsResult("camera", false, "Camera session configuration failed.");
                                        stopCamera();
                                    }
                                },
                                cameraHandler
                        );
                    } catch (CameraAccessException e) {
                        jsResult("camera", false, "Camera request failed: " + e.getMessage());
                        stopCamera();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    jsResult("camera", false, "Camera disconnected.");
                    stopCamera();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    jsResult("camera", false, "Camera error code " + error);
                    stopCamera();
                }
            }, cameraHandler);

            jsResult(
                    "camera",
                    true,
                    "Opening camera " + cameraId + " at "
                            + size.getWidth() + "×" + size.getHeight() + "…"
            );
        } catch (Exception e) {
            jsResult(
                    "camera",
                    false,
                    "Camera unavailable: " + e.getClass().getSimpleName() + " · " + e.getMessage()
            );
            stopCamera();
        }
    }

    private void startMicTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            jsResult("mic", false, "Microphone permission requested. Allow it, then press RECORD again.");
            return;
        }

        stopRecorder(false);

        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (dir == null) dir = getFilesDir();

            File out = new File(dir, "PDCue_MIC_" + stamp() + ".m4a");
            lastAudioPath = out.getAbsolutePath();

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(lastAudioPath);
            recorder.prepare();
            recorder.start();

            jsResult("mic", true, "Recording microphone for 5 seconds…");
            mainHandler.postDelayed(() -> stopRecorder(true), 5000);
        } catch (Exception e) {
            jsResult(
                    "mic",
                    false,
                    "Microphone start failed: " + e.getClass().getSimpleName() + " · " + e.getMessage()
            );
            stopRecorder(false);
        }
    }

    private void stopRecorder(boolean report) {
        if (recorder == null) return;

        try { recorder.stop(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
        recorder = null;

        if (report) {
            jsResult("mic", true, "Recording saved · " + lastAudioPath);
        }
    }

    private void playLastRecording() {
        if (lastAudioPath == null) {
            jsResult("mic", false, "No recording available yet.");
            return;
        }

        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(lastAudioPath);

            player.setOnCompletionListener(mp -> {
                mp.release();
                jsResult("mic", true, "Playback completed.");
            });

            player.prepare();
            player.start();
            jsResult("mic", true, "Playing last microphone recording…");
        } catch (Exception e) {
            jsResult("mic", false, "Playback failed: " + e.getMessage());
        }
    }

    private void startImuTest() {
        stopImu();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        final Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        final Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accel == null && gyro == null) {
            jsResult("imu", false, "No accelerometer or gyroscope exposed.");
            return;
        }

        final Sensor chosen = gyro != null ? gyro : accel;
        final String sensorName = chosen.getName();

        imuListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                String msg = sensorName
                        + " · x=" + String.format(Locale.US, "%.2f", event.values[0])
                        + " y=" + String.format(Locale.US, "%.2f", event.values[1])
                        + " z=" + String.format(Locale.US, "%.2f", event.values[2])
                        + " · accel=" + (accel != null)
                        + " gyro=" + (gyro != null);

                jsResult("imu", true, msg);
                stopImu();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        sensorManager.registerListener(
                imuListener,
                chosen,
                SensorManager.SENSOR_DELAY_NORMAL
        );

        jsResult("imu", true, "Waiting for IMU reading… move your head slightly.");
    }

    private void stopImu() {
        if (sensorManager != null && imuListener != null) {
            sensorManager.unregisterListener(imuListener);
        }
        imuListener = null;
        sensorManager = null;
    }

    private String networkStatus() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            Network n = cm.getActiveNetwork();
            if (n == null) return "No active network";

            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) return "Network active; capabilities unavailable";

            String transport =
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Cellular"
                    : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                    : "Other";

            boolean internet =
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            boolean validated =
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

            return transport
                    + " · internet=" + internet
                    + " · validated=" + validated;
        } catch (Exception e) {
            return "Network check failed: " + e.getMessage();
        }
    }

    private String storageTest() {
        try {
            File f = new File(getFilesDir(), "pdcue_storage_test.txt");
            String value = "PDCue storage OK " + System.currentTimeMillis();

            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(value.getBytes("UTF-8"));
            }

            return "Write OK · " + f.getAbsolutePath() + " · " + f.length() + " bytes";
        } catch (Exception e) {
            return "Storage failed: " + e.getMessage();
        }
    }

    private String deviceInfo() {
        return Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE
                + " · API " + Build.VERSION.SDK_INT
                + " · device=" + Build.DEVICE;
    }

    public class NativeBridge {
        @JavascriptInterface
        public void cameraTest() {
            runOnUiThread(MainActivity.this::startCameraTest);
        }

        @JavascriptInterface
        public void micTest() {
            runOnUiThread(MainActivity.this::startMicTest);
        }

        @JavascriptInterface
        public void playMic() {
            runOnUiThread(MainActivity.this::playLastRecording);
        }

        @JavascriptInterface
        public void imuTest() {
            runOnUiThread(MainActivity.this::startImuTest);
        }

        @JavascriptInterface
        public String networkStatus() {
            return MainActivity.this.networkStatus();
        }

        @JavascriptInterface
        public String storageTest() {
            return MainActivity.this.storageTest();
        }

        @JavascriptInterface
        public String deviceInfo() {
            return MainActivity.this.deviceInfo();
        }

        @JavascriptInterface
        public String appVersion() {
            return "0.4.0";
        }
    }
}
