package com.lastverse.pro;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local cache and offline outgoing-message queue. */
public final class LocalDb extends SQLiteOpenHelper {
    public LocalDb(Context context) { super(context, "last_verse.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, remote_id INTEGER, conversation_id INTEGER NOT NULL, sender_id INTEGER, text TEXT NOT NULL, created_at TEXT, client_id TEXT, pending INTEGER NOT NULL DEFAULT 0, UNIQUE(remote_id), UNIQUE(client_id))");
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, id)");
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, body TEXT NOT NULL, client_id TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public synchronized void cacheConversations(JSONArray rows) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                ContentValues values = new ContentValues();
                values.put("id", row.optLong("id"));
                values.put("payload", row.toString());
                values.put("updated_at", row.optString("updatedAt"));
                db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized JSONArray getCachedConversations() {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT payload FROM conversations ORDER BY updated_at DESC", null)) {
            while (cursor.moveToNext()) result.put(new JSONObject(cursor.getString(0)));
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized void cacheMessages(long conversationId, JSONArray messages) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row == null) continue;
                ContentValues values = new ContentValues();
                values.put("remote_id", row.optLong("id"));
                values.put("conversation_id", conversationId);
                values.put("sender_id", row.optLong("senderId"));
                values.put("text", row.optString("text"));
                values.put("created_at", row.optString("createdAt"));
                values.put("pending", 0);
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized JSONArray getMessages(long conversationId) {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT remote_id, sender_id, text, created_at, client_id, pending FROM messages WHERE conversation_id=? ORDER BY id",
                new String[]{String.valueOf(conversationId)})) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                if (!cursor.isNull(0)) row.put("id", cursor.getLong(0));
                row.put("senderId", cursor.getLong(1)); row.put("text", cursor.getString(2));
                row.put("createdAt", cursor.getString(3)); row.put("clientMessageId", cursor.getString(4));
                row.put("pending", cursor.getInt(5) == 1); result.put(row);
            }
        } catch (Exception ignored) { }
        return result;
    }


    public synchronized boolean messagesChanged(long conversationId, JSONArray remote) {
        JSONArray local = getMessages(conversationId);
        for (int i = 0; i < remote.length(); i++) {
            JSONObject incoming = remote.optJSONObject(i); if (incoming == null) continue;
            boolean found = false;
            for (int j = 0; j < local.length(); j++) {
                JSONObject cached = local.optJSONObject(j); if (cached == null) continue;
                if (cached.optLong("id", -1) == incoming.optLong("id", -2)) {
                    found = true;
                    if (!cached.optString("text").equals(incoming.optString("text"))) return true;
                    break;
                }
            }
            if (!found) return true;
        }
        return false;
    }

    public synchronized void addPending(long conversationId, String text, String clientId, String createdAt, long senderId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues message = new ContentValues();
            message.put("conversation_id", conversationId); message.put("sender_id", senderId); message.put("text", text);
            message.put("created_at", createdAt); message.put("client_id", clientId); message.put("pending", 1);
            db.insertWithOnConflict("messages", null, message, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues outbox = new ContentValues();
            outbox.put("conversation_id", conversationId); outbox.put("body", text);
            outbox.put("client_id", clientId); outbox.put("created_at", createdAt);
            db.insertWithOnConflict("outbox", null, outbox, SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized JSONArray pendingMessages() {
        JSONArray result = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT conversation_id, body, client_id FROM outbox ORDER BY id LIMIT 50", null)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject(); row.put("conversationId", c.getLong(0));
                row.put("text", c.getString(1)); row.put("clientMessageId", c.getString(2)); result.put(row);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized void markSent(String clientId, long remoteId, long senderId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("remote_id", remoteId); values.put("sender_id", senderId); values.put("pending", 0);
            db.update("messages", values, "client_id=?", new String[]{clientId});
            db.delete("outbox", "client_id=?", new String[]{clientId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void updateMessage(long conversationId, long remoteId, String text) {
        ContentValues values = new ContentValues(); values.put("text", text);
        getWritableDatabase().update("messages", values, "conversation_id=? AND remote_id=?", new String[]{String.valueOf(conversationId), String.valueOf(remoteId)});
    }

    public synchronized void deleteMessage(long conversationId, long remoteId) {
        getWritableDatabase().delete("messages", "conversation_id=? AND remote_id=?", new String[]{String.valueOf(conversationId), String.valueOf(remoteId)});
    }

    public synchronized void clearAccountCache() { getWritableDatabase().delete("conversations", null, null); getWritableDatabase().delete("messages", null, null); getWritableDatabase().delete("outbox", null, null); }
}
