package com.mechanetics.vibearc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OfflineSpeechModelManager {
    interface Listener {
        void onModelStateChanged(String detail);
    }

    static final class ModelInfo {
        final String id;
        final String language;
        final String label;
        final String md5;
        final long sizeBytes;

        ModelInfo(String id, String language, String label, String md5, long sizeBytes) {
            this.id = id;
            this.language = language;
            this.label = label;
            this.md5 = md5;
            this.sizeBytes = sizeBytes;
        }

        String getUrl() {
            return "https://alphacephei.com/vosk/models/" + id + ".zip";
        }
    }

    private static final long MAX_MODEL_SIZE = 200L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_SIZE = 600L * 1024L * 1024L;
    private static final String PREFS = "vibearc_offline_speech";
    private static final String ACTIVE_MODEL = "active_model";
    private static final ModelInfo[] CATALOG = {
            new ModelInfo("vosk-model-small-ar-0.3", "Arabic", "Arabic", "0eb578c0779f85ef039b7ee5bc1b64ed", 104351896L),
            new ModelInfo("vosk-model-small-ar-tn-0.1-linto", "Arabic (Tunisian)", "Arabic (Tunisian)", "67bbe16beaa17e6cf14f8c295ee5ddd1", 165703754L),
            new ModelInfo("vosk-model-small-ca-0.4", "Catalan", "Catalan", "5dc7901815fef3dc8b784b31f309385c", 43405881L),
            new ModelInfo("vosk-model-small-cn-0.22", "Chinese", "Chinese", "d1a8e82933dcc7632667c036b0bb3dbb", 43898754L),
            new ModelInfo("vosk-model-small-cs-0.4-rhasspy", "Czech", "Czech", "df5ddac5ee8632f6b46a6fc751953286", 46088666L),
            new ModelInfo("vosk-model-small-de-0.15", "German", "German", "4f21f92c0897b48287ef8839420608eb", 46499967L),
            new ModelInfo("vosk-model-small-en-gb-0.15", "English (UK)", "English (UK)", "6afd611b04b2b47c129c3615dc502383", 42757500L),
            new ModelInfo("vosk-model-small-en-in-0.4", "English (India)", "English (India)", "62fd085f33c8c5fc01a235030e2a1fe3", 37573330L),
            new ModelInfo("vosk-model-small-en-us-0.15", "English (US)", "English (US)", "09ab50ccd62b674cbaa231b825f9c1cb", 41205931L),
            new ModelInfo("vosk-model-small-eo-0.42", "Esperanto", "Esperanto", "3e9319ca789fa06fd3efa68d51c85d6b", 43839401L),
            new ModelInfo("vosk-model-small-es-0.42", "Spanish", "Spanish", "2d5c94f9859a84881a0ef744738ebd31", 39817833L),
            new ModelInfo("vosk-model-small-fa-0.42", "Farsi", "Farsi", "cc2b18af256ffab2c44055f6a02ecb3d", 53431220L),
            new ModelInfo("vosk-model-small-fr-0.22", "French", "French", "8873b1234503f6edd55f54bfff31cf3e", 42233323L),
            new ModelInfo("vosk-model-small-gu-0.42", "Gujarati", "Gujarati", "4595a6f0cc0c88fee6eec7d80496fc10", 108054987L),
            new ModelInfo("vosk-model-small-hi-0.22", "Hindi", "Hindi", "80f1265262c9a8a515f2707498e1b485", 44458845L),
            new ModelInfo("vosk-model-small-it-0.22", "Italian", "Italian", "fbd8f9c72cbb8c3dfa3e4581bd3585f4", 49665141L),
            new ModelInfo("vosk-model-small-ja-0.22", "Japanese", "Japanese", "0e3163dd62dfb0d823353718ac3cbf79", 49704573L),
            new ModelInfo("vosk-model-small-ka-0.42", "Georgian", "Georgian", "7e95b6a69b60a5f1c45e29d1cd2ac353", 45682310L),
            new ModelInfo("vosk-model-small-ko-0.22", "Korean", "Korean", "fa8029a173787a159e0e72fe6135f890", 86914329L),
            new ModelInfo("vosk-model-small-ky-0.42", "Kyrgyz", "Kyrgyz", "541eb45589c2b6f8350905086a76f072", 51041096L),
            new ModelInfo("vosk-model-small-kz-0.42", "Kazakh", "Kazakh", "1c7dc487ac5e0c24acfa7566c106ba2d", 59697294L),
            new ModelInfo("vosk-model-small-nl-0.22", "Dutch", "Dutch", "4582e1f20d6849099da08511f9797017", 40441176L),
            new ModelInfo("vosk-model-small-pl-0.22", "Polish", "Polish", "91cbbd6231320467da672be31827b6ac", 52979372L),
            new ModelInfo("vosk-model-small-pt-0.3", "Portuguese", "Portuguese", "458c69371c5a0b9ab6ee8fa417bf89da", 32453112L),
            new ModelInfo("vosk-model-small-ru-0.22", "Russian", "Russian", "d1759dc83eb8fd87850129afbd9f4b7b", 46236750L),
            new ModelInfo("vosk-model-small-te-0.42", "Telugu", "Telugu", "ae3c093fd7dd883d462983b15900ca61", 60544249L),
            new ModelInfo("vosk-model-small-tg-0.22", "Tajik", "Tajik", "fca4e40de915a5bd2f30e32d60af45c0", 51879043L),
            new ModelInfo("vosk-model-small-tr-0.3", "Turkish", "Turkish", "198511631860597639a0ebf779263fcf", 36855784L),
            new ModelInfo("vosk-model-small-uk-v3-small", "Ukrainian", "Ukrainian", "3188e41b4a38369d8b9527d0149616ab", 143914407L),
            new ModelInfo("vosk-model-small-uz-0.22", "Uzbek", "Uzbek", "4d75acfef76fe919c8fb68cd1179c182", 51061189L),
            new ModelInfo("vosk-model-small-vn-0.4", "Vietnamese", "Vietnamese", "b31b474a1ef75488c5fa575f8e2a1269", 33656337L)
    };

    private final Context context;
    private final Listener listener;
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private volatile boolean downloading;
    private volatile String downloadId;
    private volatile int downloadProgress;

    OfflineSpeechModelManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    static ModelInfo find(String id) {
        if (id == null) return null;
        for (ModelInfo model : CATALOG) {
            if (model.id.equals(id)) return model;
        }
        return null;
    }

    File modelDirectory(String id) {
        return new File(new File(context.getNoBackupFilesDir(), "offline-speech"), id);
    }

    boolean isInstalled(String id) {
        File directory = modelDirectory(id);
        return new File(directory, "am/final.mdl").isFile()
                && new File(directory, "conf/mfcc.conf").isFile();
    }

    String activeModelId() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ACTIVE_MODEL, null);
    }

    boolean select(String id) {
        if (find(id) == null || !isInstalled(id)) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ACTIVE_MODEL, id)
                .apply();
        notifyState(id, "ready", null);
        return true;
    }

    synchronized boolean remove(String id) {
        if (find(id) == null || downloading) return false;
        File directory = modelDirectory(id);
        if (!directory.exists()) return false;
        deleteTree(directory);
        if (directory.exists()) return false;
        if (id.equals(activeModelId())) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(ACTIVE_MODEL)
                    .apply();
        }
        notifyState(id, "removed", null);
        return true;
    }

    void reportError(String id, String message) {
        notifyState(id, "error", message);
    }

    String getStateJson() {
        JSONArray models = new JSONArray();
        String active = activeModelId();
        for (ModelInfo model : CATALOG) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", model.id);
                item.put("language", model.language);
                item.put("label", model.label);
                item.put("sizeBytes", model.sizeBytes);
                item.put("installed", isInstalled(model.id));
                item.put("selected", model.id.equals(active));
                item.put("downloading", downloading && model.id.equals(downloadId));
                item.put("progress", downloading && model.id.equals(downloadId) ? downloadProgress : 0);
            } catch (Exception ignored) {
                continue;
            }
            models.put(item);
        }
        JSONObject state = new JSONObject();
        try {
            state.put("models", models);
            state.put("activeModelId", active == null ? JSONObject.NULL : active);
            state.put("downloading", downloading);
        } catch (Exception ignored) {
            return "{\"models\":[]}";
        }
        return state.toString();
    }

    void download(String id) {
        ModelInfo model = find(id);
        if (model == null) {
            notifyState(id, "error", "That speech language pack is not supported.");
            return;
        }
        synchronized (this) {
            if (downloading) {
                notifyState(id, "error", "Another language pack is already downloading.");
                return;
            }
            downloading = true;
            downloadId = id;
            downloadProgress = 0;
        }
        notifyState(id, "downloading", null);
        downloads.execute(() -> install(model));
    }

    void close() {
        downloads.shutdownNow();
    }

    private void install(ModelInfo model) {
        File root = new File(context.getNoBackupFilesDir(), "offline-speech");
        File zipFile = new File(root, model.id + ".zip.part");
        File staging = new File(root, model.id + ".installing");
        File destination = modelDirectory(model.id);
        HttpURLConnection connection = null;
        try {
            if (!root.exists() && !root.mkdirs()) throw new Exception("Could not create speech model storage.");
            long requiredSpace = Math.max(128L * 1024L * 1024L, model.sizeBytes * 3L);
            if (root.getUsableSpace() < requiredSpace) {
                throw new Exception("There is not enough free storage for this language pack.");
            }
            URL url = new URL(model.getUrl());
            if (!"https".equals(url.getProtocol()) || !"alphacephei.com".equals(url.getHost())) {
                throw new Exception("The speech model download address was rejected.");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            URL finalUrl = connection.getURL();
            if (!"https".equals(finalUrl.getProtocol()) || !"alphacephei.com".equals(finalUrl.getHost())) {
                throw new Exception("The language pack download was redirected to an untrusted address.");
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new Exception("The language pack server returned HTTP " + connection.getResponseCode() + ".");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_MODEL_SIZE || (contentLength >= 0 && contentLength != model.sizeBytes)) {
                throw new Exception("The language pack size did not match its published details.");
            }

            MessageDigest digest = MessageDigest.getInstance("MD5");
            long downloaded = 0;
            long lastProgressUpdate = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(zipFile))) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    downloaded += count;
                    if (downloaded > model.sizeBytes || downloaded > MAX_MODEL_SIZE) {
                        throw new Exception("The downloaded language pack exceeded its published size.");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    if (downloaded - lastProgressUpdate >= 512 * 1024L) {
                        lastProgressUpdate = downloaded;
                        downloadProgress = (int) Math.min(99, downloaded * 100 / model.sizeBytes);
                        notifyState(model.id, "downloading", null);
                    }
                }
            }
            if (downloaded != model.sizeBytes || !model.md5.equals(hex(digest.digest()))) {
                throw new Exception("The language pack failed its integrity check. Please try again.");
            }
            extractModel(zipFile, staging, model.id);
            if (!new File(staging, "am/final.mdl").isFile()
                    || !new File(staging, "conf/mfcc.conf").isFile()) {
                throw new Exception("The downloaded files are not a valid speech model.");
            }
            File backup = new File(root, model.id + ".backup");
            deleteTree(backup);
            boolean hadPreviousInstall = destination.exists();
            if (hadPreviousInstall && !destination.renameTo(backup)) {
                throw new Exception("Could not safely replace the installed language pack.");
            }
            if (!staging.renameTo(destination)) {
                if (hadPreviousInstall) backup.renameTo(destination);
                throw new Exception("Could not finish installing the speech language pack.");
            }
            deleteTree(backup);
            if (activeModelId() == null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(ACTIVE_MODEL, model.id)
                        .apply();
            }
            downloadProgress = 100;
            notifyState(model.id, "installed", null);
        } catch (Exception exception) {
            deleteTree(staging);
            String message = exception.getMessage();
            notifyState(model.id, "error",
                    message == null || message.trim().isEmpty()
                            ? "Could not download or install the speech language pack."
                            : message);
        } finally {
            if (connection != null) connection.disconnect();
            if (zipFile.exists() && !zipFile.delete()) zipFile.deleteOnExit();
            downloading = false;
            downloadId = null;
            downloadProgress = 0;
            notifyState(model.id, "idle", null);
        }
    }

    private void extractModel(File zipFile, File staging, String modelId) throws Exception {
        deleteTree(staging);
        if (!staging.mkdirs()) throw new Exception("Could not prepare language pack installation.");
        String rootPrefix = modelId + "/";
        long extractedBytes = 0;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = input.getNextEntry()) != null) {
                String path = entry.getName().replace('\\', '/');
                if (!path.startsWith(rootPrefix) || path.startsWith("/") || path.contains("../")
                        || path.contains(":")) {
                    throw new Exception("The language pack contains an invalid file path.");
                }
                String relative = path.substring(rootPrefix.length());
                if (relative.isEmpty()) continue;
                File target = new File(staging, relative);
                String canonicalRoot = staging.getCanonicalPath() + File.separator;
                if (!target.getCanonicalPath().startsWith(canonicalRoot)) {
                    throw new Exception("The language pack contains an unsafe file path.");
                }
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) throw new Exception("Could not unpack language pack.");
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new Exception("Could not unpack language pack.");
                }
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        extractedBytes += count;
                        if (extractedBytes > MAX_EXTRACTED_SIZE) {
                            throw new Exception("The uncompressed language pack is too large.");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private void notifyState(String id, String status, String error) {
        JSONObject event;
        try {
            event = new JSONObject(getStateJson());
            event.put("download", new JSONObject()
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("status", status)
                    .put("progress", downloadProgress)
                    .put("error", error == null ? JSONObject.NULL : error));
        } catch (Exception ignored) {
            event = new JSONObject();
        }
        listener.onModelStateChanged(event.toString());
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }
}
