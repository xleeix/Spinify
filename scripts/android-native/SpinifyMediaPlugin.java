package com.xleeix.spinify;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(
    name = "SpinifyMedia",
    permissions = {
        @Permission(
            alias = "storage",
            strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }
        ),
        @Permission(
            alias = "audioModern",
            strings = { "android.permission.READ_MEDIA_AUDIO" }
        ),
        @Permission(
            alias = "audioLegacy",
            strings = { Manifest.permission.READ_EXTERNAL_STORAGE }
        )
    }
)
public class SpinifyMediaPlugin extends Plugin {
    private final Map<String, File> pendingFiles = new ConcurrentHashMap<>();

    @PluginMethod
    public void beginVideoFile(PluginCall call) {
        try {
            File tempFile = File.createTempFile("spinify_vid_", ".tmp", getContext().getCacheDir());
            String uploadId = UUID.randomUUID().toString();
            pendingFiles.put(uploadId, tempFile);
            JSObject ret = new JSObject();
            ret.put("uploadId", uploadId);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to create temp video file: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void appendVideoChunk(PluginCall call) {
        String uploadId = call.getString("uploadId");
        String base64 = call.getString("base64");
        if (uploadId == null || base64 == null) {
            call.reject("Missing uploadId or base64 chunk");
            return;
        }
        File tempFile = pendingFiles.get(uploadId);
        if (tempFile == null) {
            call.reject("Upload session not found");
            return;
        }
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            try (FileOutputStream fos = new FileOutputStream(tempFile, true)) {
                fos.write(bytes);
            }
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to append video chunk: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void discardVideoUpload(PluginCall call) {
        String uploadId = call.getString("uploadId");
        if (uploadId != null) {
            File tempFile = pendingFiles.remove(uploadId);
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void commitVideoToGallery(PluginCall call) {
        String uploadId = call.getString("uploadId");
        if (uploadId == null) {
            call.reject("Missing uploadId");
            return;
        }
        File tempFile = pendingFiles.get(uploadId);
        if (tempFile == null || !tempFile.exists()) {
            call.reject("Temp video file not found");
            return;
        }

        String rawName = call.getString("fileName", "spinify-video.mp4");
        String fileName = sanitizeFileName(rawName);
        String mimeType = call.getString("mimeType", fileName.endsWith(".webm") ? "video/webm" : "video/mp4");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pendingFiles.remove(uploadId);
            saveViaMediaStoreQ(call, tempFile, fileName, mimeType);
            return;
        }

        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionForAlias("storage", call, "storagePermsCallback");
            return;
        }

        pendingFiles.remove(uploadId);
        saveLegacyExternal(call, tempFile, fileName, mimeType);
    }

    @PermissionCallback
    private void storagePermsCallback(PluginCall call) {
        String uploadId = call.getString("uploadId");
        File tempFile = uploadId != null ? pendingFiles.remove(uploadId) : null;
        if (getPermissionState("storage") == PermissionState.GRANTED && tempFile != null && tempFile.exists()) {
            String fileName = sanitizeFileName(call.getString("fileName", "spinify-video.mp4"));
            String mimeType = call.getString("mimeType", fileName.endsWith(".webm") ? "video/webm" : "video/mp4");
            saveLegacyExternal(call, tempFile, fileName, mimeType);
        } else {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            call.reject("Storage permission denied");
        }
    }

    private void saveViaMediaStoreQ(PluginCall call, File tempFile, String fileName, String mimeType) {
        ContentResolver resolver = getContext().getContentResolver();
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000L;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Video.Media.DATE_ADDED, nowSec);
        values.put(MediaStore.Video.Media.DATE_MODIFIED, nowSec);
        values.put(MediaStore.Video.Media.DATE_TAKEN, nowMs);
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Spinify");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = null;
        try {
            itemUri = resolver.insert(collection, values);
            if (itemUri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = resolver.openOutputStream(itemUri)) {
                if (out == null) {
                    throw new IllegalStateException("Failed to open MediaStore output stream");
                }
                byte[] buffer = new byte[65536];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                out.flush();
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(itemUri, values, null, null);

            JSObject ret = new JSObject();
            ret.put("saved", true);
            ret.put("uri", itemUri.toString());
            ret.put("displayPath", "Movies/Spinify/" + fileName);
            call.resolve(ret);
        } catch (Exception e) {
            if (itemUri != null) {
                try {
                    resolver.delete(itemUri, null, null);
                } catch (Exception ignored) {}
            }
            call.reject("Failed to save video to Gallery: " + e.getMessage(), e);
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void saveLegacyExternal(PluginCall call, File tempFile, String fileName, String mimeType) {
        try {
            File moviesDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Spinify"
            );
            if (!moviesDir.exists() && !moviesDir.mkdirs()) {
                throw new IllegalStateException("Unable to create Movies/Spinify directory");
            }
            File destFile = new File(moviesDir, fileName);
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[65536];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                out.flush();
            }
            MediaScannerConnection.scanFile(
                getContext(),
                new String[] { destFile.getAbsolutePath() },
                new String[] { mimeType },
                null
            );

            JSObject ret = new JSObject();
            ret.put("saved", true);
            ret.put("uri", Uri.fromFile(destFile).toString());
            ret.put("displayPath", "Movies/Spinify/" + fileName);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to save video to Gallery: " + e.getMessage(), e);
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @PluginMethod
    public void requestAllAudioAndScan(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(getContext(), "android.permission.READ_MEDIA_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionForAlias("audioModern", call, "audioPermsCallback");
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionForAlias("audioLegacy", call, "audioPermsCallback");
                return;
            }
        }
        queryAllDeviceAudio(call);
    }

    @PermissionCallback
    private void audioPermsCallback(PluginCall call) {
        boolean granted;
        if (Build.VERSION.SDK_INT >= 33) {
            granted = getPermissionState("audioModern") == PermissionState.GRANTED;
        } else {
            granted = getPermissionState("audioLegacy") == PermissionState.GRANTED;
        }
        if (!granted) {
            call.reject("Audio permission denied");
            return;
        }
        queryAllDeviceAudio(call);
    }

    private void queryAllDeviceAudio(PluginCall call) {
        JSArray tracks = new JSArray();
        ContentResolver resolver = getContext().getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA
        };
        String selection = MediaStore.Audio.Media.DURATION + " > 500 OR " + MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = resolver.query(collection, projection, selection, null, sortOrder)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                int durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                int mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);
                int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);

                while (cursor.moveToNext() && tracks.length() < 1000) {
                    long id = cursor.getLong(idCol);
                    String displayName = nameCol >= 0 ? cursor.getString(nameCol) : null;
                    String title = titleCol >= 0 ? cursor.getString(titleCol) : null;
                    String artist = artistCol >= 0 ? cursor.getString(artistCol) : null;
                    long durationMs = durCol >= 0 ? cursor.getLong(durCol) : 0L;
                    String mimeType = mimeCol >= 0 ? cursor.getString(mimeCol) : "audio/mpeg";
                    String dataPath = dataCol >= 0 ? cursor.getString(dataCol) : null;

                    Uri contentUri = ContentUris.withAppendedId(collection, id);
                    String fileUri = null;
                    if (dataPath != null && !dataPath.isEmpty()) {
                        File f = new File(dataPath);
                        if (f.exists() && f.canRead()) {
                            fileUri = Uri.fromFile(f).toString();
                        }
                    }

                    JSObject item = new JSObject();
                    item.put("id", String.valueOf(id));
                    item.put("contentUri", contentUri.toString());
                    if (fileUri != null) {
                        item.put("fileUri", fileUri);
                    }
                    item.put("filename", displayName != null && !displayName.isEmpty() ? displayName : ((title != null ? title : "Track") + ".mp3"));
                    item.put("title", title != null && !title.isEmpty() ? title : "");
                    item.put("artist", artist != null && !"<unknown>".equals(artist) ? artist : "");
                    item.put("durationMs", durationMs);
                    item.put("mimeType", mimeType != null ? mimeType : "audio/mpeg");
                    tracks.put(item);
                }
            }
            JSObject ret = new JSObject();
            ret.put("tracks", tracks);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to query device audio: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void readAudioBase64(PluginCall call) {
        String uriStr = call.getString("contentUri");
        if (uriStr == null || uriStr.isEmpty()) {
            call.reject("Missing contentUri");
            return;
        }
        try {
            Uri uri = Uri.parse(uriStr);
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    call.reject("Could not open audio stream");
                    return;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int len;
                long total = 0;
                while ((len = in.read(buf)) != -1) {
                    total += len;
                    if (total > 80 * 1024 * 1024) {
                        call.reject("Audio file too large to buffer");
                        return;
                    }
                    baos.write(buf, 0, len);
                }
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                JSObject ret = new JSObject();
                ret.put("base64", base64);
                call.resolve(ret);
            }
        } catch (Exception e) {
            call.reject("Failed to read audio: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void shareVideoUri(PluginCall call) {
        String uriStr = call.getString("uri");
        if (uriStr == null || uriStr.isEmpty()) {
            call.reject("Missing video uri");
            return;
        }
        String title = call.getString("title", "Spinify");
        String text = call.getString("text", "Spinify — @xleeix");
        String mimeType = call.getString("mimeType", "video/mp4");

        try {
            Uri shareUri = Uri.parse(uriStr);
            if ("file".equals(shareUri.getScheme()) && shareUri.getPath() != null) {
                shareUri = FileProvider.getUriForFile(
                    getContext(),
                    getContext().getPackageName() + ".fileprovider",
                    new File(shareUri.getPath())
                );
            }
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            shareIntent.putExtra(Intent.EXTRA_TEXT, text);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(shareIntent, title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().runOnUiThread(() -> getActivity().startActivity(chooser));

            JSObject ret = new JSObject();
            ret.put("shared", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to share video: " + e.getMessage(), e);
        }
    }

    private String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "spinify-video.mp4";
        }
        return cleaned;
    }
}
