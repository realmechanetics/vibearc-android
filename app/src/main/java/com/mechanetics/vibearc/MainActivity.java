package com.mechanetics.vibearc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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


public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://vibearc-player.vercel.app/";
    private static final String APP_ORIGIN = "https://vibearc-player.vercel.app";
    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 101;
    private PermissionRequest currentPermissionRequest;

    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> uploadMessage;
    private SpeechRecognizer speechRecognizer;
    private boolean pendingNativeVoiceRecognition;
    private boolean microphonePermissionPending;
    private boolean suppressSpeechCallbacks;


    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.vibearc_webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

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
        if (!microphonePermissionPending) stopNativeVoiceRecognition(false);
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.dispatchEvent(new Event('vibearc:pause-embedded-media'))",
                    null
            );
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        stopNativeVoiceRecognition(false);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            dispatchAndroidSpeechEvent("vibearc:android-speech-error", "unavailable");
            return;
        }

        if (speechRecognizer != null) speechRecognizer.destroy();
        suppressSpeechCallbacks = false;
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
                    dispatchAndroidSpeechEvent("vibearc:android-speech-error", "recognition");
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
            dispatchAndroidSpeechEvent("vibearc:android-speech-error", "unavailable");
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
        if (webView == null) return;
        String detailKey = eventName.endsWith("-result") ? "transcript" : "error";
        String detail = value == null
                ? "{}"
                : "{\"" + detailKey + "\":" + JSONObject.quote(value) + "}";
        String script = "window.dispatchEvent(new CustomEvent("
                + JSONObject.quote(eventName)
                + ",{detail:"
                + detail
                + "}))";
        new Handler(Looper.getMainLooper()).post(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    private void grantWebViewAudioAccess() {
        if (currentPermissionRequest != null) {
            String[] audioResources = {PermissionRequest.RESOURCE_AUDIO_CAPTURE};
            currentPermissionRequest.grant(audioResources);
            currentPermissionRequest = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }



}
