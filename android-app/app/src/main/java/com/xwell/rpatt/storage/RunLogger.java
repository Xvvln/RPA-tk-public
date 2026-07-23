package com.xwell.rpatt.storage;

import android.content.Context;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class RunLogger {
    private final File directory;
    private final File events;
    private final File videos;
    private final ActionLedger actions;

    public RunLogger(Context context) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        directory = new File(new File(context.getFilesDir(), "runs"), stamp);
        if (!directory.mkdirs()) throw new IOException("无法创建任务目录");
        events = new File(directory, "events.jsonl");
        videos = new File(directory, "videos.jsonl");
        actions = new ActionLedger(new File(directory, "actions.jsonl"),
                new File(context.getFilesDir(), "action-ledger.jsonl"), directory.getName());
    }

    public File directory() { return directory; }
    public ActionLedger actions() { return actions; }

    public synchronized void writeConfig(JSONObject config) throws IOException {
        write(new File(directory, "config.json"), pretty(config), false);
    }

    public synchronized void event(String type, JSONObject detail) throws IOException {
        try {
            JSONObject value = new JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("type", type)
                    .put("detail", detail == null ? new JSONObject() : detail);
            write(events, value.toString() + "\n", true);
        } catch (JSONException error) {
            throw new IOException("无法序列化事件", error);
        }
    }

    public synchronized void video(JSONObject record) throws IOException {
        write(videos, record.toString() + "\n", true);
    }

    public synchronized void summary(JSONObject summary) throws IOException {
        write(new File(directory, "summary.json"), pretty(summary), false);
    }

    public File file(String name) { return new File(directory, name); }

    private static void write(File target, String text, boolean append) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(target, append)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
    }

    private static String pretty(JSONObject value) throws IOException {
        try {
            return value.toString(2) + "\n";
        } catch (JSONException error) {
            throw new IOException("无法序列化 JSON", error);
        }
    }
}
