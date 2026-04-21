package com.yareiy.hookmypica;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.app.Dialog;
import android.widget.Button;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Environment;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.app.Application;
import android.content.res.XResources;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.FileNotFoundException;
import java.net.SocketTimeoutException;
import java.net.MalformedURLException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import org.json.JSONObject;
import org.json.JSONException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;

@SuppressWarnings("RedundantThrows")
public class MainHook implements IXposedHookLoadPackage, IXposedHookInitPackageResources {
    private String cacheDir;
    private String imagePath;
    private String blurImagePath;
    private String cachedImageName;
    private String cachedBlurImageName;
    private int clickCount = 0; // 点击次数记录

    private static final String OLD_URL = "https://picaapi.picabridgeapiexample.com:2333/";
    private static final String CONFIG_PATH = "Android/data/com.yareiy.mypica/ApiConfig.txt";
    private static final String CONFIG_FILE_PATH = "/sdcard/Android/data/com.yareiy.mypica/FilterKeywords.json";

    private static JSONObject cachedFilterConfig = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("handleLoadPackage: " + lpparam.processName + ", " + lpparam.processName);

        // 动态启动图缓存路径
        cacheDir = "/sdcard/Android/data/com.yareiy.mypica/cache/LaunchImage";
        imagePath = cacheDir + "/splash_bg_1.jpg";
        blurImagePath = cacheDir + "/splash_bg_1_blur.jpg";

        // 创建动态启动图路径
        File cacheDirectory = new File(cacheDir);
        if (!cacheDirectory.exists()) {
            boolean created = cacheDirectory.mkdirs();
            if (!created) {
                XposedBridge.log("Failed to create cache directory: " + cacheDir);
            }
        }

        // XposedBridge.log("Cache dir: " + cacheDir);
        // XposedBridge.log("Image path: " + imagePath);
        // XposedBridge.log("Blur image path: " + blurImagePath);


        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        fetchAndSaveConfigAsync();
                    }
                });

        // Hook登录页，添加logo点击事件
        Class<?> clazz = XposedHelpers.findClassIfExists(
                "com.picacomic.fregata.fragments.LoginFragment",
                lpparam.classLoader
        );

        if (clazz != null) {
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "bH",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final Object fragmentInstance = param.thisObject;
                            final Context context = (Context) XposedHelpers.callMethod(fragmentInstance, "getContext");

                            final ImageView logo = (ImageView) XposedHelpers.getObjectField(fragmentInstance, "imageView_logo");

                            if (logo != null) {
                                logo.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        clickCount++;
                                        if (clickCount >= 5) {
                                            clickCount = 0;
                                            showConfigDialog(context);
                                        }
                                    }
                                });
                                //XposedBridge.log("[HookMyPica] 成功Hook启动页logo");
                            }
                        }
                    }
            );
        } else {
            XposedBridge.log("[HookMyPica] LoginFragment 不存在");
        }

        // 替换API地址
        XposedHelpers.findAndHookMethod(
                "retrofit2.Retrofit$Builder",
                lpparam.classLoader,
                "baseUrl",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String url = (String) param.args[0];

                        // 只拦截目标URL
                        if (url != null && url.equals(OLD_URL)) {
                            String customUrl = getUrlFromConfig();

                            if (customUrl != null) {
                                // 配置文件存在，使用配置文件URL替换
                                param.args[0] = customUrl;
                                //XposedBridge.log("[HookMyPica] 已读取配置，替换URL: " + OLD_URL + " → " + customUrl);
                            } else {
                                // 如果未配置，使用原始URL并弹出提示
                                XposedBridge.log("[HookMyPica] 未找到配置文件，使用原始URL");
                                showToastOnUI("哔咔桥API未配置！打哔咔五次进行配置");
                            }
                        }
                    }
                }
        );

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
                    // XposedBridge.log("Picasso.load(int) called with resId: " + resId + ", resource name: " + resName);
                    
                    // 如果资源名称为 "splash_bg_1" 或 "splash_bg_1_blur"，则尝试替换
                    if ("splash_bg_1".equals(resName) || "splash_bg_1_blur".equals(resName)) {
                        File imageFile = new File("splash_bg_1".equals(resName) ? imagePath : blurImagePath);
                        // XposedBridge.log("Matching resource found. File path: " + imageFile.getAbsolutePath() +
                        //                    ", exists: " + imageFile.exists());
                        if (imageFile.exists()) {
                            String fileUrl = "file://" + imageFile.getAbsolutePath();
                            // XposedBridge.log("Replacing image with fileUrl: " + fileUrl);
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
        // 修改官方下载返回地址
        XposedHelpers.findAndHookMethod(
            "com.picacomic.fregata.utils.g", 
            lpparam.classLoader, 
            "aC", 
            String.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    String configUrl = getUrlFromConfig();
                    return (configUrl != null) ? configUrl : OLD_URL;
                }
            }
        );

        // 修改屏蔽按钮文本
        XposedHelpers.findAndHookMethod("com.picacomic.fregata.fragments.ComicListFragment", lpparam.classLoader, "bH", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object fragmentInstance = param.thisObject;
                        Button[] buttonsFilters = (Button[]) XposedHelpers.getObjectField(fragmentInstance, "buttons_filters");
                        
                        if (buttonsFilters == null) return;

                        JSONObject config = loadLocalConfig();
                        if (config != null && config.has("FilterKeywords")) {
                            JSONObject keywordsObj = config.getJSONObject("FilterKeywords");
                            
                            // 遍历8个按钮，修改UI文本
                            for (int i = 0; i < buttonsFilters.length; i++) {
                                String id = String.valueOf(i + 1);
                                if (keywordsObj.has(id)) {
                                    JSONArray itemArray = keywordsObj.getJSONArray(id);
                                    if (itemArray.length() > 0) {
                                        String uiName = itemArray.getString(0); //第一个参数是UI名称
                                        buttonsFilters[i].setText(uiName);
                                    }
                                }
                            }
                        }
                    }
                });


        try {
            XposedHelpers.findAndHookConstructor(
                    "com.picacomic.fregata.adapters.ComicListRecyclerViewAdapter", 
                    lpparam.classLoader,
                    android.content.Context.class, 
                    java.util.ArrayList.class, 
                    "com.picacomic.fregata.a.b",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object adapterInstance = param.thisObject;
                            
                            // 获取动态构造的屏蔽逻辑关键词数组
                            String[] dynamicKeywords = buildDynamicLogicalKeywords();
                            
                            if (dynamicKeywords != null) {
                                XposedHelpers.setObjectField(adapterInstance, "js", dynamicKeywords);
                                //XposedBridge.log("HookMyPica: 成功替换屏蔽关键词数组！");
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("HookMyPica: Hook Adapter 构造函数失败: " + t.getMessage());
        }


        // 在hook加载后调用API，获取最新的启动图
        XposedBridge.log("Calling fetchAndUpdateImages to download images.");
        fetchAndUpdateImages();
        // 获取屏蔽关键词配置
        fetchAndSaveConfigAsync();

    }


    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        if (!resparam.packageName.equals("com.yareiy.mypica")) return;

            try {
                // #da547c 转换为十六进制 0xFFDA547C
                resparam.res.setReplacement("com.yareiy.mypica", "color", "pinkDark", 0xFFDA547C);
                XResources.setSystemWideReplacement("com.yareiy.mypica", "color", "pinkDark", 0xFFDA547C);
                
                //XposedBridge.log("HookMyPica: 颜色资源 pinkDark 已修改为 #da547c");
            } catch (Throwable t) {
                XposedBridge.log("HookMyPica: 修改颜色资源失败: " + t.getMessage());
            }



            JSONObject config = loadLocalConfig();
            if (config == null || !config.has("FilterKeywords")) return;

            try {
                JSONObject filterKeywords = config.getJSONObject("FilterKeywords");
                
                // 使用更安全的双重保险替换方法
                safeReplaceResource(resparam, filterKeywords, "1", "comic_list_filter_forbidden", 0x7f0f00ff);
                safeReplaceResource(resparam, filterKeywords, "2", "comic_list_filter_non_chinese", 0x7f0f0100);
                safeReplaceResource(resparam, filterKeywords, "3", "comic_list_filter_bl", 0x7f0f00ee);

            } catch (Exception e) {
                XposedBridge.log("HookMyPica: 资源处理逻辑异常: " + e.getMessage());
            }
        }

    // 通过API获取最新的启动图URL，并缓存到本地
    private void fetchAndUpdateImages() {
        new Thread(() -> {
            String username = "";
            FileInputStream fis = null;
            
            // 读取用户配置
            try {
                File prefsFile = new File("/data/user/0/com.yareiy.mypica/shared_prefs/PICACOMIC_FREGATA.xml");
                if (prefsFile.exists()) {
                    try {
                        // XML解析初始化
                        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                        factory.setNamespaceAware(true);
                        XmlPullParser parser = factory.newPullParser();
                        
                        // 文件流处理
                        fis = new FileInputStream(prefsFile);
                        parser.setInput(fis, "UTF-8");

                        // XML解析
                        int eventType = parser.getEventType();
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG) {
                                String tagName = parser.getName();
                                if ("string".equals(tagName)) {
                                    String nameAttr = parser.getAttributeValue(null, "name");
                                    if ("KEY_USER_LOGIN_EMAIL".equals(nameAttr)) {
                                        eventType = parser.next();
                                        if (eventType == XmlPullParser.TEXT) {
                                            username = parser.getText().trim();
                                            break;
                                        }
                                    }
                                }
                            }
                            eventType = parser.next();
                        }
                    } catch (FileNotFoundException e) {
                        XposedBridge.log("[ERR] Prefs file not found: " + e.getMessage());
                    } catch (SecurityException e) {
                        XposedBridge.log("[ERR] File access denied: " + e.getMessage());
                    } catch (Exception e) {
                        XposedBridge.log("[ERR] XML parsing failed: " + e.getMessage());
                    } finally {
                        // 关闭文件流
                        if (fis != null) {
                            try {
                                fis.close();
                            } catch (IOException e) {
                                XposedBridge.log("[WARN] Stream closing failed: " + e.getMessage());
                            }
                        }
                    }
                } else {
                    XposedBridge.log("[INFO] Prefs file does not exist, using anonymous mode");
                }
            } catch (SecurityException e) {
                XposedBridge.log("[ERR] File path access denied: " + e.getMessage());
            }

            // 构建API请求
            try {
                // 构建动态URL
                String baseUrl = getUrlFromConfig();
                if (baseUrl == null) {
                        baseUrl = OLD_URL; // 默认地址
                    }
                String apiUrl = baseUrl + "GetLaunchImage";
                if (!username.isEmpty()) {
                    apiUrl += "?user=" + URLEncoder.encode(username, "UTF-8");
                    XposedBridge.log("[DEBUG] Requesting with user: " + username);
                } else {
                    XposedBridge.log("[INFO] Making anonymous image request");
                }

                // 网络请求
                URL url = new URL(apiUrl);
                //URL url = new URL("https://picaapi.reiyy.com:2333/GetLaunchImage");
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

    // 下载启动图到本地
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

    // 显示配置窗口
    private void showConfigDialog(final Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert);
        builder.setTitle("哔咔桥 API配置");
        builder.setMessage("输入API地址：");

        // 创建配置输入框
        final EditText input = new EditText(context);
        input.setSingleLine(true); // 强制单行
        input.setTextColor(android.graphics.Color.BLACK);
        
        // 读取已配置的URL，如果不存在则显示默认示例
        String currentConfig = getUrlFromConfig();
        if (currentConfig != null) {
            input.setText(currentConfig);
        } else {
            input.setHint("https://picaapi.example.com:2333/");
        }

        // 设置输入框边距
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(60, 20, 60, 0); // 左右边距
        input.setLayoutParams(lp);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("保存", null);
        builder.setNegativeButton("取消", null);

        final AlertDialog dialog = builder.create();
        dialog.show();

        // 保存时进行格式校验
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = input.getText().toString().trim();
                
                if (url.isEmpty()) {
                    input.setError("内容不能为空");
                    return;
                }

                String regex = "^https?://[^/]+/?$";
                
                if (url.matches(regex)) {
                    // 格式正确，确保结尾带 /
                    if (!url.endsWith("/")) {
                        url += "/";
                    }
                    saveUrlToFile(context, url);
                    dialog.dismiss(); // 校验通过关闭窗口
                } else {
                    // 格式错误
                    input.setError("URL格式不正确，不能包含路径！");
                    Toast.makeText(context, "输入的URL格式不正确，不能包含路径！", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void saveUrlToFile(Context context, String url) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Android/data/com.yareiy.mypica");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File configFile = new File(dir, "ApiConfig.txt");
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(url.getBytes());
            fos.close();

            Toast.makeText(context, "API配置已保存！", Toast.LENGTH_LONG).show();
            XposedBridge.log("[HookMyPica] 配置已保存到 " + configFile.getAbsolutePath());
        } catch (Exception e) {
            XposedBridge.log("[HookMyPica] Error: " + e.getMessage());
            Toast.makeText(context, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 从配置文件读取URL
    private String getUrlFromConfig() {
        try {
            File file = new File(Environment.getExternalStorageDirectory(), CONFIG_PATH);
            if (file.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(file));
                String line = br.readLine();
                br.close();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            XposedBridge.log("[HookMyPica] 读取配置失败: " + e.getMessage());
        }
        return null;
    }

    // 在UI层弹出Toast
    private void showToastOnUI(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    Object at = atClass.getMethod("currentActivityThread").invoke(null);
                    Context app = (Context) atClass.getMethod("getApplication").invoke(at);
                    if (app != null) {
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    XposedBridge.log("[HookMyPica] 无法弹出 Toast: " + e.getMessage());
                }
            }
        });
    }


    // 保存配置到文件
    private void saveConfigToFile(String jsonString) {
        try {
            File file = new File(CONFIG_FILE_PATH);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(jsonString.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            XposedBridge.log("HookMyPica 保存配置文件失败: " + e.getMessage());
        }
    }

    // 加载配置
    private JSONObject loadLocalConfig() {
        if (cachedFilterConfig != null) {
            return cachedFilterConfig;
        }

        File file = new File(CONFIG_FILE_PATH);
        if (!file.exists()) {
            return null; // 文件不存在，跳过修改使用原始配置
        }

        try {
            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            cachedFilterConfig = new JSONObject(sb.toString());
            return cachedFilterConfig;
        } catch (Exception e) {
            XposedBridge.log("HookMyPica 读取配置文件解析失败: " + e.getMessage());
            return null;
        }
    }

    // 获取屏蔽关键词配置并保存
    private void fetchAndSaveConfigAsync() {
        new Thread(() -> {
            try {
                String baseUrl = getUrlFromConfig();
                if (baseUrl == null || baseUrl.isEmpty()) {
                    baseUrl = OLD_URL;
                }
                
                if (!baseUrl.endsWith("/")) {
                    baseUrl += "/";
                }
                
                String apiUrl = baseUrl + "GetFilterKeywords";
                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                if (connection.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // 写入本地文件
                    saveConfigToFile(response.toString());
                    // 清理内存缓存，下次读取新文件
                    cachedFilterConfig = null; 
                }
                connection.disconnect();
            } catch (Exception e) {
                XposedBridge.log("HookMyPica 网络获取配置失败: " + e.getMessage());
            }
        }).start();
    }

    // 提取逻辑关键词
    private String[] buildDynamicLogicalKeywords() {
        JSONObject config = loadLocalConfig();
        if (config == null || !config.has("FilterKeywords")) {
            return null; // 没有配置
        }

        try {
            JSONObject keywordsObj = config.getJSONObject("FilterKeywords");
            
            int filterCount = 8; 
            String[] newJsArray = new String[filterCount];
            
            for (int i = 0; i < filterCount; i++) {
                String id = String.valueOf(i + 1); // id 从1到8
                if (keywordsObj.has(id)) {
                    JSONArray itemArray = keywordsObj.getJSONArray(id);
                    if (itemArray.length() > 1) {
                        // 第2个参数 是实际逻辑关键词
                        newJsArray[i] = itemArray.getString(1); 
                    } else {
                        newJsArray[i] = "";
                    }
                } else {
                    newJsArray[i] = ""; // 如果配置不全，填入空字符串
                }
            }
            return newJsArray;
        } catch (Exception e) {
            XposedBridge.log("HookMyPica: 解析逻辑关键词数组出错: " + e.getMessage());
            return null;
        }
    }


    private void safeReplaceResource(XC_InitPackageResources.InitPackageResourcesParam resparam, 
                                    JSONObject keywordsObj, String jsonKey, String resName, int resId) {
        try {
            if (keywordsObj.has(jsonKey)) {
                String newValue = keywordsObj.getJSONArray(jsonKey).getString(0);
                
                try {
                    // 优先使用名称替换
                    resparam.res.setReplacement("com.yareiy.mypica", "string", resName, newValue);
                } catch (Throwable t) {
                    // 如果名称找不到，使用ID
                    resparam.res.setReplacement(resId, newValue);
                    XposedBridge.log("HookMyPica: 名称查找失败，通过 ID " + Integer.toHexString(resId) + " 替换");
                }
            }
        } catch (Exception e) {
            XposedBridge.log("HookMyPica: 彻底替换资源 " + resName + " 失败: " + e.getMessage());
        }
    }


}


    

