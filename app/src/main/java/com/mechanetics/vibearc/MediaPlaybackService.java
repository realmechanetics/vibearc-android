package com.mechanetics.vibearc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MediaPlaybackService extends Service {
    public static final String ACTION_CONTROL = "com.mechanetics.vibearc.MEDIA_CONTROL";
    public static final String ACTION_PLAY = "com.mechanetics.vibearc.PLAY_DEVICE_AUDIO";
    public static final String ACTION_FILTERS = "com.mechanetics.vibearc.UPDATE_AUDIO_FILTERS";
    public static final String ACTION_VOLUME = "com.mechanetics.vibearc.UPDATE_AUDIO_VOLUME";
    public static final String ACTION_SPEED = "com.mechanetics.vibearc.UPDATE_AUDIO_SPEED";
    public static final String ACTION_ACTIVITY_VISIBILITY = "com.mechanetics.vibearc.ACTIVITY_VISIBILITY";
    private static final String CHANNEL_ID = "vibearc-local-playback";
    private static final int NOTIFICATION_ID = 4101;
    private static final int VIBEARC_PURPLE = Color.rgb(124, 58, 237);
    private static final String EXTRA_CONTROL = "control";
    private static final String EXTRA_TRACK = "track";
    private static final String EXTRA_FILTERS = "filters";
    private static final long[] EQ_FREQUENCIES = {
            31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable positionTicker = new Runnable() {
        @Override
        public void run() {
            if (player == null || !player.isPlaying()) return;
            positionMs = player.getCurrentPosition();
            updateSession();
            broadcastPlaybackState(false, null);
            handler.postDelayed(this, 1000);
        }
    };

    private MediaSessionCompat mediaSession;
    private Bitmap artwork;
    private MediaPlayer player;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener focusChangeListener;
    private boolean receiverRegistered;
    private boolean resumeOnFocusGain;
    private String trackUri;
    private String title = "VibeArc";
    private String artist = "Device audio";
    private String album = "";
    private String filtersJson = "{}";
    private long durationMs;
    private long positionMs;
    private boolean playing;
    private boolean playWhenPrepared = true;
    private boolean playerPrepared;
    private long pendingSeekMs = -1;
    private boolean activityInForeground = true;
    private boolean foregroundStarted;
    private float volume = 1f;
    private float speed = 1f;

    private final android.content.BroadcastReceiver noisyReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                        pausePlayer();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        artwork = createVibeArcArtwork();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mediaSession = new MediaSessionCompat(this, "VibeArcPlayback");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                resumePlayer();
            }

            @Override
            public void onPause() {
                pausePlayer();
            }

            @Override
            public void onStop() {
                stopPlayer();
            }

            @Override
            public void onSkipToNext() {
                dispatchControl("next");
            }

            @Override
            public void onSkipToPrevious() {
                dispatchControl("previous");
            }

            @Override
            public void onSeekTo(long position) {
                seekTo(position);
            }

            @Override
            public void onRewind() {
                seekTo(Math.max(0, positionMs - 10000));
            }

            @Override
            public void onFastForward() {
                seekTo(Math.min(durationMs, positionMs + 10000));
            }
        });
        registerNoisyReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            startTrack(intent.getStringExtra(EXTRA_TRACK));
        } else if (ACTION_FILTERS.equals(action)) {
            String filters = intent.getStringExtra(EXTRA_FILTERS);
            filtersJson = filters == null ? "{}" : filters;
            applyAudioFilters();
        } else if (ACTION_VOLUME.equals(action)) {
            volume = Math.max(0f, Math.min(1f, intent.getFloatExtra("value", 1f)));
            if (player != null) player.setVolume(volume, volume);
        } else if (ACTION_SPEED.equals(action)) {
            speed = Math.max(0.5f, Math.min(2f, intent.getFloatExtra("value", 1f)));
            if (player != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
                } catch (IllegalStateException ignored) {
                    // The player is preparing a source and will use the selected speed on resume.
                }
            }
        } else if (ACTION_ACTIVITY_VISIBILITY.equals(action)) {
            activityInForeground = intent.getBooleanExtra("foreground", false);
            if (player != null) {
                if (activityInForeground) {
                    if (foregroundStarted) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        foregroundStarted = false;
                    }
                } else {
                    startAsForeground();
                }
                updateSession();
            }
        } else {
            String control = intent.getStringExtra(EXTRA_CONTROL);
            if ("play".equals(control)) {
                resumePlayer();
            } else if ("pause".equals(control)) {
                pausePlayer();
            } else if ("stop".equals(control)) {
                stopPlayer();
            } else if ("next".equals(control) || "previous".equals(control)) {
                dispatchControl(control);
            } else if ("seek-forward".equals(control)) {
                seekTo(Math.min(durationMs, positionMs + 10000));
            } else if ("seek-backward".equals(control)) {
                seekTo(Math.max(0, positionMs - 10000));
            } else if ("seek-to".equals(control)) {
                seekTo(intent.getLongExtra("position", 0));
            }
        }
        return START_NOT_STICKY;
    }

    private void startTrack(String trackJson) {
        try {
            JSONObject track = new JSONObject(trackJson == null ? "{}" : trackJson);
            trackUri = track.getString("uri");
            title = track.optString("title", "Unknown track");
            artist = track.optString("artist", "Unknown artist");
            album = track.optString("album", "");
            filtersJson = track.optString("filters", "{}");
            volume = Math.max(0f, Math.min(1f, (float) track.optDouble("volume", volume)));
            speed = Math.max(0.5f, Math.min(2f, (float) track.optDouble("speed", speed)));
            playWhenPrepared = true;
            positionMs = 0;
            durationMs = 0;
            pendingSeekMs = -1;
        } catch (Exception exception) {
            broadcastPlaybackState(false, "The selected device audio file could not be opened.");
            stopPlayer();
            return;
        }

        releasePlayer();
        MediaPlayer newPlayer = new MediaPlayer();
        player = newPlayer;
        playerPrepared = false;
        newPlayer.setWakeMode(getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK);
        newPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        newPlayer.setOnPreparedListener(mediaPlayer -> {
            if (player != mediaPlayer) return;
            playerPrepared = true;
            durationMs = mediaPlayer.getDuration();
            applyAudioFilters();
            mediaPlayer.setVolume(volume, volume);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
                } catch (IllegalStateException exception) {
                    broadcastPlaybackState(false, "Playback speed could not be applied.");
                }
            }
            if (pendingSeekMs >= 0) {
                seekPlayerTo(pendingSeekMs);
                pendingSeekMs = -1;
            }
            if (!playWhenPrepared) {
                playing = false;
                updateSession();
                updateNotification();
                broadcastPlaybackState(false, null);
                return;
            }
            if (!requestAudioFocus()) {
                broadcastPlaybackState(false, "Audio focus is currently unavailable.");
                stopPlayer();
                return;
            }
            mediaPlayer.start();
            playing = true;
            positionMs = Math.max(0, mediaPlayer.getCurrentPosition());
            updateSession();
            updateNotification();
            broadcastPlaybackState(false, null);
            handler.removeCallbacks(positionTicker);
            handler.post(positionTicker);
        });
        newPlayer.setOnCompletionListener(mediaPlayer -> {
            if (player != mediaPlayer) return;
            positionMs = durationMs;
            playing = false;
            playWhenPrepared = false;
            updateSession();
            updateNotification();
            broadcastPlaybackState(true, null);
        });
        newPlayer.setOnSeekCompleteListener(mediaPlayer -> {
            if (player != mediaPlayer) return;
            positionMs = mediaPlayer.getCurrentPosition();
            updateSession();
            broadcastPlaybackState(false, null);
        });
        newPlayer.setOnErrorListener((mediaPlayer, what, extra) -> {
            if (player != mediaPlayer) return true;
            broadcastPlaybackState(false, "This audio file is not supported or could not be read.");
            stopPlayer();
            return true;
        });

        updateSession();
        startAsForeground();
        try {
            newPlayer.setDataSource(this, Uri.parse(trackUri));
            newPlayer.prepareAsync();
        } catch (Exception exception) {
            broadcastPlaybackState(false, "This audio file is no longer available on the device.");
            stopPlayer();
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (focusChangeListener != null) return true;
        AudioManager.OnAudioFocusChangeListener listener = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                resumeOnFocusGain = false;
                pausePlayer();
                abandonAudioFocus();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                resumeOnFocusGain = playing;
                pausePlayer();
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocusGain) {
                resumeOnFocusGain = false;
                resumePlayer();
            }
        };
        int result = audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
        );
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
        focusChangeListener = listener;
        return true;
    }

    private void pausePlayer() {
        playWhenPrepared = false;
        if (player != null && player.isPlaying()) {
            positionMs = player.getCurrentPosition();
            player.pause();
        }
        playing = false;
        handler.removeCallbacks(positionTicker);
        updateSession();
        updateNotification();
        broadcastPlaybackState(false, null);
    }

    private void resumePlayer() {
        playWhenPrepared = true;
        if (player == null) return;
        if (!playerPrepared) {
            updateSession();
            updateNotification();
            broadcastPlaybackState(false, null);
            return;
        }
        try {
            if (requestAudioFocus()) {
                player.start();
                playing = true;
                updateSession();
                updateNotification();
                broadcastPlaybackState(false, null);
                handler.removeCallbacks(positionTicker);
                handler.post(positionTicker);
            }
        } catch (IllegalStateException exception) {
            broadcastPlaybackState(false, "Playback could not be resumed.");
        }
    }

    private void seekTo(long position) {
        if (player == null) return;
        positionMs = Math.max(0, Math.min(durationMs, position));
        if (!playerPrepared) {
            pendingSeekMs = positionMs;
            updateSession();
            broadcastPlaybackState(false, null);
            return;
        }
        seekPlayerTo(positionMs);
    }

    private void seekPlayerTo(long position) {
        if (player == null || !playerPrepared) return;
        positionMs = Math.max(0, Math.min(durationMs, position));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
        } else {
            player.seekTo((int) positionMs);
        }
        updateSession();
        broadcastPlaybackState(false, null);
    }

    private void stopPlayer() {
        releasePlayer();
        playing = false;
        playWhenPrepared = false;
        positionMs = 0;
        handler.removeCallbacks(positionTicker);
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0)
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
                    .build());
        }
        broadcastPlaybackState(false, null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.reset();
            } catch (IllegalStateException ignored) {
                // The player may already be released after a decoding error.
            }
            player.release();
            player = null;
        }
        playerPrepared = false;
        releaseAudioFilters();
    }

    private void abandonAudioFocus() {
        if (audioManager != null && focusChangeListener != null) {
            audioManager.abandonAudioFocus(focusChangeListener);
            focusChangeListener = null;
        }
    }

    private void applyAudioFilters() {
        if (player == null || player.getAudioSessionId() == 0) return;
        releaseAudioFilters();
        String filterError = null;
        int sessionId = player.getAudioSessionId();
        JSONObject filters;
        try {
            filters = new JSONObject(filtersJson);
        } catch (Exception exception) {
            broadcastPlaybackState(false, "Audio filter settings could not be read.");
            return;
        }

        try {
            equalizer = new Equalizer(1000, sessionId);
            equalizer.setEnabled(false);
            JSONArray gains = filters.optJSONArray("bands");
            if (gains != null && equalizer.getNumberOfBands() > 0) {
                short[] range = equalizer.getBandLevelRange();
                for (short band = 0; band < equalizer.getNumberOfBands(); band++) {
                    int centerHz = equalizer.getCenterFreq(band) / 1000;
                    int closestIndex = 0;
                    for (int i = 1; i < EQ_FREQUENCIES.length; i++) {
                        if (Math.abs(Math.log((double) centerHz / EQ_FREQUENCIES[i]))
                                < Math.abs(Math.log((double) centerHz / EQ_FREQUENCIES[closestIndex]))) {
                            closestIndex = i;
                        }
                    }
                    int db = gains.optInt(closestIndex, 0);
                    short level = (short) Math.max(range[0], Math.min(range[1], db * 100));
                    equalizer.setBandLevel(band, level);
                }
            }
            equalizer.setEnabled(true);
        } catch (Exception exception) {
            if (equalizer != null) {
                equalizer.release();
                equalizer = null;
            }
            filterError = "The equalizer is not supported by this device.";
        }

        try {
            bassBoost = new BassBoost(1000, sessionId);
            boolean bassEnabled = filters.optBoolean("bassBoost", false) &&
                    bassBoost.getStrengthSupported();
            if (bassEnabled) {
                bassBoost.setStrength((short) 750);
            }
            bassBoost.setEnabled(bassEnabled);
            if (filters.optBoolean("bassBoost", false) && !bassEnabled) {
                filterError = "Bass boost is not supported by this device.";
            }
        } catch (Exception exception) {
            if (bassBoost != null) {
                bassBoost.release();
                bassBoost = null;
            }
            if (filters.optBoolean("bassBoost", false)) {
                filterError = "Bass boost is not supported by this device.";
            }
        }

        if (filterError != null) {
            broadcastPlaybackState(false, filterError);
        }
    }

    private void releaseAudioFilters() {
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        if (bassBoost != null) {
            bassBoost.release();
            bassBoost = null;
        }
    }

    private void updateSession() {
        if (mediaSession == null) return;
        mediaSession.setActive(!activityInForeground);
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build());
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_FAST_FORWARD
                | PlaybackStateCompat.ACTION_REWIND;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                        playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        positionMs,
                        speed,
                        SystemClock.elapsedRealtime()
                )
                .build());
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void updateNotification() {
        if (mediaSession == null) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        PendingIntent previous = controlPendingIntent("previous", 1);
        PendingIntent toggle = controlPendingIntent(playing ? "pause" : "play", 2);
        PendingIntent next = controlPendingIntent("next", 3);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.vibearc_notification)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText(album)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(VIBEARC_PURPLE)
                .setColorized(true)
                .setLargeIcon(artwork)
                .addAction(new NotificationCompat.Action(
                        android.R.drawable.ic_media_previous,
                        "Previous",
                        previous
                ))
                .addAction(new NotificationCompat.Action(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play",
                        toggle
                ))
                .addAction(new NotificationCompat.Action(
                        android.R.drawable.ic_media_next,
                        "Next",
                        next
                ))
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setContentIntent(PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                ))
                .build();
    }

    private PendingIntent controlPendingIntent(String control, int requestCode) {
        Intent intent = new Intent(this, MediaPlaybackService.class)
                .putExtra(EXTRA_CONTROL, control);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void dispatchControl(String control) {
        Intent intent = new Intent(ACTION_CONTROL)
                .setPackage(getPackageName())
                .putExtra(EXTRA_CONTROL, control);
        sendBroadcast(intent);
    }

    private void broadcastPlaybackState(boolean ended, String error) {
        JSONObject detail = new JSONObject();
        try {
            detail.put("uri", trackUri == null ? "" : trackUri);
            detail.put("playing", playing);
            detail.put("active", player != null);
            detail.put("preparing", player != null && !playerPrepared);
            detail.put("willPlayWhenPrepared", playWhenPrepared);
            detail.put("position", positionMs / 1000.0);
            detail.put("duration", durationMs / 1000.0);
            detail.put("ended", ended);
            if (error != null) detail.put("error", error);
        } catch (Exception ignored) {
            return;
        }
        Intent intent = new Intent("com.mechanetics.vibearc.PLAYBACK_STATE")
                .setPackage(getPackageName())
                .putExtra("detail", detail.toString());
        sendBroadcast(intent);
    }

    private Bitmap createVibeArcArtwork() {
        Bitmap artwork = BitmapFactory.decodeResource(getResources(), R.drawable.vibearc_logo);
        if (artwork == null) {
            throw new IllegalStateException("VibeArc notification artwork could not be loaded");
        }
        return artwork;
    }

    private void registerNoisyReceiver() {
        Intent intent = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                new android.content.IntentFilter(intent.getAction()),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiverRegistered = true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VibeArc audio playback",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Playback controls for music from your device");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlayer();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(positionTicker);
        releasePlayer();
        abandonAudioFocus();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        if (receiverRegistered) unregisterReceiver(noisyReceiver);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (artwork != null) {
            artwork.recycle();
            artwork = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
