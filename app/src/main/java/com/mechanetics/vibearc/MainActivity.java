package com.mechanetics.vibearc;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int HARDWARE_PERMISSIONS_REQUEST_CODE = 101;
    private WebView webView;
    private PermissionRequest currentPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.vibearc_webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // VERCEL ACCENT BYPASS: Mimic an official mobile Google Chrome browser thread
        String chromeAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
        settings.setUserAgentString(chromeAgent);

        // BREAK AUTOPLAY & SPEAKER SANITY BLOCKS: Unlock native text-to-speech speaker synthesis completely
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // FIXED LOGIN LOOP: Intercept OAuth transitions and hand them to external Chrome browser
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // If the app encounters a Google/YouTube account authentication node, pop out to native Chrome safely
                if (url.contains("://google.com") || url.contains("://spotify.com")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true; // Tells the WebView to freeze local navigation and hand over to system browser
                }
                return false;
            }
        });

        // OVERRIDE HARDWARE SOUND CAPTURE: Process microphone hooks natively
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                currentPermissionRequest = request;
                for (String grantTarget : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(grantTarget)) {
                        checkAndRequestHardwarePermissions();
                        return;
                    }
                }
                request.grant(request.getResources());
            }
        });

        webView.loadUrl("https://vibearc-player.vercel.app/");
    }

    private void checkAndRequestHardwarePermissions() {
        String[] hardwarePermissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
        };

        boolean allGranted = true;
        for (String perm : hardwarePermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            grantWebViewAudioAccess();
        } else {
            ActivityCompat.requestPermissions(this, hardwarePermissions, HARDWARE_PERMISSIONS_REQUEST_CODE);
        }
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
        if (requestCode == HARDWARE_PERMISSIONS_REQUEST_CODE) {
            boolean audioApproved = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    audioApproved = true;
                    break;
                }
            }
            if (audioApproved) {
                grantWebViewAudioAccess();
            } else {
                Toast.makeText(this, "Microphone access is mandatory for VibeArc AI Voice interactions.", Toast.LENGTH_LONG).show();
                if (currentPermissionRequest != null) {
                    currentPermissionRequest.deny();
                    currentPermissionRequest = null;
                }
            }
        }
    }
}
