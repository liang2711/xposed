package de.robv.android.xposed.installer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.content.FileProvider;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

import de.robv.android.xposed.installer.util.AssetUtil;
import de.robv.android.xposed.installer.util.DownloadsUtil;
import de.robv.android.xposed.installer.util.InstallZipUtil;
import de.robv.android.xposed.installer.util.InstallZipUtil.XposedProp;
import de.robv.android.xposed.installer.util.NotificationUtil;
import de.robv.android.xposed.installer.util.RepoLoader;
/**
 * 对xposed应用进行准备工作，如获得文件配置于Prop
 * 
 * ActivityLifecycleCallbacks
 * activity生命周期的回调
 * 每个activity都会
 * */
public class XposedApp extends Application implements ActivityLifecycleCallbacks {
    public static final String TAG = "XposedInstaller";

    @SuppressLint("SdCardPath")
    
    private static final String BASE_DIR_LEGACY = "/data/data/de.robv.android.xposed.installer/";

    /**
     * 当前应用的路径 版本分割线为Android7 这个不是存储apk的路径
     *是存储的是应用程序的数据和配置信息 内部存储 而且这个user_de是加密的，相对应user是不加密的数据
     **/
    public static final String BASE_DIR = Build.VERSION.SDK_INT >= 24
            ? "/data/user_de/0/de.robv.android.xposed.installer/" : BASE_DIR_LEGACY;

    public static final String ENABLED_MODULES_LIST_FILE = XposedApp.BASE_DIR + "conf/enabled_modules.list";

    //对应着XposedProp类的数据，也只能对应着一个文件 /system优先级最高 记录着ARCH，xposedversion,sdk(min,max)等
    private static final String[] XPOSED_PROP_FILES = new String[]{
            "/su/xposed/xposed.prop", // official systemless
            "/system/xposed.prop",    // classical
    };

    public static int WRITE_EXTERNAL_PERMISSION = 69;
    private static XposedApp mInstance = null;
    private static Thread mUiThread;
    private static Handler mMainHandler;
    private boolean mIsUiLoaded = false;
    private SharedPreferences mPref;
    private XposedProp mXposedProp;

    public static XposedApp getInstance() {
        return mInstance;
    }

    public static void runOnUiThread(Runnable action) {
        if (Thread.currentThread() != mUiThread) {
            //如果不是在主线程中 使用handler调用这个action，这个就当在主线程中使用action.run()方法它不会创建子线程
            mMainHandler.post(action);
        } else {
            action.run();
        }
    }

    public static void postOnUiThread(Runnable action) {
        mMainHandler.post(action);
    }

    // This method is hooked by XposedBridge to return the current version
    public static int getActiveXposedVersion() {
        return -1;
    }

    public static int getInstalledXposedVersion() {
        XposedProp prop = getXposedProp();
        return prop != null ? prop.getVersionInt() : -1;
    }

    public static XposedProp getXposedProp() {
        synchronized (mInstance) {
            return mInstance.mXposedProp;
        }
    }

    public static SharedPreferences getPreferences() {
        return mInstance.mPref;
    }

    //安装apk
    public static void installApk(Context context, DownloadsUtil.DownloadInfo info) {
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri uri;
        if (Build.VERSION.SDK_INT >= 24) {
            /**
             * 得到info.localFilename文件资源uri(目录在file_path.xml中)，key=de.robv.android.xposed.installer.fileprovider
             * 分享文件
             */
            uri = FileProvider.getUriForFile(context, "de.robv.android.xposed.installer.fileprovider", new File(info.localFilename));
            installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(new File(info.localFilename));
        }
        installIntent.setDataAndType(uri, DownloadsUtil.MIME_TYPE_APK);
        installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getApplicationInfo().packageName);
        context.startActivity(installIntent);
    }

    public static String getDownloadPath() {
        return getPreferences().getString("download_location", Environment.getExternalStorageDirectory() + "/XposedInstaller");
    }

    public void onCreate() {
        super.onCreate();
        mInstance = this;
        //获得当前运行的线程 主线程
        mUiThread = Thread.currentThread();
        mMainHandler = new Handler();

        //和welcomeactivity一样的context
        mPref = PreferenceManager.getDefaultSharedPreferences(this);
        reloadXposedProp();
        createDirectories();
        NotificationUtil.init();
        AssetUtil.removeBusybox();

        registerActivityLifecycleCallbacks(this);
    }

    private void createDirectories() {
        //在nox模拟器中sdk=25 /data/user_de/0/de.robv.android.xposed.installer/创建工作区
        FileUtils.setPermissions(BASE_DIR, 00711, -1, -1);
        mkdirAndChmod("conf", 00771);
        mkdirAndChmod("log", 00777);

        if (Build.VERSION.SDK_INT >= 24) {
            //移除sdk24以下的工作区域
            try {
                Method deleteDir = FileUtils.class.getDeclaredMethod("deleteContentsAndDir", File.class);
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "bin"));
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "conf"));
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "log"));
            } catch (ReflectiveOperationException e) {
                Log.w(XposedApp.TAG, "Failed to delete obsolete directories", e);
            }
        }
    }

    private void mkdirAndChmod(String dir, int permissions) {
        dir = BASE_DIR + dir;
        new File(dir).mkdir();
        FileUtils.setPermissions(dir, permissions, -1, -1);
    }

    public void reloadXposedProp() {
        XposedProp prop = null;

        for (String path : XPOSED_PROP_FILES) {
            File file = new File(path);
            if (file.canRead()) {
                FileInputStream is = null;
                try {
                    is = new FileInputStream(file);
                    //从路径中获取xposedProp
                    prop = InstallZipUtil.parseXposedProp(is);
                    break;
                } catch (IOException e) {
                    Log.e(XposedApp.TAG, "Could not read " + file.getPath(), e);
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        synchronized (this) {
            mXposedProp = prop;
        }
    }

    // TODO find a better way to trigger actions only when any UI is shown for the first time activity 生命周期回调函数
    @Override
    public synchronized void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (mIsUiLoaded)
            return;

        RepoLoader.getInstance().triggerFirstLoadIfNecessary();
        mIsUiLoaded = true;
    }

    @Override
    public synchronized void onActivityResumed(Activity activity) {
    }

    @Override
    public synchronized void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
