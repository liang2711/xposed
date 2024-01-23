package de.robv.android.xposed.installer.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Browser;
import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.support.customtabs.CustomTabsIntent;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.URLSpan;
import android.text.util.Linkify;

import com.afollestad.materialdialogs.MaterialDialog;

import de.robv.android.xposed.installer.R;
import de.robv.android.xposed.installer.XposedApp;

public final class NavUtil {
    //获得str里的链接
    public static Uri parseURL(String str) {
        if (str == null || str.isEmpty())
            return null;

        Spannable spannable = new SpannableString(str);
        Linkify.addLinks(spannable, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        URLSpan spans[] = spannable.getSpans(0, spannable.length(), URLSpan.class);
        return (spans.length > 0) ? Uri.parse(spans[0].getURL()) : null;
    }
    //打开一个网页
    public static void startURL(Activity activity, Uri uri) {
        if (!XposedApp.getPreferences().getBoolean("chrome_tabs", true)) {
            /**
             * 用于显示用户的数据。比较通用，会根据用户的数据类型打开相应的Activity。
             * 比如 tel:13400010001打开拨号程序，http://www.g.cn则会打开浏览器等
             */
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, activity.getPackageName());
            activity.startActivity(intent);
            return;
        }
        //跳转网页的，先是开启一个新的activity在这活动里显示要显示的网页
        CustomTabsIntent.Builder customTabsIntent = new CustomTabsIntent.Builder();
        customTabsIntent.setShowTitle(true);
        customTabsIntent.setToolbarColor(activity.getResources().getColor(R.color.colorPrimary));
        customTabsIntent.build().launchUrl(activity, uri);
    }

    public static void startURL(Activity activity, String url) {
        startURL(activity, parseURL(url));
    }

    @AnyThread
    public static void showMessage(final @NonNull Context context, final CharSequence message) {
        XposedApp.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new MaterialDialog.Builder(context)
                        .content(message)
                        .positiveText(android.R.string.ok)
                        .show();
            }
        });
    }
}
