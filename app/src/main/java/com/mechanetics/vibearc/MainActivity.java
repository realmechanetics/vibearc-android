package com.mechanetics.vibearc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.database.Cursor;
import android.provider.MediaStore;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import org.json.JSONObject;
import org.json.JSONArray;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://vibearc-player.vercel.app/";
    private static final String APP_ORIGIN = "https://vibearc-player.vercel.app";
    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 101;
    private static final int AUDIO_LIBRARY_PERMISSION_REQUEST_CODE = 103;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 104;
    private PermissionRequest currentPermissionRequest;

    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> uploadMessage;
    private SpeechRecognizer speechRecognizer;
    private boolean pendingNativeVoiceRecognition;
    private boolean microphonePermissionPending;
    private boolean suppressSpeechCallbacks;
    private boolean keepWebViewActiveForAudio;
    private boolean mediaControlReceiverRegistered;
    private String pendingNativePlaybackTrack;
    private boolean activityResumed;
    private boolean nativePlaybackServiceActive;
    private Boolean lastReportedActivityVisibility;
    private OfflineSpeechModelManager offlineSpeechModels;
    private volatile Model voskModel;
    private volatile String voskModelId;
    private SpeechService voskSpeechService;
    private Recognizer voskRecognizer;
    private final ExecutorService speechModelLoader = Executors.newSingleThreadExecutor();
    private int speechRequestId;

    private final BroadcastReceiver mediaControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if ("com.mechanetics.vibearc.PLAYBACK_STATE".equals(intent.getAction())) {
                String detail = intent.getStringExtra("detail");
                if (detail != null) {
                    try {
                        JSONObject state = new JSONObject(detail);
                        keepWebViewActiveForAudio = state.optBoolean("playing", false);
                        nativePlaybackServiceActive = state.optBoolean("active", false);
                        if (!nativePlaybackServiceActive) lastReportedActivityVisibility = null;
                    } catch (Exception ignored) {
                        keepWebViewActiveForAudio = false;
                        nativePlaybackServiceActive = false;
                        lastReportedActivityVisibility = null;
                    }
                    dispatchAndroidEvent("vibearc:android-audio-playback", detail);
                    reportNativePlaybackActivityVisibility();
                }
                return;
            }
            String control = intent.getStringExtra("control");
            if (control != null) dispatchAndroidEvent(
                    "vibearc:android-media-control",
                    JSONObject.quote(control)
            );
        }
    };


    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.vibearc_webview);
        offlineSpeechModels = new OfflineSpeechModelManager(
                this,
                detail -> dispatchAndroidEvent("vibearc:offline-speech-models", detail)
        );
        IntentFilter mediaFilter = new IntentFilter(MediaPlaybackService.ACTION_CONTROL);
        mediaFilter.addAction("com.mechanetics.vibearc.PLAYBACK_STATE");
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                mediaControlReceiver,
                mediaFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        );
        mediaControlReceiverRegistered = true;

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);

        // BREAK AUTOPLAY & SPEAKER SANITY BLOCKS: Unlock native text-to-speech speaker synthesis completely
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new VibeArcJavascriptInterface(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                String path = uri.getPath();
                boolean isAppShellResource =
                        APP_ORIGIN.equals(uri.getScheme() + "://" + uri.getAuthority()) &&
                                ("/".equals(path) ||
                                        (path != null && path.startsWith("/assets/") &&
                                                (path.endsWith(".js") || path.endsWith(".css"))));

                if (isAppShellResource) {
                    try {
                        String localFileName = url.replaceAll("[^a-zA-Z0-9]", "_") +
                                (url.contains(".js") ? ".js" : url.contains(".css") ? ".css" : ".html");
                        File localFile = new File(getFilesDir(), localFileName);

                        // If online, silently write a permanent UI snapshot to internal device disk
                        if (isNetworkAvailable()) {
                            new Thread(() -> {
                                try {
                                    URLConnection connection = new URL(url).openConnection();
                                    InputStream in = connection.getInputStream();
                                    FileOutputStream out = new FileOutputStream(localFile);
                                    byte[] buffer = new byte[4096];
                                    int read;
                                    while ((read = in.read(buffer)) != -1) {
                                        out.write(buffer, 0, read);
                                    }
                                    out.close();
                                    in.close();
                                } catch (Exception ignored) {}
                            }).start();
                        }

                        // If offline, instantly load the user interface directly out of absolute memory!
                        if (!isNetworkAvailable() && localFile.exists()) {
                            String mimeType = url.contains(".js") ? "application/javascript" : url.contains(".css") ? "text/css" : "text/html";
                            return new WebResourceResponse(mimeType, "UTF-8", new FileInputStream(localFile));
                        }
                    } catch (Exception ignored) {}
                }
                return super.shouldInterceptRequest(view, request);
            }


            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host != null && (host.equals("accounts.google.com") ||
                        host.equals("google.com") || host.endsWith(".google.com") ||
                        host.equals("spotify.com") || host.endsWith(".spotify.com"))) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });

        // OVERRIDE HARDWARE SOUND CAPTURE: Process microphone hooks natively
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> requestWebViewAudioPermission(request));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {

                // Save the callback bridge instantly so onActivityResult can access it!
                uploadMessage = filePathCallback;

                // Tells the native container to handle the document storage window intents safely
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, 102);
                } catch (Exception e) {
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl(APP_URL);
        handleOAuthReturnIntent(getIntent());
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        reportNativePlaybackActivityVisibility();
        if (!microphonePermissionPending) stopNativeVoiceRecognition(false);
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.dispatchEvent(new Event('vibearc:pause-embedded-media'))",
                    null
            );
            if (!keepWebViewActiveForAudio) webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (webView != null) {
            webView.onResume();
        }
        reportNativePlaybackActivityVisibility();
    }

    @Override
    protected void onDestroy() {
        stopNativeVoiceRecognition(false);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (offlineSpeechModels != null) offlineSpeechModels.close();
        speechModelLoader.shutdownNow();
        if (voskModel != null) {
            voskModel.close();
            voskModel = null;
            voskModelId = null;
        }
        if (mediaControlReceiverRegistered) {
            unregisterReceiver(mediaControlReceiver);
            mediaControlReceiverRegistered = false;
        }
        super.onDestroy();
    }

    public class VibeArcJavascriptInterface {
        @android.webkit.JavascriptInterface
        public void launchSecureExternalLogin(String authUrl) {
            if (authUrl == null || authUrl.length() > 2048) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "The Google sign-in link was not valid.",
                        Toast.LENGTH_LONG
                ).show());
                return;
            }
            Uri uri = Uri.parse(authUrl);
            if (!"https".equals(uri.getScheme()) ||
                    !"vibearc-player.vercel.app".equals(uri.getHost()) ||
                    !"/api/auth/google/start".equals(uri.getPath()) ||
                    !"android".equals(uri.getQueryParameter("platform"))) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "The Google sign-in link was not valid.",
                        Toast.LENGTH_LONG
                ).show());
                return;
            }
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toast.makeText(
                            MainActivity.this,
                            "No external browser is available for sign-in.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void startVoiceRecognition() {
            runOnUiThread(() -> startNativeVoiceRecognition());
        }

        @android.webkit.JavascriptInterface
        public void stopVoiceRecognition() {
            runOnUiThread(() -> stopNativeVoiceRecognition(true));
        }

        @android.webkit.JavascriptInterface
        public String getOfflineSpeechModels() {
            return offlineSpeechModels == null ? "{\"models\":[]}" : offlineSpeechModels.getStateJson();
        }

        @android.webkit.JavascriptInterface
        public void downloadOfflineSpeechModel(String modelId) {
            if (offlineSpeechModels != null) offlineSpeechModels.download(modelId);
        }

        @android.webkit.JavascriptInterface
        public boolean selectOfflineSpeechModel(String modelId) {
            return offlineSpeechModels != null && offlineSpeechModels.select(modelId);
        }

        @android.webkit.JavascriptInterface
        public void removeOfflineSpeechModel(String modelId) {
            runOnUiThread(() -> {
                if (offlineSpeechModels == null) return;
                if (modelId != null && modelId.equals(voskModelId)) {
                    stopNativeVoiceRecognition(false);
                    releaseVoskModel();
                }
                if (!offlineSpeechModels.remove(modelId)) {
                    offlineSpeechModels.reportError(
                            modelId,
                            "Could not remove that speech language pack."
                    );
                }
            });
        }

        @android.webkit.JavascriptInterface
        public boolean isInternetAvailable() {
            return MainActivity.this.isNetworkAvailable();
        }

        @android.webkit.JavascriptInterface
        public void scanDeviceAudio() {
            if (!hasAudioLibraryPermission()) {
                runOnUiThread(() -> requestAudioLibraryPermission());
                return;
            }
            scanDeviceAudioFiles();
        }

        @android.webkit.JavascriptInterface
        public void playDeviceAudio(String trackJson) {
            if (trackJson == null || trackJson.length() > 8192) return;
            try {
                JSONObject track = new JSONObject(trackJson);
                Uri uri = Uri.parse(track.optString("uri", ""));
                if (!"content".equals(uri.getScheme()) ||
                        !"media".equals(uri.getAuthority()) ||
                        uri.getPath() == null ||
                        !uri.getPath().startsWith("/external/audio/media/")) {
                    dispatchAndroidEvent(
                            "vibearc:android-audio-playback",
                            "{\"playing\":false,\"position\":0,\"duration\":0,\"error\":\"The selected device audio file is invalid.\"}"
                    );
                    return;
                }
                runOnUiThread(() -> requestNotificationPermissionOrPlay(trackJson));
            } catch (Exception e) {
                dispatchAndroidEvent(
                        "vibearc:android-audio-playback",
                        "{\"playing\":false,\"position\":0,\"duration\":0,\"error\":\"The selected device audio file could not be opened.\"}"
                );
            }
        }

        @android.webkit.JavascriptInterface
        public void controlDeviceAudio(String action, double positionSeconds) {
            if (action == null || !(
                    "play".equals(action) || "pause".equals(action) ||
                            "stop".equals(action) || "next".equals(action) ||
                            "previous".equals(action) || "seek-to".equals(action) ||
                            "seek-forward".equals(action) || "seek-backward".equals(action)
            )) return;
            Intent service = new Intent(MainActivity.this, MediaPlaybackService.class)
                    .putExtra("control", action)
                    .putExtra(
                            "position",
                            (long) (Math.max(0, Math.min(86400, positionSeconds)) * 1000)
                    );
            startService(service);
        }

        @android.webkit.JavascriptInterface
        public void updateDeviceAudioFilters(String filters) {
            if (filters == null || filters.length() > 2048) return;
            try {
                new JSONObject(filters);
                Intent service = new Intent(MainActivity.this, MediaPlaybackService.class)
                        .setAction(MediaPlaybackService.ACTION_FILTERS)
                        .putExtra("filters", filters);
                startService(service);
            } catch (Exception e) {
                dispatchAndroidEvent(
                        "vibearc:android-audio-playback",
                        "{\"playing\":false,\"position\":0,\"duration\":0,\"error\":\"Audio filter settings were invalid.\"}"
                );
            }
        }

        @android.webkit.JavascriptInterface
        public void setDeviceAudioVolume(float volume) {
            startService(new Intent(MainActivity.this, MediaPlaybackService.class)
                    .setAction(MediaPlaybackService.ACTION_VOLUME)
                    .putExtra("value", Math.max(0f, Math.min(1f, volume))));
        }

        @android.webkit.JavascriptInterface
        public void setDeviceAudioSpeed(float speed) {
            startService(new Intent(MainActivity.this, MediaPlaybackService.class)
                    .setAction(MediaPlaybackService.ACTION_SPEED)
                    .putExtra("value", Math.max(0.5f, Math.min(2f, speed))));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOAuthReturnIntent(intent);
    }

    private void handleOAuthReturnIntent(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null ||
                !"https".equals(uri.getScheme()) ||
                !"vibearc-player.vercel.app".equals(uri.getHost()) ||
                !"/auth/return".equals(uri.getPath())) {
            return;
        }
        String fragment = uri.getFragment();
        Uri fragmentUri = fragment == null ? null : Uri.parse("https://local.invalid/?" + fragment);
        String authResult = fragmentUri == null ? null : fragmentUri.getQueryParameter("auth");
        if ("cancelled".equals(authResult) || "error".equals(authResult)) {
            setIntent(new Intent(this, MainActivity.class));
            Toast.makeText(
                    this,
                    "cancelled".equals(authResult)
                            ? "Google sign-in was cancelled."
                            : "Google sign-in could not be completed.",
                    Toast.LENGTH_LONG
            ).show();
            webView.loadUrl(APP_URL);
            return;
        }
        String handoff = fragmentUri == null ? null : fragmentUri.getQueryParameter("handoff");
        if (handoff == null || handoff.length() > 4096) {
            Toast.makeText(this, "The Google sign-in return link is invalid.", Toast.LENGTH_LONG).show();
            return;
        }

        setIntent(new Intent(this, MainActivity.class));
        new Thread(() -> redeemGoogleHandoff(handoff)).start();
    }

    private void redeemGoogleHandoff(String handoff) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(APP_ORIGIN + "/api/auth/google/redeem");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-VibeArc-Handoff", "android");
            byte[] payload = new JSONObject().put("handoff", handoff).toString().getBytes("UTF-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("The sign-in handoff expired or was rejected.");
            }
            String responseText;
            try (InputStream input = connection.getInputStream();
                 java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                byte[] data = new byte[2048];
                int count;
                while ((count = input.read(data)) != -1) {
                    buffer.write(data, 0, count);
                }
                responseText = buffer.toString("UTF-8");
            }
            String sessionToken = new JSONObject(responseText).getString("sessionToken");
            String cookie = "vibearc_session=" + sessionToken +
                    "; Path=/; Max-Age=2592000; Secure; HttpOnly; SameSite=Lax";
            android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setCookie(APP_ORIGIN, cookie, value -> runOnUiThread(() -> {
                if (value) {
                    cookieManager.flush();
                    webView.loadUrl(APP_URL);
                    Toast.makeText(MainActivity.this, "Google account connected to VibeArc.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Could not save the VibeArc sign-in.", Toast.LENGTH_LONG).show();
                }
            }));
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(
                    MainActivity.this,
                    "Google sign-in could not be returned to VibeArc. Please try again.",
                    Toast.LENGTH_LONG
            ).show());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicrophonePermission() {
        microphonePermissionPending = true;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                MICROPHONE_PERMISSION_REQUEST_CODE
        );
    }

    private boolean hasAudioLibraryPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioLibraryPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        ActivityCompat.requestPermissions(
                this,
                new String[]{permission},
                AUDIO_LIBRARY_PERMISSION_REQUEST_CODE
        );
    }

    private void scanDeviceAudioFiles() {
        new Thread(() -> {
            JSONArray audioFiles = new JSONArray();
            try {
                Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                ArrayList<String> projection = new ArrayList<>();
                projection.add(MediaStore.Audio.Media._ID);
                projection.add(MediaStore.Audio.Media.TITLE);
                projection.add(MediaStore.Audio.Media.ARTIST);
                projection.add(MediaStore.Audio.Media.ALBUM);
                projection.add(MediaStore.Audio.Media.DURATION);
                projection.add(MediaStore.Audio.Media.IS_MUSIC);
                projection.add(MediaStore.Audio.Media.DATA);
                if (Build.VERSION.SDK_INT >= 29) {
                    projection.add(MediaStore.Audio.Media.RELATIVE_PATH);
                }
                String[] columns = projection.toArray(new String[0]);
                try (Cursor cursor = getContentResolver().query(
                        collection,
                        columns,
                        MediaStore.Audio.Media.IS_MUSIC + " != 0",
                        null,
                        MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
                )) {
                    if (cursor != null) {
                        int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                        int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                        int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                        int folderColumn = Build.VERSION.SDK_INT >= 29
                                ? cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                                : -1;
                        while (cursor.moveToNext()) {
                            long id = cursor.getLong(idColumn);
                            Uri contentUri = ContentUris.withAppendedId(collection, id);
                            String folder = folderColumn >= 0 ? cursor.getString(folderColumn) : null;
                            if ((folder == null || folder.isEmpty()) && dataColumn >= 0) {
                                String path = cursor.getString(dataColumn);
                                if (path != null) folder = new File(path).getParent();
                            }
                            if (folder == null || folder.isEmpty()) folder = "Music";
                            folder = folder.replace('\\', '/').replaceAll("/+$", "");
                            JSONObject item = new JSONObject();
                            item.put("id", "android-media-" + id);
                            item.put("title", cursor.getString(titleColumn));
                            item.put("artist", cursor.getString(artistColumn));
                            item.put("album", cursor.getString(albumColumn));
                            item.put("durationSeconds", cursor.getLong(durationColumn) / 1000.0);
                            item.put("folder", folder);
                            item.put("uri", contentUri.toString());
                            audioFiles.put(item);
                        }
                    }
                }
                JSONObject detail = new JSONObject().put("tracks", audioFiles);
                dispatchAndroidEvent("vibearc:android-audio-library", detail.toString());
            } catch (Exception exception) {
                dispatchAndroidEvent(
                        "vibearc:android-audio-library",
                        "{\"error\":\"VibeArc could not scan the device audio library.\"}"
                );
            }
        }, "vibearc-media-scan").start();
    }

    private void requestWebViewAudioPermission(PermissionRequest request) {
        boolean requestsAudio = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                requestsAudio = true;
                break;
            }
        }
        if (!requestsAudio) {
            request.deny();
            return;
        }
        if (hasMicrophonePermission()) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            return;
        }
        currentPermissionRequest = request;
        requestMicrophonePermission();
    }

    private void startNativeVoiceRecognition() {
        if (!hasMicrophonePermission()) {
            pendingNativeVoiceRecognition = true;
            requestMicrophonePermission();
            return;
        }
        pendingNativeVoiceRecognition = false;
        stopNativeVoiceRecognition(false);
        suppressSpeechCallbacks = false;
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (!isNetworkAvailable()) {
            startOfflineVoiceRecognition();
            return;
        }
        releaseVoskModel();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            dispatchAndroidSpeechEvent("vibearc:android-speech-error", "unavailable");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) { }

            @Override
            public void onBeginningOfSpeech() { }

            @Override
            public void onRmsChanged(float rmsdB) { }

            @Override
            public void onBufferReceived(byte[] buffer) { }

            @Override
            public void onEndOfSpeech() {
                if (!suppressSpeechCallbacks) {
                    dispatchAndroidSpeechEvent("vibearc:android-speech-ended", null);
                }
            }

            @Override
            public void onError(int error) {
                if (!suppressSpeechCallbacks) {
                    if (isNetworkAvailable()) {
                        dispatchAndroidSpeechEvent("vibearc:android-speech-error", "recognition");
                    } else {
                        if (speechRecognizer != null) {
                            speechRecognizer.destroy();
                            speechRecognizer = null;
                        }
                        startNativeVoiceRecognition();
                    }
                }
            }

            @Override
            public void onResults(Bundle results) {
                if (suppressSpeechCallbacks) return;
                ArrayList<String> matches = results == null
                        ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String transcript = matches == null || matches.isEmpty() ? "" : matches.get(0);
                dispatchAndroidSpeechEvent("vibearc:android-speech-result", transcript);
            }

            @Override
            public void onPartialResults(Bundle partialResults) { }

            @Override
            public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            speechRecognizer.startListening(intent);
        } catch (RuntimeException e) {
            if (isNetworkAvailable()) {
                dispatchAndroidSpeechEvent("vibearc:android-speech-error", "unavailable");
            } else {
                startNativeVoiceRecognition();
            }
        }
    }

    private void startOfflineVoiceRecognition() {
        String modelId = offlineSpeechModels == null ? null : offlineSpeechModels.activeModelId();
        if (modelId == null || !offlineSpeechModels.isInstalled(modelId)) {
            dispatchAndroidSpeechEvent("vibearc:android-speech-error", "offline-model-required");
            return;
        }
        int requestId = ++speechRequestId;
        if (voskModel != null && modelId.equals(voskModelId)) {
            startVoskListening(requestId);
            return;
        }
        releaseVoskModel();

        File modelDirectory = offlineSpeechModels.modelDirectory(modelId);
        speechModelLoader.execute(() -> {
            try {
                Model loadedModel = new Model(modelDirectory.getAbsolutePath());
                runOnUiThread(() -> {
                    if (requestId != speechRequestId || isFinishing() || isDestroyed()) {
                        loadedModel.close();
                        return;
                    }
                    voskModel = loadedModel;
                    voskModelId = modelId;
                    startVoskListening(requestId);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (requestId == speechRequestId) {
                        dispatchAndroidSpeechEvent("vibearc:android-speech-error", "offline-model-failed");
                    }
                });
            }
        });
    }

    private void startVoskListening(int requestId) {
        try {
            voskRecognizer = new Recognizer(voskModel, 16000.0f);
            voskSpeechService = new SpeechService(voskRecognizer, 16000.0f);
            voskSpeechService.startListening(new org.vosk.android.RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) { }

                @Override
                public void onResult(String hypothesis) { }

                @Override
                public void onFinalResult(String hypothesis) {
                    String transcript = "";
                    try {
                        transcript = new JSONObject(hypothesis).optString("text", "").trim();
                    } catch (Exception ignored) {
                        transcript = "";
                    }
                    String finalTranscript = transcript;
                    runOnUiThread(() -> {
                        if (requestId != speechRequestId || suppressSpeechCallbacks) return;
                        stopNativeVoiceRecognition(false);
                        if (finalTranscript.isEmpty()) {
                            dispatchAndroidSpeechEvent("vibearc:android-speech-error", "no-speech");
                        } else {
                            dispatchAndroidSpeechEvent("vibearc:android-speech-result", finalTranscript);
                        }
                    });
                }

                @Override
                public void onError(Exception exception) {
                    runOnUiThread(() -> {
                        if (requestId != speechRequestId || suppressSpeechCallbacks) return;
                        stopNativeVoiceRecognition(false);
                        dispatchAndroidSpeechEvent("vibearc:android-speech-error", "recognition");
                    });
                }

                @Override
                public void onTimeout() {
                    runOnUiThread(() -> {
                        if (requestId != speechRequestId || suppressSpeechCallbacks) return;
                        stopNativeVoiceRecognition(false);
                        dispatchAndroidSpeechEvent("vibearc:android-speech-ended", null);
                    });
                }
            });
        } catch (Exception exception) {
            stopNativeVoiceRecognition(false);
            dispatchAndroidSpeechEvent("vibearc:android-speech-error", "offline-model-failed");
        }
    }

    private void releaseVoskModel() {
        if (voskModel != null) {
            voskModel.close();
            voskModel = null;
            voskModelId = null;
        }
    }

    private void stopNativeVoiceRecognition(boolean notifyWeb) {
        speechRequestId++;
        pendingNativeVoiceRecognition = false;
        suppressSpeechCallbacks = true;
        if (speechRecognizer != null) speechRecognizer.cancel();
        if (voskSpeechService != null) {
            voskSpeechService.stop();
            voskSpeechService.shutdown();
            voskSpeechService = null;
        }
        if (voskRecognizer != null) {
            voskRecognizer.close();
            voskRecognizer = null;
        }
        if (notifyWeb) {
            dispatchAndroidSpeechEvent("vibearc:android-speech-ended", null);
        }
    }

    private void dispatchAndroidSpeechEvent(String eventName, String value) {
        String detailKey = eventName.endsWith("-result") ? "transcript" : "error";
        String detail = value == null
                ? "{}"
                : "{\"" + detailKey + "\":" + JSONObject.quote(value) + "}";
        dispatchAndroidEvent(eventName, detail);
    }

    private void dispatchAndroidEvent(String eventName, String detail) {
        if (webView == null) return;
        String script = "window.dispatchEvent(new CustomEvent("
                + JSONObject.quote(eventName)
                + ",{detail:"
                + detail
                + "}))";
        new Handler(Looper.getMainLooper()).post(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    private void reportNativePlaybackActivityVisibility() {
        if (!nativePlaybackServiceActive ||
                (lastReportedActivityVisibility != null &&
                        lastReportedActivityVisibility == activityResumed)) {
            return;
        }
        lastReportedActivityVisibility = activityResumed;
        startService(new Intent(this, MediaPlaybackService.class)
                .setAction(MediaPlaybackService.ACTION_ACTIVITY_VISIBILITY)
                .putExtra("foreground", activityResumed));
    }

    private void grantWebViewAudioAccess() {
        if (currentPermissionRequest != null) {
            String[] audioResources = {PermissionRequest.RESOURCE_AUDIO_CAPTURE};
            currentPermissionRequest.grant(audioResources);
            currentPermissionRequest = null;
        }
    }

    private void requestNotificationPermissionOrPlay(String trackJson) {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            pendingNativePlaybackTrack = trackJson;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    NOTIFICATION_PERMISSION_REQUEST_CODE
            );
            return;
        }
        startNativeDeviceAudio(trackJson);
    }

    private void startNativeDeviceAudio(String trackJson) {
        Intent service = new Intent(this, MediaPlaybackService.class)
                .setAction(MediaPlaybackService.ACTION_PLAY)
                .putExtra("track", trackJson);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            String trackJson = pendingNativePlaybackTrack;
            pendingNativePlaybackTrack = null;
            if (trackJson != null) {
                if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                                != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                            this,
                            "Playback will continue, but lock-screen controls may be unavailable without notifications.",
                            Toast.LENGTH_LONG
                    ).show();
                }
                startNativeDeviceAudio(trackJson);
            }
        }
        if (requestCode == AUDIO_LIBRARY_PERMISSION_REQUEST_CODE) {
            boolean permissionGranted = hasAudioLibraryPermission();
            if (permissionGranted) {
                scanDeviceAudioFiles();
            } else {
                dispatchAndroidEvent(
                        "vibearc:android-audio-library",
                        "{\"error\":\"Allow audio access to scan music on this device.\"}"
                );
            }
        }
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            microphonePermissionPending = false;
            boolean audioApproved = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    audioApproved = true;
                    break;
                }
            }
            if (audioApproved && currentPermissionRequest != null) {
                grantWebViewAudioAccess();
            } else if (!audioApproved && currentPermissionRequest != null) {
                currentPermissionRequest.deny();
                currentPermissionRequest = null;
            }
            if (pendingNativeVoiceRecognition) {
                if (audioApproved) {
                    startNativeVoiceRecognition();
                } else {
                    pendingNativeVoiceRecognition = false;
                    dispatchAndroidSpeechEvent("vibearc:android-speech-error", "permission");
                }
            }
        }
    }

    // THE FILES BRIDGE: Receives the selected songs from Android storage and hands them to React
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Match the 102 request code we set inside our file chooser client loop
        if (requestCode == 102) {
            if (uploadMessage == null) return;

            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    // Handles multiple audio files selected at once
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    // Handles a single audio file selection
                    result = new Uri[]{data.getData()};
                }
            }

            // Hand the file tokens directly to Chrome's WebView receiver loop
            uploadMessage.onReceiveValue(result);
            uploadMessage = null;
        }
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : cm.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }



}
