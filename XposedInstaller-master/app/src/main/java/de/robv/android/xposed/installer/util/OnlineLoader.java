package de.robv.android.xposed.installer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.annotation.CallSuper;

import de.robv.android.xposed.installer.XposedApp;

public abstract class OnlineLoader<T> extends Loader<T> {
    //以当前包名的文件
    protected SharedPreferences mPref = XposedApp.getPreferences();
    //父类的名称+_last_update_check
    protected String mPrefKeyLastUpdateCheck = CLASS_NAME + "_last_update_check";
    protected int mUpdateFrequency = 24 * 60 * 60 * 1000;
    // ConnectivityManager 是 Android 提供的一个系统服务类，用于管理设备的网络连接状态和网络相关的操作
    private static final ConnectivityManager sConMgr
            = (ConnectivityManager) XposedApp.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE);

    protected boolean shouldUpdate() {
        long now = System.currentTimeMillis();
        long lastUpdateCheck = mPref.getLong(mPrefKeyLastUpdateCheck, 0);
        //是否要更新 上一次更新时间加上更新的频率
        if (now < lastUpdateCheck + mUpdateFrequency) {
            return false;
        }

        // 获取当前活动的网络连接信息，包括网络类型（如移动数据、Wi-Fi、蓝牙、以太网等）以及网络是否可用等
        NetworkInfo netInfo = sConMgr.getActiveNetworkInfo();
        //是否有网络连接
        if (netInfo == null || !netInfo.isConnected()) {
            return false;
        }
        
        //把这次更新时间放入文件
        mPref.edit().putLong(mPrefKeyLastUpdateCheck, now).apply();
        return true;
    }

    @CallSuper
    @Override
    protected void onClear() {
        //清楚更新时间记录
        resetLastUpdateCheck();
    }

    public void resetLastUpdateCheck() {
        mPref.edit().remove(mPrefKeyLastUpdateCheck).apply();
    }

}
