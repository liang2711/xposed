package de.robv.android.xposed.installer.util;

import android.content.Context;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;

import de.robv.android.xposed.installer.R;
import de.robv.android.xposed.installer.XposedApp;
import de.robv.android.xposed.installer.XposedBaseActivity;

public final class ThemeUtil {
	//标题样式
	private static int[] THEMES = new int[] {
			R.style.Theme_XposedInstaller_Light,
			R.style.Theme_XposedInstaller_Dark,
			R.style.Theme_XposedInstaller_Dark_Black, };

	private ThemeUtil() {
	}

	public static int getSelectTheme() {
		//从文件中获取theme是那个
		int theme = XposedApp.getPreferences().getInt("theme", 0);
		return (theme >= 0 && theme < THEMES.length) ? theme : 0;
	}

	public static void setTheme(XposedBaseActivity activity) {
		//覆盖之前的theme设置新的
		activity.mTheme = getSelectTheme();
		activity.setTheme(THEMES[activity.mTheme]);
	}

	public static void reloadTheme(XposedBaseActivity activity) {
		//如果当前主题不是文件里的说明可能设置了但activity没更新，重新加载activity
		int theme = getSelectTheme();
		if (theme != activity.mTheme)
			activity.recreate();
    }

    public static int getThemeColor(Context context, int id) {
        Theme theme = context.getTheme();
        TypedArray a = theme.obtainStyledAttributes(new int[] { id });
		int result = a.getColor(0, 0);
		a.recycle();
		return result;
	}
}
