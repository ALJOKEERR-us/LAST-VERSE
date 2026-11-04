package com.lastverse.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pure Android Activity. No WebView, HTML, JavaScript or website runtime is used. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 11, 16);
    private static final int PANEL = Color.rgb(19, 24, 35);
    private static final int PANEL_2 = Color.rgb(25, 31, 45);
    private static final int TEXT = Color.rgb(244, 245, 250);
    private static final int MUTED = Color.rgb(155, 164, 181);
    private static final int PURPLE = Color.rgb(139, 124, 255);
    private static final int GREEN = Color.rgb(95, 202, 155);

    private SessionStore session;
    private ApiClient api;
    private LocalDb localDb;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private LinearLayout content;
    private String authMode = "login";
    private String activeTab = "chats";
    private JSONObject currentUser;
    private JSONObject currentConversation;
    private long currentConversationId = -1;
    private JSONObject currentChannel;
    private boolean loading;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (currentUser == null || isFinishing()) return;
            pollActiveScreen();
            pollHandler.postDelayed(this, 30000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        session = new SessionStore(this);
        api = new ApiClient(session);
        localDb = new LocalDb(this);
        if (session.getBaseUrl().isEmpty()) renderServerSetup();
        else if (!session.readCookie().isEmpty()) checkSession();
        else renderAuth();
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentUser != null) startPolling();
    }

    @Override protected void onPause() {
        pollHandler.removeCallbacks(pollTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        pollHandler.removeCallbacks(pollTask);
        io.shutdownNow();
        localDb.close();
        super.onDestroy();
    }

    private void checkSession() {
        request("GET", "/api/me", null, result -> {
            JSONObject user = result.optJSONObject("user");
            if (user == null) { currentUser = null; renderAuth(); }
            else { currentUser = user; startPolling(); renderHome(); }
        }, error -> { renderAuth(); toast("تعذر التحقق من الجلسة. راجع اتصالك بالخادم."); });
    }

    private interface Success { void accept(JSONObject result); }
    private interface Failure { void accept(Exception error); }

    private void request(String method, String path, JSONObject body, Success success, Failure failure) {
        if (loading && method.equals("POST")) { /* allow independent sends; server rate limits writes */ }
        io.execute(() -> {
            try {
                JSONObject result = api.request(method, path, body);
                runOnUiThread(() -> { if (!isFinishing()) success.accept(result); });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isFinishing()) failure.accept(error); });
            }
        });
    }

    private JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); }
        catch (JSONException ignored) { }
        return result;
    }

    private void renderServerSetup() {
        page("إعداد الاتصال", false, false);
        addBranding();
        addText("اتصال آمن بخادم LAST VERSE", 20, TEXT, true, 0, 0, 0, 10);
        addText("التطبيق Android مستقل. اكتب عنوان خادم الـ API الذي يستضيف حساباتك ومحادثاتك. لازم يكون HTTPS.", 14, MUTED, false, 0, 0, 0, 18);
        EditText url = edit("https://your-server.example", InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(session.getBaseUrl());
        content.addView(url);
        addButton("حفظ وفحص الاتصال", true, () -> {
            String value = url.getText().toString().trim().replaceAll("/+$", "");
            if (!value.startsWith("https://")) { toast("استخدم رابط HTTPS آمن."); return; }
            session.setBaseUrl(value);
            request("GET", "/api/health", null, result -> {
                toast("اتصال الخادم ناجح."); renderAuth();
            }, error -> toast(error.getMessage()));
        });
        addText("ملاحظة: التطبيق لا يحتوي على موقع أو خادم مدمج. يلزم وجود خادم API مستقل حتى تعمل الحسابات والمزامنة بين الأجهزة.", 12, MUTED, false, 0, 16, 0, 0);
    }

    private void renderAuth() {
        page("LAST VERSE PRO", false, false);
        addBranding();
        String title = authMode.equals("register") ? "إنشاء حساب جديد" : authMode.equals("recover") ? "استعادة الحساب" : "أهلًا بعودتك";
        addText(title, 23, TEXT, true, 0, 0, 0, 6);
        addText(authMode.equals("recover") ? "استخدم كود الاسترداد الذي حفظته عند إنشاء الحساب أو آخر تغيير لكلمة المرور." : "مساحة محادثاتك الخاصة، مجموعاتك وقنواتك.", 13, MUTED, false, 0, 0, 0, 18);
        EditText username = edit("اسم المستخدم", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        content.addView(username);
        EditText password;
        EditText confirm = null;
        EditText recovery = null;
        if (authMode.equals("recover")) {
            recovery = edit("كود الاسترداد", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            content.addView(recovery);
            password = edit("كلمة المرور الجديدة", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            content.addView(password);
            confirm = edit("تأكيد كلمة المرور الجديدة", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            content.addView(confirm);
        } else {
            password = edit("كلمة المرور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            content.addView(password);
            if (authMode.equals("register")) {
                confirm = edit("تأكيد كلمة المرور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                content.addView(confirm);
            }
        }
        final EditText confirmField = confirm;
        final EditText recoveryField = recovery;
        if (authMode.equals("login")) {
            addButton("تسجيل الدخول", true, () -> {
                hideKeyboard();
                request("POST", "/api/login", object("username", value(username), "password", value(password)), result -> {
                    currentUser = result.optJSONObject("user");
                    startPolling();
                    showHomeAndNotify(result.optJSONObject("securityAlert"));
                }, error -> toast(error.getMessage()));
            });
            addButton("إنشاء حساب", false, () -> { authMode = "register"; renderAuth(); });
            addButton("نسيت كلمة المرور؟", false, () -> { authMode = "recover"; renderAuth(); });
        } else if (authMode.equals("register")) {
            addButton("إنشاء الحساب", true, () -> {
                if (confirmField == null) return;
                request("POST", "/api/register", object("username", value(username), "password", value(password), "confirmPassword", value(confirmField)), result -> {
                    currentUser = result.optJSONObject("user");
                    startPolling();
                    showRecoveryCode(result.optString("recoveryCode"), "تم إنشاء الحساب");
                    renderHome();
                }, error -> toast(error.getMessage()));
            });
            addButton("عندي حساب بالفعل", false, () -> { authMode = "login"; renderAuth(); });
        } else {
            addButton("استعادة الحساب", true, () -> {
                if (confirmField == null || recoveryField == null) return;
                request("POST", "/api/password/recover", object("username", value(username), "recoveryCode", value(recoveryField), "password", value(password), "confirmPassword", value(confirmField)), result -> {
                    currentUser = result.optJSONObject("user");
                    startPolling();
                    showRecoveryCode(result.optString("recoveryCode"), "تم استرداد الحساب");
                    renderHome();
                }, error -> toast(error.getMessage()));
            });
            addButton("العودة لتسجيل الدخول", false, () -> { authMode = "login"; renderAuth(); });
        }
        addText("حماية الحساب: كلمة المرور لا تُحفظ داخل التطبيق، ورمز الجلسة يُشفّر باستخدام Android Keystore.", 12, MUTED, false, 0, 18, 0, 0);
    }

    private void startPolling() {
        pollHandler.removeCallbacks(pollTask);
        syncOutbox();
        pollHandler.postDelayed(pollTask, 30000);
    }

    private void showHomeAndNotify(JSONObject alert) {
        renderHome();
        if (alert != null && alert.optBoolean("unread", true)) toast("تم تسجيل الدخول من جلسة جديدة. راجع تنبيهات الأمان.");
    }

    private void showRecoveryCode(String code, String title) {
        if (code == null || code.isEmpty()) return;
        EditText codeField = new EditText(this);
        codeField.setText(code);
        codeField.setTextIsSelectable(true);
        codeField.setTextColor(TEXT);
        codeField.setTextSize(16);
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage("احفظ كود الاسترداد في مكان آمن خارج التطبيق. يظهر مرة واحدة فقط، ولا تشاركه مع أي شخص.")
                .setView(codeField)
                .setPositiveButton("حفظت الكود", (d, w) -> { })
                .setCancelable(false).show();
    }

    private void pollActiveScreen() {
        if (session.readCookie().isEmpty()) return;
        if (currentConversationId > 0) {
            long id = currentConversationId;
            request("GET", "/api/conversations/" + id + "/messages", null, result -> {
                JSONArray rows = result.optJSONArray("messages");
                if (rows == null || id != currentConversationId) return;
                boolean changed = localDb.messagesChanged(id, rows);
                localDb.cacheMessages(id, rows);
                if (changed && !(getCurrentFocus() instanceof EditText)) renderConversationScreen();
            }, error -> { });
        } else if (activeTab.equals("chats")) {
            request("GET", "/api/conversations", null, result -> {
                JSONArray rows = result.optJSONArray("conversations"); if (rows != null) localDb.cacheConversations(rows);
            }, error -> { });
        }
    }

    private void renderHome() {
        page("LAST VERSE", true, false);
        addText("LΛST VERSE 🖤", 22, TEXT, true, 2, 2, 0, 2);
        String display = currentUser == null ? "" : currentUser.optString("displayName", currentUser.optString("username", ""));
        addText("أهلًا " + display, 13, MUTED, false, 2, 0, 0, 16);
        if (activeTab.equals("chats")) renderConversations();
        else if (activeTab.equals("channels")) renderChannels();
        else if (activeTab.equals("profile")) renderProfile();
        addBottomNav();
    }

    private void renderConversations() {
        addSectionTitle("المحادثات");
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button direct = button("محادثة جديدة", true, this::startNewChat);
        Button group = button("إنشاء مجموعة", false, this::createGroupDialog);
        actions.addView(direct, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, dp(48), 1); gp.setMargins(dp(8), 0, 0, 0);
        actions.addView(group, gp); content.addView(actions);
        JSONArray cached = localDb.getCachedConversations();
        if (cached.length() > 0) addConversationRows(cached);
        request("GET", "/api/conversations", null, result -> {
            JSONArray rows = result.optJSONArray("conversations");
            if (rows != null) { localDb.cacheConversations(rows); if (activeTab.equals("chats") && currentConversationId < 0) { if (content.getChildCount() > 4) content.removeViews(4, content.getChildCount() - 4); addConversationRows(rows); } }
        }, error -> { if (cached.length() == 0) addText("لا توجد محادثات محفوظة بعد. اتصل بالخادم لعرض محادثاتك.", 13, MUTED, false, 0, 16, 0, 0); });
    }

    private void addConversationRows(JSONArray rows) {
        if (rows.length() == 0) { addText("لسه مفيش محادثات. ابدأ محادثة جديدة أو أنشئ مجموعة.", 14, MUTED, false, 0, 18, 0, 0); return; }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject conversation = rows.optJSONObject(i); if (conversation == null) continue;
            JSONObject participant = conversation.optJSONObject("participant");
            String title = conversation.optBoolean("isGroup") ? conversation.optString("name", "مجموعة") : participant == null ? "محادثة" : participant.optString("displayName", participant.optString("username", "مستخدم"));
            JSONObject last = conversation.optJSONObject("lastMessage");
            String preview = last == null ? (conversation.optBoolean("isGroup") ? conversation.optString("description", "مجموعة خاصة") : "ابدأ أول رسالة") : last.optString("text", "");
            String caption = (conversation.optBoolean("isGroup") ? "مجموعة • " + conversation.optInt("memberCount") + " عضو" : "محادثة خاصة") + "\n" + preview;
            addCardRow(title, caption, conversation.optInt("unreadCount"), () -> showConversation(conversation));
        }
    }

    private void startNewChat() {
        EditText query = edit("اكتب اسم المستخدم", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("بدء محادثة").setView(query)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("بحث", (d, w) -> {
                    String term = value(query); if (term.isEmpty()) return;
                    request("GET", "/api/users?q=" + android.net.Uri.encode(term), null, result -> {
                        JSONArray users = result.optJSONArray("users");
                        if (users == null || users.length() == 0) { toast("لم يتم العثور على مستخدم."); return; }
                        String[] names = new String[users.length()];
                        for (int i = 0; i < users.length(); i++) { JSONObject u = users.optJSONObject(i); names[i] = u.optString("displayName", u.optString("username")) + "  (@" + u.optString("username") + ")"; }
                        new AlertDialog.Builder(this).setTitle("اختار المستخدم").setItems(names, (dialog, index) -> {
                            JSONObject chosen = users.optJSONObject(index);
                            request("POST", "/api/conversations", object("userId", chosen.optInt("id")), created -> {
                                JSONObject conv = created.optJSONObject("conversation"); if (conv != null) showConversation(conv);
                            }, error -> toast(error.getMessage()));
                        }).show();
                    }, error -> toast(error.getMessage()));
                }).show();
    }

    private void createGroupDialog() {
        LinearLayout form = formBox();
        EditText name = edit("اسم المجموعة", InputType.TYPE_CLASS_TEXT); form.addView(name);
        EditText description = edit("وصف المجموعة (اختياري)", InputType.TYPE_CLASS_TEXT); form.addView(description);
        EditText usernames = edit("أسماء المستخدمين مفصولة بفاصلة (عضوان على الأقل)", InputType.TYPE_CLASS_TEXT); form.addView(usernames);
        new AlertDialog.Builder(this).setTitle("مجموعة جديدة").setView(form)
                .setNegativeButton("إلغاء", null).setPositiveButton("إنشاء", (d, w) -> {
                    JSONArray names = new JSONArray();
                    for (String value : value(usernames).split(",")) if (!value.trim().isEmpty()) names.put(value.trim());
                    request("POST", "/api/groups", object("name", value(name), "description", value(description), "usernames", names), result -> {
                        JSONObject conv = result.optJSONObject("conversation"); if (conv != null) showConversation(conv);
                    }, error -> toast(error.getMessage()));
                }).show();
    }

    private void showConversation(JSONObject conversation) {
        currentConversation = conversation;
        currentConversationId = conversation.optLong("id", -1);
        renderConversationScreen();
        request("GET", "/api/conversations/" + currentConversationId + "/messages", null, result -> {
            JSONArray messages = result.optJSONArray("messages");
            if (messages != null) { localDb.cacheMessages(currentConversationId, messages); if (currentConversationId == conversation.optLong("id")) renderConversationScreen(); }
        }, error -> toast("تعذر تحديث الرسائل؛ يتم عرض النسخة المحفوظة عند وجودها."));
    }

    private void renderConversationScreen() {
        page("المحادثة", false, true);
        addButton("رجوع للمحادثات", false, () -> { currentConversationId = -1; currentConversation = null; activeTab = "chats"; renderHome(); });
        String title = currentConversation.optBoolean("isGroup") ? currentConversation.optString("name", "مجموعة") : currentConversation.optJSONObject("participant") == null ? "محادثة" : currentConversation.optJSONObject("participant").optString("displayName", "محادثة");
        addText(title, 21, TEXT, true, 0, 10, 0, 2);
        addText(currentConversation.optBoolean("isGroup") ? "مجموعة خاصة • " + currentConversation.optInt("memberCount") + " عضو" : "محادثة خاصة", 12, MUTED, false, 0, 0, 0, 8);
        if (currentConversation.optBoolean("isGroup")) addButton("أعضاء المجموعة وإدارتها", false, this::showGroupMembers);
        else {
            addButton("Echo Seal • صدى مزدوج", false, this::showEchoCapsules);
            JSONObject other = currentConversation.optJSONObject("participant");
            if (other != null) addButton("إبلاغ عن المستخدم", false, () -> openReportDialog(other.optString("username")));
            boolean blocked = currentConversation.optBoolean("blockedByMe");
            addButton(blocked ? "إلغاء حظر المستخدم" : "حظر المستخدم", false, () -> request(blocked ? "DELETE" : "POST", "/api/conversations/" + currentConversationId + "/block", blocked ? null : new JSONObject(), result -> { showConversation(currentConversation); toast(blocked ? "تم إلغاء الحظر." : "تم حظر المستخدم."); }, error -> toast(error.getMessage())));
            if (currentConversation.optBoolean("blockedByOther")) addText("هذا المستخدم حظرك؛ قد لا تتمكن من إرسال الرسائل إليه.", 12, PURPLE, false, 0, 0, 0, 8);
        }
        JSONArray messages = localDb.getMessages(currentConversationId);
        if (messages.length() == 0) addText("لا توجد رسائل بعد. اكتب أول رسالة.", 13, MUTED, false, 0, 12, 0, 0);
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
            boolean mine = currentUser != null && message.optLong("senderId", -1) == currentUser.optLong("id", -2);
            boolean pending = message.optBoolean("pending", false);
            mine = mine || pending;
            addMessageBubble(mine ? "أنت" : "الطرف الآخر", message.optString("text"), pending, message.optLong("id", -1), mine);
        }
        LinearLayout composer = new LinearLayout(this); composer.setOrientation(LinearLayout.HORIZONTAL); composer.setGravity(Gravity.CENTER_VERTICAL);
        EditText messageInput = edit("اكتب رسالة…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.addView(messageInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button send = button("إرسال", true, () -> sendMessage(value(messageInput)));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(82), dp(52)); sendParams.setMargins(dp(7), 0, 0, 0); composer.addView(send, sendParams);
        content.addView(composer);
    }

    private void sendMessage(String text) {
        if (text.trim().isEmpty() || currentConversationId < 1) return;
        long conversationId = currentConversationId;
        String clientId = "local-" + System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0xffffff)).substring(0, 6);
        String created = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date());
        request("POST", "/api/conversations/" + conversationId + "/messages", object("text", text.trim(), "clientMessageId", clientId), result -> {
            JSONObject message = result.optJSONObject("message");
            if (message != null) {
                JSONArray one = new JSONArray(); one.put(message); localDb.cacheMessages(conversationId, one);
                if (currentConversationId == conversationId) renderConversationScreen();
            }
        }, error -> {
            if (error instanceof ApiClient.ApiException) { toast(error.getMessage()); return; }
            localDb.addPending(conversationId, text.trim(), clientId, created, currentUser == null ? 0 : currentUser.optLong("id"));
            toast("اتحفظت الرسالة على الجهاز وهتتحاول تتبعت لما الاتصال يرجع.");
            if (currentConversationId == conversationId) renderConversationScreen();
        });
    }

    private void syncOutbox() {
        if (session.readCookie().isEmpty()) return;
        JSONArray pending = localDb.pendingMessages();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i); if (item == null) continue;
            long conversationId = item.optLong("conversationId"); String clientId = item.optString("clientMessageId");
            request("POST", "/api/conversations/" + conversationId + "/messages", object("text", item.optString("text"), "clientMessageId", clientId), result -> {
                JSONObject message = result.optJSONObject("message");
                localDb.markSent(clientId, message == null ? 0 : message.optLong("id"), currentUser == null ? 0 : currentUser.optLong("id"));
                if (currentConversationId == conversationId) showConversation(currentConversation);
            }, error -> { /* Retain the item for the next connection attempt. */ });
        }
    }

    private void showGroupMembers() {
        long conversationId = currentConversationId;
        request("GET", "/api/conversations/" + conversationId + "/members", null, result -> {
            if (currentConversationId != conversationId) return;
            page("إدارة المجموعة", false, true);
            addButton("رجوع للمجموعة", false, () -> renderConversationScreen());
            addText(result.optString("name", "المجموعة"), 21, TEXT, true, 0, 12, 0, 8);
            addText(result.optString("description", ""), 13, MUTED, false, 0, 0, 0, 12);
            boolean canManage = result.optBoolean("canManage");
            JSONArray members = result.optJSONArray("members");
            if (members != null) for (int i = 0; i < members.length(); i++) {
                JSONObject member = members.optJSONObject(i); if (member == null) continue;
                String memberName = member.optString("displayName", member.optString("username"));
                String role = member.optBoolean("isAdmin") ? "مشرف" : "عضو";
                addCardRow(memberName + " • " + role, "@" + member.optString("username"), 0, () -> {
                    if (canManage && !member.optBoolean("isAdmin")) new AlertDialog.Builder(this).setTitle("إدارة العضو")
                            .setMessage("إزالة " + memberName + " من المجموعة؟")
                            .setNegativeButton("إلغاء", null)
                            .setPositiveButton("إزالة", (d, w) -> request("DELETE", "/api/conversations/" + conversationId + "/members/" + member.optLong("id"), null, done -> showGroupMembers(), error -> toast(error.getMessage())))
                            .show();
                });
            }
            if (canManage) {
                addButton("تعديل اسم ووصف المجموعة", false, () -> {
                    LinearLayout form = formBox();
                    EditText name = edit("اسم المجموعة", InputType.TYPE_CLASS_TEXT); name.setText(result.optString("name")); form.addView(name);
                    EditText description = edit("وصف المجموعة", InputType.TYPE_CLASS_TEXT); description.setText(result.optString("description")); form.addView(description);
                    new AlertDialog.Builder(this).setTitle("تخصيص المجموعة").setView(form).setNegativeButton("إلغاء", null)
                            .setPositiveButton("حفظ", (d, w) -> request("PATCH", "/api/conversations/" + conversationId, object("name", value(name), "description", value(description)), done -> { currentConversation = done.optJSONObject("conversation"); showGroupMembers(); }, error -> toast(error.getMessage()))).show();
                });
                addButton("إضافة عضو", true, () -> {
                    EditText username = edit("اسم المستخدم", InputType.TYPE_CLASS_TEXT);
                    new AlertDialog.Builder(this).setTitle("إضافة عضو").setView(username).setNegativeButton("إلغاء", null)
                            .setPositiveButton("إضافة", (d, w) -> request("POST", "/api/conversations/" + conversationId + "/members", object("username", value(username)), done -> showGroupMembers(), error -> toast(error.getMessage()))).show();
                });
            }
            if (!result.optBoolean("isFounder")) addButton("مغادرة المجموعة", false, () -> new AlertDialog.Builder(this).setMessage("تأكيد مغادرة المجموعة؟").setNegativeButton("إلغاء", null)
                    .setPositiveButton("مغادرة", (d, w) -> request("DELETE", "/api/conversations/" + conversationId + "/members/" + (currentUser == null ? 0 : currentUser.optLong("id")), null, done -> { currentConversationId = -1; activeTab = "chats"; renderHome(); }, error -> toast(error.getMessage()))).show());
            else if (canManage && members != null && members.length() > 1) addButton("نقل ملكية المجموعة", false, () -> {
                String[] names = new String[members.length()];
                for (int i = 0; i < members.length(); i++) { JSONObject m = members.optJSONObject(i); names[i] = m.optString("displayName"); }
                new AlertDialog.Builder(this).setTitle("اختيار المالك الجديد").setItems(names, (d, i) -> {
                    JSONObject selected = members.optJSONObject(i);
                    if (selected.optLong("id") == (currentUser == null ? -1 : currentUser.optLong("id"))) { toast("اختار عضوًا آخر."); return; }
                    request("POST", "/api/conversations/" + conversationId + "/transfer", object("userId", selected.optLong("id")), done -> { currentConversation = done.optJSONObject("conversation"); showGroupMembers(); }, error -> toast(error.getMessage()));
                }).show();
            });
        }, error -> toast(error.getMessage()));
    }

    private void showEchoCapsules() {
        long conversationId = currentConversationId;
        request("GET", "/api/conversations/" + conversationId + "/echo-capsules", null, result -> {
            if (currentConversationId != conversationId) return;
            JSONArray capsules = result.optJSONArray("capsules");
            page("Echo Seal", false, true);
            addButton("رجوع للمحادثة", false, () -> renderConversationScreen());
            addText("صدى مزدوج", 22, TEXT, true, 0, 12, 0, 5);
            addText("رسالة خاصة لا تُفتح إلا بعد أن يرسل الطرفان مساهمتيهما. تنتهي الكبسولة غير المكتملة بعد 7 أيام.", 13, MUTED, false, 0, 0, 0, 12);
            addButton("إنشاء كبسولة جديدة", true, () -> {
                EditText contribution = edit("اكتب مساهمتك الخاصة", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                new AlertDialog.Builder(this).setTitle("كبسولة صدى جديدة").setView(contribution).setNegativeButton("إلغاء", null)
                        .setPositiveButton("إرسال للمشاركة", (d, w) -> request("POST", "/api/conversations/" + conversationId + "/echo-capsules", object("text", value(contribution)), done -> showEchoCapsules(), error -> toast(error.getMessage()))).show();
            });
            if (capsules == null || capsules.length() == 0) addText("لا توجد كبسولات صدى حتى الآن.", 13, MUTED, false, 0, 12, 0, 0);
            else for (int i = 0; i < capsules.length(); i++) {
                JSONObject capsule = capsules.optJSONObject(i); if (capsule == null) continue;
                long capsuleId = capsule.optLong("id");
                String status = capsule.optString("status", "pending");
                boolean mine = capsule.optBoolean("mySubmitted");
                boolean other = capsule.optBoolean("otherSubmitted");
                String statusText = status.equals("unlocked") ? "اكتملت المساهمتان" : status.equals("expired") ? "انتهت الصلاحية" : mine ? "تم إرسال مساهمتك • في انتظار الطرف الآخر" : other ? "الطرف الآخر أرسل مساهمته" : "في انتظار المساهمتين";
                addCardRow("كبسولة صدى #" + capsuleId, statusText, 0, () -> {
                    if (status.equals("pending") && !mine) {
                        EditText contribution = edit("اكتب مساهمتك", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                        new AlertDialog.Builder(this).setTitle("إكمال كبسولة الصدى").setView(contribution).setNegativeButton("إلغاء", null)
                                .setPositiveButton("إرسال", (d, w) -> request("POST", "/api/conversations/" + conversationId + "/echo-capsules/" + capsuleId + "/submit", object("text", value(contribution)), done -> showEchoCapsules(), error -> toast(error.getMessage()))).show();
                    } else if (status.equals("unlocked")) {
                        JSONArray entries = capsule.optJSONArray("entries"); StringBuilder text = new StringBuilder();
                        if (entries != null) for (int n = 0; n < entries.length(); n++) { JSONObject entry = entries.optJSONObject(n); text.append(entry.optString("displayName", "مشارك")).append(": ").append(entry.optString("text", "")).append("\n\n"); }
                        new AlertDialog.Builder(this).setTitle("مساهمات الصدى").setMessage(text.toString()).setPositiveButton("إغلاق", null).show();
                    } else toast(statusText);
                });
            }
        }, error -> toast(error.getMessage()));
    }

    private void renderChannels() {
        addSectionTitle("القنوات");
        addButton("إنشاء قناة", true, this::createChannelDialog);
        request("GET", "/api/channels", null, result -> {
            JSONArray channels = result.optJSONArray("channels");
            if (channels == null || channels.length() == 0) { addText("لا توجد قنوات متاحة حتى الآن.", 13, MUTED, false, 0, 15, 0, 0); return; }
            for (int i = 0; i < channels.length(); i++) {
                JSONObject channel = channels.optJSONObject(i); if (channel == null) continue;
                String caption = "@" + channel.optString("slug") + "\n" + channel.optString("description", "") + "\n" + channel.optInt("postCount") + " منشور";
                addCardRow(channel.optString("name", "قناة"), caption, 0, () -> openChannel(channel));
            }
        }, error -> addText("تعذر تحميل القنوات: " + error.getMessage(), 13, MUTED, false, 0, 15, 0, 0));
    }

    private void createChannelDialog() {
        LinearLayout form = formBox();
        EditText name = edit("اسم القناة", InputType.TYPE_CLASS_TEXT); form.addView(name);
        EditText slug = edit("معرّف إنجليزي مثل last-soul", InputType.TYPE_CLASS_TEXT); form.addView(slug);
        EditText description = edit("وصف القناة", InputType.TYPE_CLASS_TEXT); form.addView(description);
        new AlertDialog.Builder(this).setTitle("إنشاء قناة").setView(form).setNegativeButton("إلغاء", null)
                .setPositiveButton("إنشاء", (d, w) -> request("POST", "/api/channels", object("name", value(name), "slug", value(slug), "description", value(description)), result -> { activeTab = "channels"; renderHome(); }, error -> toast(error.getMessage()))).show();
    }

    private void openChannel(JSONObject channel) {
        long id = channel.optLong("id");
        request("GET", "/api/channels/" + id, null, result -> {
            currentChannel = result.optJSONObject("channel");
            renderChannel(result.optJSONArray("posts"), result.optBoolean("requiresFollow"));
        }, error -> toast(error.getMessage()));
    }

    private void renderChannel(JSONArray posts, boolean requiresFollow) {
        page("القناة", false, true);
        addButton("رجوع للقنوات", false, () -> { activeTab = "channels"; renderHome(); });
        addText(currentChannel.optString("name", "قناة"), 22, TEXT, true, 0, 10, 0, 3);
        addText("@" + currentChannel.optString("slug") + " • " + currentChannel.optString("description"), 13, MUTED, false, 0, 0, 0, 15);
        long channelId = currentChannel.optLong("id");
        String myRole = currentChannel.optString("myRole", "visitor");
        if (requiresFollow) addButton("متابعة القناة لعرض المنشورات", true, () -> request("POST", "/api/channels/" + channelId + "/follow", new JSONObject(), result -> openChannel(currentChannel), error -> toast(error.getMessage())));
        else if (!myRole.equals("owner") && !myRole.equals("visitor")) addButton("إلغاء متابعة القناة", false, () -> request("DELETE", "/api/channels/" + channelId + "/follow", null, result -> { activeTab = "channels"; renderHome(); }, error -> toast(error.getMessage())));
        if (currentChannel.optBoolean("canManage")) addButton("نشر منشور", true, this::createPostDialog);
        if (myRole.equals("owner")) {
            addButton("تعديل القناة", false, this::editChannelDialog);
            addButton("إدارة مشرفي القناة", false, this::manageChannelModerators);
        }
        if (!requiresFollow && posts != null) {
            if (posts.length() == 0) addText("لا توجد منشورات في هذه القناة.", 13, MUTED, false, 0, 12, 0, 0);
            for (int i = 0; i < posts.length(); i++) {
                JSONObject post = posts.optJSONObject(i); if (post == null) continue;
                addCardRow(post.optBoolean("pinned") ? "📌 منشور مثبت" : "منشور", post.optString("text") + "\n" + post.optString("createdAt"), 0, () -> { });
            }
        }
    }

    private void editChannelDialog() {
        LinearLayout form = formBox();
        EditText name = edit("اسم القناة", InputType.TYPE_CLASS_TEXT); name.setText(currentChannel.optString("name")); form.addView(name);
        EditText description = edit("وصف القناة", InputType.TYPE_CLASS_TEXT); description.setText(currentChannel.optString("description")); form.addView(description);
        new AlertDialog.Builder(this).setTitle("تعديل القناة").setView(form).setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d, w) -> request("PATCH", "/api/channels/" + currentChannel.optLong("id"), object("name", value(name), "description", value(description)), result -> openChannel(currentChannel), error -> toast(error.getMessage()))).show();
    }

    private void manageChannelModerators() {
        JSONArray moderators = currentChannel.optJSONArray("moderators");
        String[] names = new String[moderators == null ? 0 : moderators.length()];
        for (int i = 0; i < names.length; i++) { JSONObject member = moderators.optJSONObject(i); names[i] = member.optString("displayName", member.optString("username")); }
        String[] actions = new String[]{"إضافة مشرف", "إزالة مشرف"};
        new AlertDialog.Builder(this).setTitle("مشرفو القناة").setItems(actions, (dialog, which) -> {
            long channelId = currentChannel.optLong("id");
            if (which == 0) {
                EditText username = edit("اسم المستخدم", InputType.TYPE_CLASS_TEXT);
                new AlertDialog.Builder(this).setTitle("إضافة مشرف").setView(username).setNegativeButton("إلغاء", null)
                        .setPositiveButton("بحث وإضافة", (d, w) -> request("GET", "/api/users?q=" + android.net.Uri.encode(value(username)), null, result -> {
                            JSONArray users = result.optJSONArray("users");
                            if (users == null || users.length() == 0) { toast("المستخدم غير موجود."); return; }
                            JSONObject selected = users.optJSONObject(0);
                            request("POST", "/api/channels/" + channelId + "/moderators", object("userId", selected.optLong("id")), done -> openChannel(currentChannel), error -> toast(error.getMessage()));
                        }, error -> toast(error.getMessage()))).show();
            } else if (moderators != null && moderators.length() > 0) {
                new AlertDialog.Builder(this).setTitle("اختار المشرف لإزالته").setItems(names, (d, index) -> {
                    JSONObject selected = moderators.optJSONObject(index);
                    request("DELETE", "/api/channels/" + channelId + "/moderators/" + selected.optLong("id"), null, done -> openChannel(currentChannel), error -> toast(error.getMessage()));
                }).show();
            } else toast("لا يوجد مشرفون لإزالتهم.");
        }).show();
    }

    private void createPostDialog() {
        EditText text = edit("اكتب محتوى المنشور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this).setTitle("منشور جديد").setView(text).setNegativeButton("إلغاء", null)
                .setPositiveButton("نشر", (d, w) -> request("POST", "/api/channels/" + currentChannel.optLong("id") + "/posts", object("text", value(text)), result -> openChannel(currentChannel), error -> toast(error.getMessage()))).show();
    }

    private void openReportDialog(String username) {
        String[] labels = {"مضايقة أو تحرش", "انتحال شخصية", "رسائل مزعجة", "تهديدات", "محتوى غير مناسب", "سبب آخر"};
        String[] values = {"harassment", "impersonation", "spam", "threats", "inappropriate_content", "other"};
        new AlertDialog.Builder(this).setTitle("سبب البلاغ").setItems(labels, (d, index) -> {
            EditText details = edit("اشرح المشكلة في 15 حرفًا على الأقل", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            new AlertDialog.Builder(this).setTitle("تفاصيل البلاغ ضد @" + username).setView(details).setNegativeButton("إلغاء", null)
                    .setPositiveButton("إرسال البلاغ", (dialog, which) -> request("POST", "/api/reports", object("username", username, "category", values[index], "details", value(details)), result -> toast("تم إرسال البلاغ لفريق المراجعة."), error -> toast(error.getMessage()))).show();
        }).show();
    }

    private void showMyReports() {
        request("GET", "/api/reports/mine", null, result -> {
            JSONArray rows = result.optJSONArray("reports");
            page("بلاغاتي", false, true);
            addButton("رجوع للحساب", false, () -> { activeTab = "profile"; renderHome(); });
            if (rows == null || rows.length() == 0) { addText("لم ترسل أي بلاغ حتى الآن.", 13, MUTED, false, 0, 12, 0, 0); return; }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject report = rows.optJSONObject(i); if (report == null) continue;
                addCardRow("بلاغ • " + report.optString("category"), "الحالة: " + report.optString("status") + "\n" + report.optString("createdAt"), 0, () -> {
                    StringBuilder details = new StringBuilder(); JSONArray timeline = report.optJSONArray("timeline");
                    if (timeline != null) for (int n = 0; n < timeline.length(); n++) { JSONObject event = timeline.optJSONObject(n); details.append(event.optString("message")).append("\n"); }
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("تفاصيل البلاغ").setMessage(details.toString()).setPositiveButton("إغلاق", null);
                    if (report.optBoolean("canAppeal")) dialog.setNeutralButton("طلب استئناف", (d, w) -> {
                        EditText explanation = edit("اشرح سبب الاستئناف في 15 حرفًا على الأقل", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                        new AlertDialog.Builder(this).setTitle("استئناف القرار").setView(explanation).setNegativeButton("إلغاء", null)
                                .setPositiveButton("إرسال", (dd, ww) -> request("POST", "/api/reports/" + report.optString("id") + "/appeal", object("explanation", value(explanation)), done -> showMyReports(), error -> toast(error.getMessage()))).show();
                    });
                    dialog.show();
                });
            }
        }, error -> toast(error.getMessage()));
    }

    private void showModerationQueue() {
        request("GET", "/api/moderation/reports", null, result -> {
            JSONArray rows = result.optJSONArray("reports");
            page("مراجعة البلاغات", false, true);
            addButton("رجوع للحساب", false, () -> { activeTab = "profile"; renderHome(); });
            if (rows == null || rows.length() == 0) { addText("لا توجد بلاغات في قائمة المراجعة.", 13, MUTED, false, 0, 12, 0, 0); return; }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject report = rows.optJSONObject(i); if (report == null) continue;
                JSONObject target = report.optJSONObject("target");
                String targetName = target == null ? "حساب" : target.optString("username");
                addCardRow("بلاغ ضد @" + targetName, report.optString("category") + " • " + report.optString("status") + "\n" + report.optString("details"), 0, () -> {
                    String reportId = report.optString("id");
                    request("POST", "/api/moderation/reports/" + reportId + "/claim", new JSONObject(), claimed -> moderationUpdateDialog(reportId, claimed.optJSONObject("report")), error -> toast(error.getMessage()));
                });
            }
        }, error -> toast(error.getMessage()));
    }

    private void moderationUpdateDialog(String reportId, JSONObject report) {
        String status = report == null ? "reviewing" : report.optString("status", "reviewing");
        String[] choices = status.equals("reviewing") ? new String[]{"بدء التحقيق", "اتخاذ إجراء", "إغلاق البلاغ"} : new String[]{"اتخاذ إجراء", "إغلاق البلاغ"};
        String[] values = status.equals("reviewing") ? new String[]{"investigating", "action_taken", "closed"} : new String[]{"action_taken", "closed"};
        new AlertDialog.Builder(this).setTitle("تحديث حالة البلاغ").setItems(choices, (d, which) -> {
            String next = values[which];
            if (next.equals("investigating")) request("PATCH", "/api/moderation/reports/" + reportId, object("status", next), done -> showModerationQueue(), error -> toast(error.getMessage()));
            else {
                EditText decision = edit("اكتب ملخص الإجراء أو القرار (8 أحرف على الأقل)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                new AlertDialog.Builder(this).setTitle("ملخص المراجعة").setView(decision).setNegativeButton("إلغاء", null)
                        .setPositiveButton("حفظ", (dd, ww) -> request("PATCH", "/api/moderation/reports/" + reportId, object("status", next, "decision", value(decision)), done -> showModerationQueue(), error -> toast(error.getMessage()))).show();
            }
        }).show();
    }

    private void renderProfile() {
        addSectionTitle("حسابي");
        String username = currentUser == null ? "" : currentUser.optString("username");
        String displayName = currentUser == null ? "" : currentUser.optString("displayName", username);
        addText(displayName, 23, TEXT, true, 0, 0, 0, 2);
        addText("@" + username, 13, MUTED, false, 0, 0, 0, 16);
        addButton("تعديل الملف الشخصي", false, this::editProfileDialog);
        addButton("تغيير كلمة المرور", false, this::changePasswordDialog);
        addButton("إنشاء كود استرداد جديد", false, this::rotateRecoveryCodeDialog);
        addButton("الأجهزة والجلسات النشطة", false, this::showSessions);
        addButton("تنبيهات الأمان", false, this::showSecurityAlerts);
        addButton("بلاغاتي", false, this::showMyReports);
        if (currentUser != null && (currentUser.optString("role").equals("owner") || currentUser.optString("role").equals("admin") || currentUser.optString("role").equals("report_reviewer")))
            addButton("مراجعة البلاغات • فريق LAST VERSE", false, this::showModerationQueue);
        addButton("تغيير عنوان خادم API", false, this::changeServerDialog);
        addButton("تسجيل الخروج", true, () -> request("POST", "/api/logout", new JSONObject(), result -> {
            session.clearCookie(); localDb.clearAccountCache(); currentUser = null; activeTab = "chats"; authMode = "login"; renderAuth();
        }, error -> { session.clearCookie(); currentUser = null; renderAuth(); }));
    }

    private void editProfileDialog() {
        LinearLayout form = formBox();
        EditText name = edit("الاسم الظاهر", InputType.TYPE_CLASS_TEXT); name.setText(currentUser.optString("displayName")); form.addView(name);
        EditText bio = edit("نبذة قصيرة", InputType.TYPE_CLASS_TEXT); bio.setText(currentUser.optString("bio")); form.addView(bio);
        new AlertDialog.Builder(this).setTitle("تعديل الحساب").setView(form).setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d, w) -> request("PATCH", "/api/profile", object("displayName", value(name), "bio", value(bio)), result -> { currentUser = result.optJSONObject("user"); renderHome(); }, error -> toast(error.getMessage()))).show();
    }

    private void changePasswordDialog() {
        LinearLayout form = formBox();
        EditText old = edit("كلمة المرور الحالية", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(old);
        EditText next = edit("كلمة المرور الجديدة", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(next);
        EditText confirm = edit("تأكيد كلمة المرور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(confirm);
        new AlertDialog.Builder(this).setTitle("تغيير كلمة المرور").setView(form).setNegativeButton("إلغاء", null)
                .setPositiveButton("تغيير", (d, w) -> request("POST", "/api/account/password", object("currentPassword", value(old), "password", value(next), "confirmPassword", value(confirm)), result -> showRecoveryCode(result.optString("recoveryCode"), "احفظ كود الاسترداد الجديد"), error -> toast(error.getMessage()))).show();
    }

    private void rotateRecoveryCodeDialog() {
        EditText current = edit("أكد كلمة المرور الحالية", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("كود الاسترداد").setMessage("سيتم إبطال الكود القديم وإصدار كود جديد.").setView(current).setNegativeButton("إلغاء", null)
                .setPositiveButton("إنشاء كود جديد", (d, w) -> request("POST", "/api/account/recovery-code", object("currentPassword", value(current)), result -> showRecoveryCode(result.optString("recoveryCode"), "كود استرداد جديد"), error -> toast(error.getMessage()))).show();
    }

    private void showSessions() {
        request("GET", "/api/sessions", null, result -> {
            JSONArray rows = result.optJSONArray("sessions"); StringBuilder message = new StringBuilder();
            if (rows != null) for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); message.append(row.optString("deviceLabel", "جهاز")).append(row.optBoolean("current") ? " • هذا الجهاز" : "").append("\n").append(row.optString("createdAt")).append("\n\n"); }
            if (message.length() == 0) message.append("لا توجد جلسات نشطة أخرى.");
            new AlertDialog.Builder(this).setTitle("الجلسات النشطة").setMessage(message.toString())
                    .setNeutralButton("إنهاء الجلسات الأخرى", (d, w) -> request("POST", "/api/sessions/revoke-others", new JSONObject(), done -> toast("تم إنهاء الجلسات الأخرى."), error -> toast(error.getMessage())))
                    .setPositiveButton("إغلاق", null).show();
        }, error -> toast(error.getMessage()));
    }

    private void showSecurityAlerts() {
        request("GET", "/api/security-alerts", null, result -> {
            JSONArray rows = result.optJSONArray("alerts"); StringBuilder message = new StringBuilder();
            if (rows != null) for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); message.append(row.optString("type", "تنبيه أمان")).append("\n").append(row.optString("deviceLabel", "جهاز غير معروف")).append("\n").append(row.optString("createdAt", "")).append("\n\n"); }
            if (message.length() == 0) message.append("لا توجد تنبيهات أمان.");
            new AlertDialog.Builder(this).setTitle("تنبيهات الأمان").setMessage(message.toString()).setPositiveButton("حسنًا", null).show();
        }, error -> toast(error.getMessage()));
    }

    private void changeServerDialog() {
        EditText url = edit("https://your-server.example", InputType.TYPE_TEXT_VARIATION_URI); url.setText(session.getBaseUrl());
        new AlertDialog.Builder(this).setTitle("عنوان خادم API").setMessage("تغيير الخادم قد ينهي الوصول إلى الجلسة الحالية.").setView(url).setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ وفحص", (d, w) -> {
                    String value = value(url).replaceAll("/+$", ""); if (!value.startsWith("https://")) { toast("استخدم HTTPS."); return; }
                    session.setBaseUrl(value); session.clearCookie(); currentUser = null;
                    request("GET", "/api/health", null, ok -> renderAuth(), error -> toast(error.getMessage()));
                }).show();
    }

    private void addBottomNav() {
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL); nav.setPadding(dp(8), dp(8), dp(8), dp(8)); nav.setBackgroundColor(PANEL);
        String[][] items = {{"chats", "المحادثات"}, {"channels", "القنوات"}, {"profile", "حسابي"}};
        for (String[] item : items) {
            Button b = button(item[1], activeTab.equals(item[0]), () -> { activeTab = item[0]; currentConversationId = -1; currentConversation = null; renderHome(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1); params.setMargins(dp(3), 0, dp(3), 0); nav.addView(b, params);
        }
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(62)));
    }

    private void addMessageBubble(String author, String message, boolean pending, long messageId, boolean mine) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = rounded(PANEL_2, 14); card.setBackground(bg);
        TextView title = text(author + (pending ? " • لم تُرسل بعد" : ""), 11, pending ? PURPLE : MUTED, true); card.addView(title);
        TextView body = text(message, 15, TEXT, false); body.setPadding(0, dp(5), 0, 0); card.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(5), 0, dp(5)); content.addView(card, params);
        if (mine && !pending && messageId > 0) card.setOnLongClickListener(v -> { showMessageActions(messageId, message); return true; });
    }

    private void showMessageActions(long messageId, String oldText) {
        new AlertDialog.Builder(this).setTitle("إدارة رسالتك")
                .setItems(new String[]{"تعديل الرسالة", "حذف الرسالة"}, (dialog, which) -> {
                    long conversationId = currentConversationId;
                    if (which == 0) {
                        EditText edit = edit("نص الرسالة", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); edit.setText(oldText);
                        new AlertDialog.Builder(this).setTitle("تعديل الرسالة").setView(edit).setNegativeButton("إلغاء", null)
                                .setPositiveButton("حفظ", (d, w) -> request("PATCH", "/api/conversations/" + conversationId + "/messages/" + messageId, object("text", value(edit)), result -> {
                                    localDb.updateMessage(conversationId, messageId, value(edit)); if (currentConversationId == conversationId) renderConversationScreen();
                                }, error -> toast(error.getMessage()))).show();
                    } else new AlertDialog.Builder(this).setMessage("حذف هذه الرسالة؟").setNegativeButton("إلغاء", null)
                            .setPositiveButton("حذف", (d, w) -> request("DELETE", "/api/conversations/" + conversationId + "/messages/" + messageId, null, result -> {
                                localDb.deleteMessage(conversationId, messageId); if (currentConversationId == conversationId) renderConversationScreen();
                            }, error -> toast(error.getMessage()))).show();
                }).show();
    }

    private void addCardRow(String title, String caption, int unread, Runnable action) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14), dp(13), dp(14), dp(13)); card.setBackground(rounded(PANEL, 16)); card.setClickable(true); card.setFocusable(true); card.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(7), 0, dp(7)); content.addView(card, params);
        LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL); line.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(title, 16, TEXT, true); line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (unread > 0) { TextView badge = text(String.valueOf(unread), 11, Color.WHITE, true); badge.setGravity(Gravity.CENTER); badge.setBackground(rounded(PURPLE, 18)); badge.setPadding(dp(8), dp(4), dp(8), dp(4)); line.addView(badge); }
        card.addView(line);
        TextView detail = text(caption, 12, MUTED, false); detail.setPadding(0, dp(6), 0, 0); card.addView(detail);
    }

    private void page(String title, boolean bottom, boolean back) {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(8), dp(16), dp(0)); setContentView(root);
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text(title, 14, MUTED, true); header.addView(brand, new LinearLayout.LayoutParams(0, dp(42), 1));
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(10), 0, dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void addBranding() {
        TextView logo = text("LΛST VERSE", 29, TEXT, true); logo.setLetterSpacing(0.04f); logo.setPadding(0, dp(30), 0, dp(5)); content.addView(logo);
        addText("PRO  •  PRIVATE SPACE", 10, PURPLE, true, 0, 0, 0, 25);
    }

    private void addSectionTitle(String value) { addText(value, 21, TEXT, true, 0, dp(10), 0, dp(10)); }

    private void addText(String value, int size, int color, boolean bold, int left, int top, int right, int bottom) {
        TextView view = text(value, size, color, bold);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); content.addView(view, params);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }

    private EditText edit(String hint, int type) {
        EditText view = new EditText(this); view.setSingleLine(type != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        view.setHint(hint); view.setHintTextColor(MUTED); view.setTextColor(TEXT); view.setTextSize(14); view.setInputType(type);
        view.setPadding(dp(14), dp(9), dp(14), dp(9)); view.setBackground(rounded(PANEL, 13));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52)); params.setMargins(0, 0, 0, dp(10));
        if (content != null) view.setLayoutParams(params);
        return view;
    }

    private LinearLayout formBox() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(4), dp(6), dp(4), dp(0)); return box; }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(13); button.setTextColor(TEXT);
        button.setBackground(rounded(primary ? PURPLE : PANEL_2, 13)); button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(v -> action.run()); return button;
    }

    private void addButton(String label, boolean primary, Runnable action) {
        Button button = button(label, primary, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.setMargins(0, dp(5), 0, dp(5)); content.addView(button, params);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), Color.argb(35, 180, 190, 220)); return drawable;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private String value(EditText edit) { return edit.getText().toString().trim(); }
    private void toast(String message) { Toast.makeText(this, message == null ? "حدث خطأ." : message, Toast.LENGTH_LONG).show(); }
    private void hideKeyboard() { try { ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0); } catch (Exception ignored) { } }
}
