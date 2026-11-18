package com.weleone.tagr;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.documentfile.provider.DocumentFile;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String PREFS = "tagr_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_SERVERS = "servers";
    private static final String DEFAULT_URL = "http://192.168.1.3:8307";
    private static final String OLD_DEFAULT_URL = "http://192.168.2.3:8307";

    private WebView webView;
    private TextView serverLabel;
    private RelativeLayout topBar;
    private TextView settingsText;
    // 服务器连接状态: url -> true(通)/false(不通)/null(检测中); 当前列表的 adapter, 检测完刷新用
    private final Map<String, Boolean> serverStatus = new HashMap<>();
    private BaseAdapter serverAdapter;
    private AlertDialog serverDialog;
    // 下载到本地（转移弹窗出库页签）: SAF 手动选位置时暂存待下链接
    private static final int REQ_SAVE_FILE = 1001;
    private String pendingUrl;
    private String pendingMime;
    private String pendingFileName;
    // 批量下载（出库多首）：SAF 选文件夹时暂存待下清单
    private static final int REQ_PICK_FOLDER = 1002;
    private ArrayList<BatchFile> pendingBatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 顶栏独占一行 + WebView 在下面填满剩余空间:
        // 之前 ◐/⚙ 是浮在 WebView 右上角的, 会盖住页面自己的右上角按钮
        // (文件夹列表头的 ⋮ 菜单, 里面有重新扫描), 改成不重叠的布局
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏改 RelativeLayout: 地址全宽真正居中 (之前 LinearLayout 里 ⚙ Button 自带最小宽度,
        // 把地址那段压窄了, 居中后也偏左); ⚙ 改窄 TextView 贴右, 不占地址的居中位置
        topBar = new RelativeLayout(this);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 地址全宽、文字居中, 点它也能换服务器; 左右各留齿轮宽度, 长地址不跑到齿轮下面
        serverLabel = new TextView(this);
        serverLabel.setTextSize(14);
        serverLabel.setSingleLine(true);
        serverLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        serverLabel.setGravity(Gravity.CENTER);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        int sidePad = (int) (56 * getResources().getDisplayMetrics().density);
        serverLabel.setPadding(sidePad, pad, sidePad, pad);
        RelativeLayout.LayoutParams labelParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        labelParams.addRule(RelativeLayout.CENTER_VERTICAL);
        serverLabel.setLayoutParams(labelParams);
        serverLabel.setOnClickListener(v -> showServerManager());
        topBar.addView(serverLabel);

        settingsText = new TextView(this);
        settingsText.setText("⚙");
        settingsText.setTextSize(22);
        settingsText.setGravity(Gravity.CENTER);
        settingsText.setPadding(pad, 0, pad, 0);
        RelativeLayout.LayoutParams gearParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        gearParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        gearParams.addRule(RelativeLayout.CENTER_VERTICAL);
        settingsText.setLayoutParams(gearParams);
        settingsText.setOnClickListener(v -> showServerManager());
        topBar.addView(settingsText);

        root.addView(topBar);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        // WebView 填满顶栏下方的剩余空间 (MATCH_PARENT), 页面 100vh 以它为准, 不会塌
        webView.setLayoutParams(new SwipeRefreshLayout.LayoutParams(
                SwipeRefreshLayout.LayoutParams.MATCH_PARENT,
                SwipeRefreshLayout.LayoutParams.MATCH_PARENT));
        SwipeRefreshLayout swipe = new SwipeRefreshLayout(this);
        swipe.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));
        swipe.addView(webView);
        swipe.setOnRefreshListener(() -> {
            webView.reload();
            swipe.setRefreshing(false);
        });
        root.addView(swipe);

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 关掉 WebView 的强制深色: Tagr 页面自带黑白切换, 系统深色再强行加深会把页面渲染成全黑
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            s.setAlgorithmicDarkeningAllowed(false);
        }
        s.setForceDark(WebSettings.FORCE_DARK_OFF);
        // 移动端布局 (不强制桌面版); 黑白主题用页面自带的切换按钮 (◐ 原生按钮已去掉, 重复了)

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showNoServicePage(request.getUrl().toString());
                }
            }
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                String cur = getPrefs().getString(KEY_URL, DEFAULT_URL);
                if (failingUrl != null && failingUrl.equals(cur)) {
                    showNoServicePage(failingUrl);
                }
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完后读主题 (html 有没有 dark class), 并监听 class 变化 (页面内切主题时也跟着变)
                view.evaluateJavascript(
                        "(function(){"
                        + "function bgHex(el){"
                        + "  var bg=getComputedStyle(el).backgroundColor;"
                        + "  var m=bg.match(/[\\d.]+/g);"
                        + "  if(!m) return null;"
                        + "  if(m.length>=4 && parseFloat(m[3])===0) return null;"
                        + "  var hex='#'+[0,1,2].map(function(i){"
                        + "    var h=parseInt(m[i]).toString(16);"
                        + "    return h.length==1?'0'+h:h;}).join('');"
                        + "  return hex;"
                        + "}"
                        + "var report = function(){"
                        + "  if (!window.ThemeBridge) return;"
                        + "  var hex = bgHex(document.body) || bgHex(document.documentElement) || '#FFFFFF';"
                        + "  ThemeBridge.setTheme(hex);"
                        + "};"
                        + "report();"
                        + "new MutationObserver(report).observe(document.documentElement, {attributes:true, attributeFilter:['class']});"
                        + "})()", null);
            }
        });
        webView.addJavascriptInterface(new ThemeBridge(), "ThemeBridge");
        // 出库批量下载的桥：页面把已选歌曲清单一次传进来，APK 只弹一次保存框，
        // 逐个下原文件（不打包 zip）；浏览器里没有这个桥，页面改走 zip 下载
        webView.addJavascriptInterface(new DownloadBridge(), "DownloadBridge");
        // 点页面里的下载链接 (Content-Disposition: attachment), 弹保存位置选择
        // 注意：Tagr 下载接口要登录态，DownloadManager / HttpURLConnection 都要带上
        // WebView 的 Cookie，不然会被鉴权拦下
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                startDownloadChoice(url, userAgent, contentDisposition, mimetype);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                android.util.Log.d("TagrWeb", msg.message() + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")");
                return true;
            }
        });

        // 首次启动直接进默认服务器 (之前是先弹服务器管理, 不选就一直空白)
        // 老默认 192.168.2.3 已保存过的也迁移到新默认 192.168.1.3
        String url = getPrefs().getString(KEY_URL, null);
        if (url == null) {
            url = DEFAULT_URL;
            getPrefs().edit().putString(KEY_URL, url).apply();
        } else if (url.equals(OLD_DEFAULT_URL)) {
            url = DEFAULT_URL;
            getPrefs().edit().putString(KEY_URL, url).apply();
        }
        updateServerLabel(url);
        webView.loadUrl(url);
    }

    // 顶栏底色跟页面真实底色走 (不是写死的 #1E1E1E): 页面纯黑时顶栏也纯黑,
    // 浅色时也一样, 不再露出一道缝; 文字颜色按底色亮度自动选白/黑
    private void applyTheme(String bgColor) {
        runOnUiThread(() -> {
            try {
                int bg = Color.parseColor(bgColor);
                if (topBar != null) {
                    topBar.setBackgroundColor(bg);
                }
                double lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg);
                int text = lum < 128 ? Color.WHITE : Color.BLACK;
                if (serverLabel != null) {
                    serverLabel.setTextColor(text);
                }
                if (settingsText != null) {
                    settingsText.setTextColor(text);
                }
            } catch (Exception e) {
                // 颜色解析失败就保持现状
            }
        });
    }

    private class ThemeBridge {
        @JavascriptInterface
        public void setTheme(String bgColor) {
            applyTheme(bgColor);
        }
    }

    // 批量下载的一首歌：下载地址 + 要保存的文件名
    private static class BatchFile {
        final String url;
        final String fileName;
        BatchFile(String url, String fileName) {
            this.url = url;
            this.fileName = fileName;
        }
    }

    // 页面调的批量下载桥：downloadFiles('[{"url":"...","fileName":"..."}, ...]')
    private class DownloadBridge {
        @JavascriptInterface
        public void downloadFiles(String json) {
            ArrayList<BatchFile> files = new ArrayList<>();
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.getJSONObject(i);
                    String url = obj.optString("url", "");
                    String name = obj.optString("fileName", "");
                    if (!url.isEmpty() && !name.isEmpty()) files.add(new BatchFile(url, name));
                }
            } catch (Exception ignored) {}
            if (files.isEmpty()) return;
            runOnUiThread(() -> showBatchDownloadChoice(files));
        }
    }

    // 批量下载只弹一次保存框：默认下载目录 / 选文件夹；两路都带 WebView 的 Cookie
    // 框里列出要下的文件名，确认前能看到是哪几首
    private void showBatchDownloadChoice(ArrayList<BatchFile> files) {
        StringBuilder msg = new StringBuilder();
        int shown = Math.min(files.size(), 10);
        for (int i = 0; i < shown; i++) {
            if (i > 0) msg.append("\n");
            msg.append(files.get(i).fileName);
        }
        if (files.size() > shown) msg.append("\n……等 ").append(files.size()).append(" 首");
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("保存到手机哪里?")
                .setMessage(msg.toString())
                .setPositiveButton("默认下载目录", (d, w) -> {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    for (BatchFile f : files) {
                        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(f.url));
                        req.setMimeType("audio/mpeg");
                        String cookie = CookieManager.getInstance().getCookie(f.url);
                        if (cookie != null && !cookie.isEmpty()) req.addRequestHeader("Cookie", cookie);
                        req.setTitle(f.fileName);
                        req.setDescription("Tagr 下载");
                        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.fileName);
                        dm.enqueue(req);
                    }
                    Toast.makeText(MainActivity.this, "已开始下载 " + files.size() + " 首歌到手机下载目录", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("选文件夹", (d, w) -> {
                    pendingBatch = files;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, REQ_PICK_FOLDER);
                })
                .show();
    }

    // 批量下载写进 SAF 文件夹：逐个 HttpURLConnection（带 Cookie）拉下来写进去
    private void downloadBatchToFolder(Uri treeUri, ArrayList<BatchFile> files) {
        new Thread(() -> {
            DocumentFile dir = DocumentFile.fromTreeUri(MainActivity.this, treeUri);
            if (dir == null || !dir.canWrite()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "文件夹不可写", Toast.LENGTH_LONG).show());
                return;
            }
            int ok = 0;
            for (BatchFile f : files) {
                try {
                    DocumentFile out = dir.createFile("audio/mpeg", f.fileName);
                    if (out == null) continue;
                    HttpURLConnection conn = (HttpURLConnection) new URL(f.url).openConnection();
                    String cookie = CookieManager.getInstance().getCookie(f.url);
                    if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);
                    conn.connect();
                    try (InputStream in = conn.getInputStream();
                         OutputStream outStream = getContentResolver().openOutputStream(out.getUri())) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) outStream.write(buf, 0, n);
                    }
                    ok++;
                } catch (Exception ignored) {}
            }
            int done = ok;
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "已保存 " + done + " / " + files.size() + " 首歌", Toast.LENGTH_LONG).show());
        }).start();
    }

    // 服务器不通时显示的专设页面: “当前地址无服务” + 地址 + 右上角重选服务器的提示
    private void showNoServicePage(String url) {
        String safe = url == null ? "" : url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:sans-serif;background:#fff;color:#888;}h1{font-size:26px;color:#666;margin:0 0 10px;text-align:center;padding:0 24px;}p{font-size:14px;margin:4px 0;text-align:center;padding:0 24px;}</style></head>"
                + "<body><h1>当前地址无服务</h1>"
                + "<p>" + safe + "</p>"
                + "<p>点右上角 ⚙ 重新选择服务器</p>"
                + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void loadServer(String url) {
        getPrefs().edit().putString(KEY_URL, url).apply();
        updateServerLabel(url);
        Boolean st = serverStatus.get(url);
        if (st != null && !st) {
            // 已知不通 (红×), 直接显示无服务页, 不用等加载超时
            showNoServicePage(url);
        } else {
            webView.loadUrl(url);
        }
    }

    // 顶栏左侧只显示裸地址 (不加“服务器:”前缀, 地址本身就是信息, 加前缀多余)
    private void updateServerLabel(String url) {
        if (serverLabel == null || url == null) return;
        String shown = url.replaceFirst("^https?://", "");
        serverLabel.setText(shown);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private ArrayList<String> getServers() {
        Set<String> set = getPrefs().getStringSet(KEY_SERVERS, null);
        ArrayList<String> list = new ArrayList<>();
        if (set != null) list.addAll(set);
        String cur = getPrefs().getString(KEY_URL, null);
        if (cur != null && !list.contains(cur)) list.add(0, cur);
        if (list.isEmpty()) list.add(DEFAULT_URL);
        return list;
    }

    private void saveServers(ArrayList<String> list) {
        getPrefs().edit().putStringSet(KEY_SERVERS, new LinkedHashSet<>(list)).apply();
    }

    // 检测服务器连通性: GET 根地址, 2xx/3xx 算通, 超时或异常算不通; 结果回主线程刷新列表
    private void checkServer(String url) {
        serverStatus.put(url, null);
        new Thread(() -> {
            boolean ok = false;
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                int code = conn.getResponseCode();
                ok = code >= 200 && code < 400;
            } catch (Exception e) {
                ok = false;
            } finally {
                if (conn != null) conn.disconnect();
            }
            boolean result = ok;
            runOnUiThread(() -> {
                serverStatus.put(url, result);
                if (serverAdapter != null) serverAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    // 服务器列表行: [圆点选择] [地址] [连接状态 ✓绿/×红/…检测中]
    private class ServerAdapter extends BaseAdapter {
        private final ArrayList<String> servers;
        private final String cur;
        ServerAdapter(ArrayList<String> servers, String cur) {
            this.servers = servers;
            this.cur = cur;
        }
        @Override public int getCount() { return servers.size(); }
        @Override public String getItem(int pos) { return servers.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            String url = servers.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int vpad = (int) (8 * getResources().getDisplayMetrics().density);
            row.setPadding(0, vpad, 0, vpad);

            RadioButton radio = new RadioButton(MainActivity.this);
            radio.setChecked(url.equals(cur));
            radio.setClickable(false);
            radio.setFocusable(false);
            row.addView(radio);

            TextView urlTv = new TextView(MainActivity.this);
            urlTv.setText(url);
            urlTv.setSingleLine(true);
            urlTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            urlTv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(urlTv);

            TextView statusTv = new TextView(MainActivity.this);
            statusTv.setTextSize(18);
            Boolean st = serverStatus.get(url);
            if (st == null) {
                statusTv.setText("…");
                statusTv.setTextColor(Color.GRAY);
            } else if (st) {
                statusTv.setText("✓");
                statusTv.setTextColor(Color.parseColor("#22AA55"));
            } else {
                statusTv.setText("×");
                statusTv.setTextColor(Color.RED);
            }
            row.addView(statusTv);
            return row;
        }
    }

    private void showServerManager() {
        if (serverDialog != null && serverDialog.isShowing()) serverDialog.dismiss();
        ArrayList<String> servers = getServers();
        String cur = getPrefs().getString(KEY_URL, DEFAULT_URL);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        ListView listView = new ListView(this);
        serverAdapter = new ServerAdapter(servers, cur);
        listView.setAdapter(serverAdapter);
        layout.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 长按删除
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除服务器")
                    .setMessage(url)
                    .setPositiveButton("删除", (d, w) -> {
                        servers.remove(position);
                        serverStatus.remove(url);
                        saveServers(servers);
                        if (url.equals(cur) && !servers.isEmpty()) {
                            loadServer(servers.get(0));
                        }
                        showServerManager();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        Button addBtn = new Button(this);
        addBtn.setText("添加服务器");
        addBtn.setOnClickListener(v -> showAddServer(servers));
        layout.addView(addBtn);

        serverDialog = new AlertDialog.Builder(this)
                .setTitle("服务器管理 (点圆点或点行切换, 长按删除)")
                .setView(layout)
                .setNegativeButton("关闭", null)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            loadServer(url);
            if (serverDialog != null) serverDialog.dismiss();
        });
        serverDialog.show();
        // 打开就测全部服务器的连通性
        for (String url : servers) checkServer(url);
    }

    private void showAddServer(ArrayList<String> servers) {
        final EditText input = new EditText(this);
        input.setText("http://");
        input.setSelection(input.getText().length());
        input.setHint("http://192.168.2.3:8307 或外网地址");
        new AlertDialog.Builder(this)
                .setTitle("添加服务器")
                .setView(input)
                .setPositiveButton("添加并连接", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        if (!url.startsWith("http")) url = "http://" + url;
                        if (!servers.contains(url)) servers.add(url);
                        saveServers(servers);
                        loadServer(url);
                        // 重新打开服务器管理, 新加的服务器出现并立即测连通性
                        showServerManager();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 下载到本地：弹保存位置选择（仿汽水 APK）
    // 默认下载目录走 DownloadManager，手动选位置走 SAF；两路都要带 WebView 的 Cookie，
    // 因为 /api/songs/[id]/download 要登录态，不带会被鉴权拦下
    // 文件名：先从 Content-Disposition 的 filename*=UTF-8''... 里解（RFC 5987），
    // 因为 URLUtil.guessFileName 不认 filename*，会把 /api/songs/123/download
    // 认成 download.bin（URL 末段 download + octet-stream 后缀）
    private String parseFileName(String url, String contentDisposition, String mimetype) {
        if (contentDisposition != null) {
            int i = contentDisposition.indexOf("filename*=");
            if (i >= 0) {
                String rest = contentDisposition.substring(i + "filename*=".length()).trim();
                int q = rest.indexOf("''");
                if (q >= 0) {
                    String encoded = rest.substring(q + 2).trim();
                    int end = encoded.indexOf('"');
                    if (end >= 0) encoded = encoded.substring(0, end);
                    end = encoded.indexOf(';');
                    if (end >= 0) encoded = encoded.substring(0, end);
                    try {
                        String decoded = java.net.URLDecoder.decode(encoded, "UTF-8");
                        if (decoded != null && !decoded.isEmpty()) return decoded;
                    } catch (Exception ignored) {}
                }
            }
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
        // 服务器头会把 "-.mp3; filename*=UTF-8''..." 当成文件名, 这里兜底解出来
        if (fileName != null && (fileName.contains(";") || fileName.contains("filename"))) {
            int j = fileName.indexOf("UTF-8''");
            if (j >= 0) {
                try { fileName = java.net.URLDecoder.decode(fileName.substring(j + 7), "UTF-8"); }
                catch (Exception ignored) { fileName = null; }
            } else {
                fileName = null;
            }
        }
        return fileName;
    }

    private void startDownloadChoice(String url, String userAgent, String contentDisposition, String mimetype) {
        String fileName = parseFileName(url, contentDisposition, mimetype);
        if (fileName == null || fileName.isEmpty()) {
            fileName = Uri.parse(url).getLastPathSegment();
            if (fileName == null) fileName = "tagr.mp3";
        }
        final String fUrl = url;
        final String fMime = mimetype;
        final String fAgent = userAgent;
        final String fName = fileName;
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("保存到手机哪里?")
                .setMessage(fName)
                .setPositiveButton("默认下载目录", (d, w) -> {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(fUrl));
                    req.setMimeType(fMime);
                    if (fAgent != null && !fAgent.isEmpty()) req.addRequestHeader("User-Agent", fAgent);
                    String cookie = CookieManager.getInstance().getCookie(fUrl);
                    if (cookie != null && !cookie.isEmpty()) req.addRequestHeader("Cookie", cookie);
                    req.setTitle(fName);
                    req.setDescription("Tagr 下载");
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fName);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    Toast.makeText(MainActivity.this, "已开始下载到手机下载目录: " + fName, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("手动选位置", (d, w) -> {
                    pendingUrl = fUrl;
                    pendingMime = fMime;
                    pendingFileName = fName;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(fMime != null && !fMime.isEmpty() ? fMime : "audio/mpeg");
                    intent.putExtra(Intent.EXTRA_TITLE, fName);
                    startActivityForResult(intent, REQ_SAVE_FILE);
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            // 批量下载选文件夹：一次选好，逐个写进去
            Uri treeUri = data.getData();
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            ArrayList<BatchFile> batch = pendingBatch;
            pendingBatch = null;
            if (batch != null && !batch.isEmpty()) downloadBatchToFolder(treeUri, batch);
            return;
        }
        if (requestCode == REQ_SAVE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri dest = data.getData();
            String url = pendingUrl;
            String name = pendingFileName;
            new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);
                    conn.connect();
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = getContentResolver().openOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "已保存到手机: " + name, Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
