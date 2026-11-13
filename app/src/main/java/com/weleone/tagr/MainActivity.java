package com.weleone.tagr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PREFS = "tagr_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_SERVERS = "servers";
    private static final String DEFAULT_URL = "http://192.168.1.3:8307";
    private static final String OLD_DEFAULT_URL = "http://192.168.2.3:8307";

    private WebView webView;
    private TextView serverLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 顶栏独占一行 + WebView 在下面填满剩余空间:
        // 之前 ◐/⚙ 是浮在 WebView 右上角的, 会盖住页面自己的右上角按钮
        // (文件夹列表头的 ⋮ 菜单, 里面有重新扫描), 改成不重叠的布局
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 左侧显示当前服务器地址, 点它也能换服务器
        serverLabel = new TextView(this);
        serverLabel.setTextSize(14);
        serverLabel.setSingleLine(true);
        serverLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        serverLabel.setPadding(pad, 0, pad, 0);
        serverLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        serverLabel.setOnClickListener(v -> showServerManager());
        topBar.addView(serverLabel);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("⚙");
        settingsBtn.setTextSize(18);
        settingsBtn.setOnClickListener(v -> showServerManager());
        topBar.addView(settingsBtn);

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
                    Toast.makeText(MainActivity.this, "页面加载失败: " + error.getDescription(), Toast.LENGTH_LONG).show();
                }
            }
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(MainActivity.this, "页面加载失败: " + description, Toast.LENGTH_LONG).show();
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

    private void loadServer(String url) {
        getPrefs().edit().putString(KEY_URL, url).apply();
        updateServerLabel(url);
        webView.loadUrl(url);
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

    private void showServerManager() {
        ArrayList<String> servers = getServers();
        String cur = getPrefs().getString(KEY_URL, DEFAULT_URL);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, servers) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                android.widget.TextView tv = (android.widget.TextView) v;
                String u = getItem(position);
                tv.setText(u + (u.equals(cur) ? "  ✓" : ""));
                return v;
            }
        };
        listView.setAdapter(adapter);
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("服务器管理 (点一下切换, 长按删除)")
                .setView(layout)
                .setNegativeButton("关闭", null)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String url = servers.get(position);
            loadServer(url);
        });
        dialog.show();
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
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
