package com.mechanetics.vibearc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognitionListener;
import android.speech.ModelDownloadListener;
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
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONArray;


public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://vibearc-player.vercel.app/";
    private static final String APP_ORIGIN = "https://vibearc-player.vercel.app";
    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 101;
    private static final int AUDIO_LIBRARY_PERMISSION_REQUEST_CODE = 103;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 104;
    private static final String SPEECH_PREFERENCES = "vibearc_speech";
    private static final String SPEECH_LOCALE = "locale";
    private static final String DEFAULT_SPEECH_LOCALE = "en-IN";
    private PermissionRequest currentPermissionRequest;

    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> uploadMessage;
    private SpeechRecognizer speechRecognizer;
    private SpeechRecognizer speechModelDownloader;
    private boolean pendingNativeVoiceRecognition;
    private boolean microphonePermissionPending;
    private boolean suppressSpeechCallbacks;
    private boolean offlineRecognitionRequest;
    private boolean keepWebViewActiveForAudio;
    private boolean mediaControlReceiverRegistered;
    private String pendingNativePlaybackTrack;
    private boolean activityResumed;
    private boolean nativePlaybackServiceActive;
    private Boolean lastReportedActivityVisibility;
    private volatile String activeSpeechLocale = DEFAULT_SPEECH_LOCALE;

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
        activeSpeechLocale = getSharedPreferences(SPEECH_PREFERENCES, MODE_PRIVATE)
                .getString(SPEECH_LOCALE, DEFAULT_SPEECH_LOCALE);
        removeLegacyVoskModels();
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
        releaseSpeechModelDownloader();
        if (mediaControlReceiverRegistered) {
            unregisterReceiver(mediaControlReceiver);
            mediaControlReceiverRegistered = false;
        }
        super.onDestroy();
    }

    public class VibeArcJavascriptInterface {
        @android.webkit.JavascriptInterface
        public void startVoiceRecognition() {
            runOnUiThread(() -> startNativeVoiceRecognition());
        }

        @android.webkit.JavascriptInterface
        public void stopVoiceRecognition() {
            runOnUiThread(() -> stopNativeVoiceRecognition(true));
        }

        @android.webkit.JavascriptInterface
        public String getSpeechLocale() {
            return activeSpeechLocale;
        }

        @android.webkit.JavascriptInterface
        public boolean setSpeechLocale(String locale) {
            if (!isSupportedSpeechLocale(locale)) return false;
            activeSpeechLocale = locale;
            getSharedPreferences(SPEECH_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(SPEECH_LOCALE, locale)
                    .apply();
            return true;
        }

        @android.webkit.JavascriptInterface
        public void checkOnDeviceSpeechModel(String locale) {
            runOnUiThread(() -> checkOnDeviceSpeechModelOnMainThread(locale));
        }

        @android.webkit.JavascriptInterface
        public void downloadOnDeviceSpeechModel(String locale) {
            runOnUiThread(() -> requestOnDeviceSpeechModelDownload(locale));
        }

        @android.webkit.JavascriptInterface
        public void openSpeechSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
                } catch (Exception exception) {
                    Toast.makeText(MainActivity.this, "Open your Android speech input settings to manage voice services.", Toast.LENGTH_LONG).show();
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
        offlineRecognitionRequest = !isNetworkAvailable();
        boolean recognizerAvailable = offlineRecognitionRequest
                ? Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                : SpeechRecognizer.isRecognitionAvailable(this);
        if (!recognizerAvailable) {
            dispatchAndroidSpeechEvent(
                    "vibearc:android-speech-error",
                    offlineRecognitionRequest ? "ondevice-unavailable" : "unavailable"
            );
            return;
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        suppressSpeechCallbacks = false;
        try {
            speechRecognizer = offlineRecognitionRequest
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    : SpeechRecognizer.createSpeechRecognizer(this);
        } catch (RuntimeException exception) {
            dispatchAndroidSpeechEvent(
                    "vibearc:android-speech-error",
                    offlineRecognitionRequest ? "ondevice-unavailable" : "unavailable"
            );
            return;
        }
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
                    String reason = "recognition";
                    if (offlineRecognitionRequest
                            && error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        reason = "offline-model-required";
                    } else if (offlineRecognitionRequest
                            && error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                        reason = "offline-language-unsupported";
                    }
                    dispatchAndroidSpeechEvent("vibearc:android-speech-error", reason);
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
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeSpeechLocale);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            speechRecognizer.startListening(intent);
        } catch (RuntimeException e) {
            dispatchAndroidSpeechEvent("vibearc:android-speech-error", "unavailable");
        }
    }

    private boolean isSupportedSpeechLocale(String locale) {
        return locale != null
                && locale.length() <= 35
                && locale.matches("(?i)[a-z]{2,3}(-[a-z0-9]{2,8})*");
    }

    private void removeLegacyVoskModels() {
        File legacyModels = new File(getNoBackupFilesDir(), "offline-speech");
        if (legacyModels.exists() && !deleteLegacySpeechFiles(legacyModels)) {
            android.util.Log.e("VibeArc", "Could not remove the old bundled speech model files.");
        }
    }

    private boolean deleteLegacySpeechFiles(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteLegacySpeechFiles(child)) return false;
            }
        }
        return file.delete();
    }

    private Intent buildSpeechRecognitionIntent(String locale) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    }

    private void checkOnDeviceSpeechModelOnMainThread(String locale) {
        if (!isSupportedSpeechLocale(locale)) {
            dispatchSpeechModelState(locale, false, false, false, "unsupported-locale", null);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            dispatchSpeechModelState(locale, false, false, false, "unavailable", null);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            dispatchSpeechModelState(locale, true, false, false, "system-settings-required", null);
            return;
        }

        SpeechRecognizer checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        try {
            checker.checkRecognitionSupport(
                    buildSpeechRecognitionIntent(locale),
                    ContextCompat.getMainExecutor(this),
                    new RecognitionSupportCallback() {
                        @Override
                        public void onSupportResult(RecognitionSupport support) {
                            boolean supported = containsLocale(support.getSupportedOnDeviceLanguages(), locale)
                                    || containsLocale(support.getInstalledOnDeviceLanguages(), locale);
                            boolean installed = containsLocale(support.getInstalledOnDeviceLanguages(), locale);
                            boolean pending = containsLocale(support.getPendingOnDeviceLanguages(), locale);
                            dispatchSpeechModelState(
                                    locale,
                                    true,
                                    supported,
                                    installed,
                                    installed ? "installed"
                                            : pending ? "download-pending"
                                            : supported ? "download-required" : "unsupported",
                                    support
                            );
                            if (installed) releaseSpeechModelDownloader();
                            checker.destroy();
                        }

                        @Override
                        public void onError(int error) {
                            android.util.Log.e("VibeArc", "Android speech support check failed: " + error);
                            dispatchSpeechModelState(locale, true, false, false, "check-failed", null);
                            checker.destroy();
                        }
                    }
            );
        } catch (RuntimeException exception) {
            checker.destroy();
            dispatchSpeechModelState(locale, true, false, false, "check-failed", null);
        }
    }

    private boolean containsLocale(List<String> locales, String languageTag) {
        if (locales == null) return false;
        for (String availableLocale : locales) {
            if (availableLocale.equalsIgnoreCase(languageTag)) return true;
        }
        return false;
    }

    private void dispatchSpeechModelState(
            String locale,
            boolean onDeviceAvailable,
            boolean supported,
            boolean installed,
            String status,
            RecognitionSupport support
    ) {
        JSONObject state = new JSONObject();
        try {
            state.put("locale", locale == null ? JSONObject.NULL : locale);
            state.put("onDeviceAvailable", onDeviceAvailable);
            state.put("supported", supported);
            state.put("installed", installed);
            state.put("status", status);
            JSONArray installedLanguages = new JSONArray();
            JSONArray supportedLanguages = new JSONArray();
            if (support != null && support.getInstalledOnDeviceLanguages() != null) {
                for (String installedLocale : support.getInstalledOnDeviceLanguages()) {
                    installedLanguages.put(installedLocale);
                }
            }
            if (support != null && support.getSupportedOnDeviceLanguages() != null) {
                for (String supportedLocale : support.getSupportedOnDeviceLanguages()) {
                    supportedLanguages.put(supportedLocale);
                }
            }
            state.put("installedLanguages", installedLanguages);
            state.put("supportedLanguages", supportedLanguages);
        } catch (Exception exception) {
            android.util.Log.e("VibeArc", "Could not serialize speech model status.", exception);
        }
        dispatchAndroidEvent("vibearc:android-speech-model-state", state.toString());
    }

    private void requestOnDeviceSpeechModelDownload(String locale) {
        if (!isSupportedSpeechLocale(locale)) {
            dispatchSpeechModelState(locale, false, false, false, "unsupported-locale", null);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            dispatchSpeechModelState(locale, false, false, false, "download-unavailable", null);
            return;
        }

        releaseSpeechModelDownloader();
        speechModelDownloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                triggerSpeechModelDownloadWithProgress(locale);
            } else {
                speechModelDownloader.triggerModelDownload(buildSpeechRecognitionIntent(locale));
                dispatchSpeechModelDownloadState(locale, "download-requested", -1);
            }
        } catch (RuntimeException exception) {
            android.util.Log.e("VibeArc", "Could not request Android speech model download.", exception);
            dispatchSpeechModelDownloadState(locale, "download-failed", -1);
            releaseSpeechModelDownloader();
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private void triggerSpeechModelDownloadWithProgress(String locale) {
        speechModelDownloader.triggerModelDownload(
                buildSpeechRecognitionIntent(locale),
                ContextCompat.getMainExecutor(this),
                new ModelDownloadListener() {
                    @Override
                    public void onProgress(int completedPercent) {
                        dispatchSpeechModelDownloadState(locale, "download-progress", completedPercent);
                    }

                    @Override
                    public void onScheduled() {
                        dispatchSpeechModelDownloadState(locale, "download-pending", -1);
                    }

                    @Override
                    public void onSuccess() {
                        dispatchSpeechModelDownloadState(locale, "download-complete", 100);
                        releaseSpeechModelDownloader();
                        checkOnDeviceSpeechModelOnMainThread(locale);
                    }

                    @Override
                    public void onError(int error) {
                        android.util.Log.e("VibeArc", "Android speech model download failed: " + error);
                        dispatchSpeechModelDownloadState(locale, "download-failed", -1);
                        releaseSpeechModelDownloader();
                    }
                }
        );
        dispatchSpeechModelDownloadState(locale, "download-requested", -1);
    }

    private void dispatchSpeechModelDownloadState(String locale, String status, int progress) {
        JSONObject state = new JSONObject();
        try {
            state.put("locale", locale);
            state.put("onDeviceAvailable", true);
            state.put("supported", true);
            state.put("installed", false);
            state.put("status", status);
            if (progress >= 0) state.put("progress", progress);
        } catch (Exception exception) {
            android.util.Log.e("VibeArc", "Could not serialize speech download status.", exception);
        }
        dispatchAndroidEvent("vibearc:android-speech-model-state", state.toString());
    }

    private void releaseSpeechModelDownloader() {
        if (speechModelDownloader != null) {
            speechModelDownloader.destroy();
            speechModelDownloader = null;
        }
    }

    private void stopNativeVoiceRecognition(boolean notifyWeb) {
        pendingNativeVoiceRecognition = false;
        suppressSpeechCallbacks = true;
        if (speechRecognizer != null) speechRecognizer.cancel();
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
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity == null) return false;
        Network activeNetwork = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(activeNetwork);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }



}
