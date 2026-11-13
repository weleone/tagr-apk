# tagr-apk

Tagr 的安卓 WebView 壳：手机上打开家里的 Tagr 服务器，远程改音乐标签。

- 包名：`com.weleone.tagr`
- 默认服务器：`http://192.168.2.3:8307`（B 机 Tagr）
- 右上角 ⚙ 按钮：服务器管理（点一下切换，长按删除，底部添加服务器）
- 下拉刷新；返回键先回退网页历史
- 移动端布局（不强制桌面版）；黑白主题用 Tagr 页面自带的切换按钮
- 不带下载功能，不带本地改标签（本地改标签不在这个 App 里做）

构建走 GitHub Actions（`build-apk.yml`），产物是 debug APK（未正式签名）。

## 构建

推一个 `v*` tag 或在 Actions 里手动跑 `workflow_dispatch`，产物在 Artifacts 里（`tagr-apk`）。
