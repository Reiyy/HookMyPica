package com.yareiy.hookmypica;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.json.JSONObject;

@SuppressWarnings("RedundantThrows")
public class MainHook implements IXposedHookLoadPackage {
    private String cacheDir;
    private String imagePath;
    private String blurImagePath;
    private String cachedImageName;
    private String cachedBlurImageName;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("handleLoadPackage: " + lpparam.processName + ", " + lpparam.processName);

        cacheDir = "/sdcard/Android/data/com.yareiy.mypica/cache/LaunchImage";
        imagePath = cacheDir + "/splash_bg_1.jpg";
        blurImagePath = cacheDir + "/splash_bg_1_blur.jpg";

        XposedBridge.log("Cache dir: " + cacheDir);
        XposedBridge.log("Image path: " + imagePath);
        XposedBridge.log("Blur image path: " + blurImagePath);

        // Hook Picasso 加载动态启动图
        XposedHelpers.findAndHookMethod(
            "com.squareup.picasso.Picasso",
            lpparam.classLoader,
            "load",
            int.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int resId = (int) param.args[0];
                    Object picassoInstance = param.thisObject;
                    Context context = null;
                    try {
                        // 尝试从 Picasso 实例中获取 Context（Picasso 内部通常会持有）
                        context = (Context) XposedHelpers.getObjectField(picassoInstance, "context");
                    } catch (Throwable t) {
                        XposedBridge.log("Failed to get context from Picasso instance: " + t.getMessage());
                    }
                    if (context == null) {
                        // 如果无法获取，使用当前应用
                        context = android.app.AndroidAppHelper.currentApplication();
                    }
                    
                    String resName = "";
                    try {
                        resName = context.getResources().getResourceEntryName(resId);
                    } catch (Throwable t) {
                        XposedBridge.log("Failed to get resource entry name for resId " + resId + ": " + t.getMessage());
                    }
                    XposedBridge.log("Picasso.load(int) called with resId: " + resId + ", resource name: " + resName);
                    
                    // 如果资源名称为 "splash_bg_1" 或 "splash_bg_1_blur"，则尝试替换
                    if ("splash_bg_1".equals(resName) || "splash_bg_1_blur".equals(resName)) {
                        File imageFile = new File("splash_bg_1".equals(resName) ? imagePath : blurImagePath);
                        XposedBridge.log("Matching resource found. File path: " + imageFile.getAbsolutePath() +
                                           ", exists: " + imageFile.exists());
                        if (imageFile.exists()) {
                            String fileUrl = "file://" + imageFile.getAbsolutePath();
                            XposedBridge.log("Replacing image with fileUrl: " + fileUrl);
                            // 调用 load(String) 并返回新的 RequestCreator 对象
                            Object newRequestCreator = XposedHelpers.callMethod(picassoInstance, "load", fileUrl);
                            param.setResult(newRequestCreator);
                        } else {
                            XposedBridge.log("Image file does not exist. Using original resource.");
                        }
                    }
                }
            }
        );

        XposedHelpers.findAndHookMethod(
                "com.picacomic.fregata.utils.views.PopupWebview",
                lpparam.classLoader,
                "init",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("beforeHookedMethod: PopupWebview.init(Context)");
                        param.setResult(null);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.picacomic.fregata.utils.views.BannerWebview",
                lpparam.classLoader,
                "init",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("beforeHookedMethod: BannerWebview.init(Context)");
                        param.setResult(null);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.picacomic.fregata.activities.MainActivity",
                lpparam.classLoader,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("afterHookedMethod: MainActivity.onCreate(Bundle)");
                        // 取消首页广告移除，保留移除游戏选项
                        View[] buttons_tabbar = (View[]) XposedHelpers.getObjectField(param.thisObject, "buttons_tabbar");
                        buttons_tabbar[2].setVisibility(View.GONE);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.picacomic.fregata.adapters.ComicPageRecyclerViewAdapter",
                lpparam.classLoader,
                "onCreateViewHolder",
                ViewGroup.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("afterHookedMethod: ComicPageRecyclerViewAdapter.onCreateViewHolder");
                        Object result = param.getResult();
                        if (!TextUtils.equals(result.getClass().getName(), "com.picacomic.fregata.holders.AdvertisementListViewHolder")) {
                            return;
                        }
                        View webView_ads = (View) XposedHelpers.getObjectField(result, "itemView");
                        webView_ads.setVisibility(View.GONE);
                        Object lp = XposedHelpers.newInstance(XposedHelpers.findClass("android.support.v7.widget.RecyclerView$LayoutParams", lpparam.classLoader), 0, 0);
                        webView_ads.setLayoutParams((ViewGroup.LayoutParams) lp);
                    }
                });
        XposedHelpers.findAndHookMethod("com.picacomic.fregata.adapters.ComicListRecyclerViewAdapter", lpparam.classLoader, "onBindViewHolder", XposedHelpers.findClass("android.support.v7.widget.RecyclerView$ViewHolder", lpparam.classLoader), int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object viewHolder = param.args[0];
                if (viewHolder.getClass().getSimpleName().equals("AdvertisementListViewHolder")) {
                    param.setResult(null);
                    View itemView = (View) XposedHelpers.getObjectField(viewHolder, "itemView");
                    ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) itemView.getLayoutParams();
                    // 完全隐藏会影响分页加载的逻辑，所以保留一点，
                    lp.height = 1;
                    itemView.setLayoutParams(lp);
                }
            }
        });
        // 跳过分流选择页面并自动选择分流1
        XposedHelpers.findAndHookMethod(
                "com.picacomic.fregata.activities.SplashActivity",
                lpparam.classLoader,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object splashActivity = param.thisObject;

                        // 使用反射查找 Button 类
                        Class<?> buttonClass = XposedHelpers.findClass("android.widget.Button", lpparam.classLoader);
                        
                        // 获取分流1按钮
                        Object button_server1 = XposedHelpers.getObjectField(splashActivity, "button_server1");

                        // 点击分流1
                        XposedHelpers.callMethod(button_server1, "performClick");

                        // 结束 SplashActivity
                        XposedHelpers.callMethod(splashActivity, "finish");
                    }
                });
    }
    // == 通过 API 获取最新的启动图 URL，并缓存 ==
    private void fetchAndUpdateImages() {
        new Thread(() -> {
            try {
                URL url = new URL("https://picaapi.reiyy.com:2333/GetLaunchImage");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);

                InputStream is = conn.getInputStream();
                StringBuilder response = new StringBuilder();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    response.append(new String(buffer, 0, len));
                }
                is.close();

                JSONObject json = new JSONObject(response.toString());
                String newImageName = json.getString("image_name");
                String newBlurImageName = json.getString("blur_image_name");
                String newImageUrl = json.getString("image_url");
                String newBlurImageUrl = json.getString("blur_image_url");

                if (!newImageName.equals(cachedImageName)) {
                    downloadFile(newImageUrl, imagePath);
                    cachedImageName = newImageName;
                }
                if (!newBlurImageName.equals(cachedBlurImageName)) {
                    downloadFile(newBlurImageUrl, blurImagePath);
                    cachedBlurImageName = newBlurImageName;
                }

                XposedBridge.log("Updated images: " + imagePath + " & " + blurImagePath);
            } catch (Exception e) {
                XposedBridge.log("Error fetching image URLs: " + e.getMessage());
            }
        }).start();
    }

    // == 下载文件到本地 ==
    private void downloadFile(String urlStr, String outputPath) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(new File(outputPath));
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.close();
            is.close();
            XposedBridge.log("File downloaded: " + outputPath);
        } catch (Exception e) {
            XposedBridge.log("Error downloading image: " + e.getMessage());
        }
    }
}

    

