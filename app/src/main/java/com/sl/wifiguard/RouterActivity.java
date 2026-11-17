package com.sl.wifiguard;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * الوضع العام: لوحة إدارة الراوتر داخل التطبيق مع دخول تلقائي بالبيانات المحفوظة.
 * يعمل مع أي راوتر له صفحة ويب، محلياً أو عن بعد (Remote Management / DDNS / VPN).
 */
public class RouterActivity extends BaseActivity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_REMOTE = "remote";
    /** شرح قصير يظهر فوق الصفحة: وين يدوس المستخدم لهذه المهمة. */
    static final String EXTRA_HINT = "hint";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RouterProfile profile;
    private boolean remote;
    private String base = "";
    private String host = "";
    private WebView web;
    private TextView title, status;
    private boolean autoDone, sslAccepted;
    private int basicTries, pageLoads;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        profile = Vault.find(this, String.valueOf(getIntent().getStringExtra(EXTRA_ID)));
        remote = getIntent().getBooleanExtra(EXTRA_REMOTE, false);
        if (profile == null || profile.url(remote).isEmpty()) {
            finish();
            return;
        }
        base = profile.url(remote);
        host = String.valueOf(Uri.parse(base).getHost());

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout head = Ui.column(this);
        int p = Ui.dp(this, 10);
        head.setPadding(p, p, p, p);
        title = Ui.text(this, "", 15, Ui.WHITE, true);
        head.addView(title);
        status = Ui.text(this, base, 11, Ui.MUTED, false);
        status.setSingleLine(true);
        head.addView(status);
        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.button(this, "↻", Ui.CYAN, v -> web.reload()), Ui.weight(this, 3));
        bar.addView(Ui.button(this, "دخول", Ui.GREEN, v -> autoLogin(true, 0)), Ui.weight(this, 3));
        bar.addView(Ui.button(this, "أدوات", Ui.BLUE, v -> tools()), Ui.weight(this, 3));
        bar.addView(Ui.button(this, "✕", Ui.RED, v -> finish()), Ui.weight(this, 3));
        head.addView(bar, Ui.top(this, 8));
        String hint = getIntent().getStringExtra(EXTRA_HINT);
        if (hint != null && !hint.isEmpty()) {
            final TextView h = Ui.text(this, hint + "   ✕", 13, Ui.GOLD, false);
            int hp = Ui.dp(this, 10);
            h.setPadding(hp, hp, hp, hp);
            h.setBackground(Ui.shape(this, 0x22FBBF24, Ui.GOLD, 12));
            h.setOnClickListener(v -> h.setVisibility(View.GONE));
            h.setTextDirection(View.TEXT_DIRECTION_RTL);
            head.addView(h, Ui.top(this, 8));
        }
        root.addView(head);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        setContentView(root);
        updateTitle();
        setupWeb();

        if (base.startsWith("http://") && remote) {
            new AlertDialog.Builder(this)
                    .setTitle("اتصال غير مشفّر")
                    .setMessage("العنوان البعيد يستعمل http، يعني كلمة مرور الراوتر تمر على الإنترنت بدون تشفير. "
                            + "الأفضل استعمال https أو VPN.")
                    .setPositiveButton("متابعة", (d, w) -> web.loadUrl(base))
                    .setNegativeButton("إلغاء", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            web.loadUrl(base);
        }

        if ("auto".equals(profile.vendor)) detectVendor();
    }

    private void updateTitle() {
        title.setText(profile.name + "  •  " + RouterDetect.name(profile.vendor)
                + (remote ? "  •  عن بعد" : "  •  محلي"));
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                status.setText(url);
                // الدخول التلقائي لأول تحميلين فقط (الصفحة ثم تحويلها لصفحة الدخول)، حتى لا تُملأ
                // كلمة مرور الإدارة لاحقاً في حقل آخر مثل كلمة مرور الواي فاي.
                if (++pageLoads > 2) autoDone = true;
                if (!autoDone) autoLogin(false, 0);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String authHost, String realm) {
                // راوترات قديمة تستعمل Basic Auth بدل صفحة دخول.
                if (basicTries++ < 2 && host.equalsIgnoreCase(authHost)) {
                    autoDone = true;
                    h.proceed(profile.username, profile.password);
                } else {
                    h.cancel();
                }
            }

            @Override
            public void onReceivedSslError(WebView v, final SslErrorHandler h, SslError e) {
                if (sslAccepted) {
                    h.proceed();
                    return;
                }
                new AlertDialog.Builder(RouterActivity.this)
                        .setTitle("شهادة غير موثوقة")
                        .setMessage("أغلب الراوترات تستعمل شهادة موقّعة ذاتياً، وهذا طبيعي لراوترك. "
                                + "لا تتابع إذا لم تكن متأكداً أن هذا عنوان راوترك:\n" + host)
                        .setPositiveButton("متابعة", (d, w) -> {
                            sslAccepted = true;
                            h.proceed();
                        })
                        .setNegativeButton("إلغاء", (d, w) -> h.cancel())
                        .setCancelable(false)
                        .show();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    status.setText("تعذّر الاتصال بالراوتر: " + e.getDescription());
                }
            }
        });
    }

    /** يملأ حقول الدخول فقط إذا كانت الصفحة على نفس عنوان الراوتر المحفوظ. */
    private void autoLogin(final boolean manual, final int attempt) {
        String current = web.getUrl();
        if (current == null || !host.equalsIgnoreCase(String.valueOf(Uri.parse(current).getHost()))) {
            if (manual) Ui.toast(this, "الصفحة الحالية ليست على عنوان الراوتر المحفوظ");
            return;
        }
        web.evaluateJavascript(loginScript(), result -> {
            String r = result == null ? "" : result.replace("\"", "");
            if (r.equals("clicked") || r.equals("filled")) {
                autoDone = true;
                if (r.equals("filled")) Ui.toast(this, "تم ملء البيانات، اضغط زر الدخول في الصفحة");
            } else if (!manual && attempt < 2) {
                // بعض اللوحات ترسم نموذج الدخول بعد اكتمال تحميل الصفحة.
                ui.postDelayed(() -> {
                    if (!autoDone && !isFinishing()) autoLogin(false, attempt + 1);
                }, 1300);
            } else if (manual) {
                Ui.toast(this, "لم أجد نموذج دخول في هذه الصفحة");
            }
        });
    }

    private String loginScript() {
        return "(function(u,p){try{"
                + "var all=document.querySelectorAll('input');var pw=null,user=null,n=0;"
                + "for(var i=0;i<all.length;i++){var e=all[i];if(e.offsetParent===null||e.disabled)continue;"
                + "var t=(e.getAttribute('type')||'text').toLowerCase();"
                + "if(t==='password'){n++;if(!pw)pw=e;}"
                + "else if(!pw&&(t==='text'||t==='email'||t==='tel'))user=e;}"
                // أكثر من حقل كلمة مرور = صفحة تغيير كلمة المرور أو الإعداد الأول: لا نلمسها.
                + "if(!pw||n>1)return 'none';"
                + "var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');"
                + "function setv(e,v){e.focus();d.set.call(e,v);"
                + "['input','keyup','change'].forEach(function(k){e.dispatchEvent(new Event(k,{bubbles:true}));});}"
                + "if(user&&u)setv(user,u);setv(pw,p);"
                + "var b=document.querySelector('button[type=submit],input[type=submit],#login-btn,#loginBtn,"
                + "#btnLogin,#loginbtn,#login_btn,#LoginId,#pc-login-btn,.login-btn,.btn-login');"
                + "if(b&&b.offsetParent!==null){b.click();return 'clicked';}"
                + "return 'filled';}catch(x){return 'error';}})("
                + JSONObject.quote(profile.username) + "," + JSONObject.quote(profile.password) + ")";
    }

    private void detectVendor() {
        status.setText("جاري التعرف على نوع الراوتر…");
        new Thread(() -> {
            final String found = RouterDetect.detect(base);
            ui.post(() -> {
                if (isFinishing()) return;
                if (!found.equals("generic")) {
                    profile.vendor = found;
                    try {
                        Vault.put(this, profile);
                    } catch (Exception ignored) {
                    }
                    Ui.toast(this, "تم التعرف: " + RouterDetect.name(found));
                }
                updateTitle();
            });
        }).start();
    }

    private void tools() {
        final boolean tenda = "tenda".equals(profile.vendor);
        String[] items = tenda
                ? new String[]{"دليل: تحديد السرعة وحظر المواقع", "إعادة كشف نوع الراوتر", "تقرير تشخيص (للمطور)", "تحكم مباشر بالأجهزة"}
                : new String[]{"دليل: تحديد السرعة وحظر المواقع", "إعادة كشف نوع الراوتر", "تقرير تشخيص (للمطور)"};
        new AlertDialog.Builder(this)
                .setTitle("أدوات الراوتر")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle(RouterDetect.name(profile.vendor))
                                .setMessage(RouterDetect.guide(profile.vendor))
                                .setPositiveButton("تم", null)
                                .show();
                    } else if (which == 1) {
                        detectVendor();
                    } else if (which == 2) {
                        diagnostics();
                    } else {
                        startActivity(new Intent(this, NativeControlActivity.class)
                                .putExtra(EXTRA_ID, profile.id)
                                .putExtra(EXTRA_REMOTE, remote));
                    }
                })
                .show();
    }

    /**
     * تقرير لأي راوتر: هيكل الصفحة المفتوحة (العنوان، ملفات السكربت، أسماء الحقول والروابط) بدون أي قيم مكتوبة.
     * افتح الصفحة اللي تريد ميزتها (مثلاً قائمة الأجهزة) ثم اطلب التقرير.
     */
    private void diagnostics() {
        String js = "(function(){var o={title:document.title,url:location.href,scripts:[],forms:[],fields:[],links:[],frames:[]};"
                + "function add(a,v){if(v&&a.length<80&&a.indexOf(v)<0)a.push(v);}"
                + "document.querySelectorAll('script[src]').forEach(function(e){add(o.scripts,e.getAttribute('src'));});"
                + "document.querySelectorAll('form').forEach(function(e){add(o.forms,(e.method||'')+' '+(e.getAttribute('action')||''));});"
                + "document.querySelectorAll('input,select,button').forEach(function(e){add(o.fields,(e.type||e.tagName).toLowerCase()+':'+(e.name||e.id||''));});"
                + "document.querySelectorAll('a[href]').forEach(function(e){add(o.links,e.getAttribute('href'));});"
                + "document.querySelectorAll('iframe,frame').forEach(function(e){add(o.frames,e.getAttribute('src'));});"
                + "o.text=(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,1500);"
                + "return JSON.stringify(o);})()";
        web.evaluateJavascript(js, result -> {
            String body;
            try {
                body = new org.json.JSONObject(new org.json.JSONArray("[" + result + "]").getString(0)).toString(1);
            } catch (Exception e) {
                body = String.valueOf(result);
            }
            String report = "WiFi Guard • تقرير تشخيص\nالنوع: " + RouterDetect.name(profile.vendor)
                    + "\nAndroid " + android.os.Build.VERSION.SDK_INT + " • " + (remote ? "remote" : "local")
                    + "\n\n" + body;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(i, "إرسال تقرير التشخيص"));
        });
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            // لا نترك جلسة الراوتر محفوظة بعد الخروج.
            CookieManager.getInstance().removeAllCookies(null);
            web.clearCache(true);
            web.clearFormData();
            web.destroy();
        }
        super.onDestroy();
    }
}
