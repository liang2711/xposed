package de.robv.android.xposed.installer.util;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.support.v4.content.ContextCompat;
import android.support.v4.os.EnvironmentCompat;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.MaterialDialog.SingleButtonCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.installer.R;
import de.robv.android.xposed.installer.XposedApp;
import de.robv.android.xposed.installer.repo.Module;
import de.robv.android.xposed.installer.repo.ModuleVersion;
import de.robv.android.xposed.installer.repo.ReleaseType;

public class DownloadsUtil {
    public static final String MIME_TYPE_APK = "application/vnd.android.package-archive";
    public static final String MIME_TYPE_ZIP = "application/zip";
    private static final Map<String, DownloadFinishedCallback> mCallbacks = new HashMap<>();
    private static final XposedApp mApp = XposedApp.getInstance();
    //download_cache为文件名
    private static final SharedPreferences mPref = mApp
            .getSharedPreferences("download_cache", Context.MODE_PRIVATE);

    public static class Builder {
        private final Context mContext;
        private String mTitle = null;
        private String mUrl = null;
        private DownloadFinishedCallback mCallback = null;
        //apk的枚举 自定义类
        private MIME_TYPES mMimeType = MIME_TYPES.APK;
        private File mDestination = null;
        private boolean mDialog = false;

        public Builder(Context context) {
            mContext = context;
        }

        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        public Builder setUrl(String url) {
            mUrl = url;
            return this;
        }

        public Builder setCallback(DownloadFinishedCallback callback) {
            mCallback = callback;
            return this;
        }

        public Builder setMimeType(MIME_TYPES mimeType) {
            mMimeType = mimeType;
            return this;
        }

        public Builder setDestination(File file) {
            mDestination = file;
            return this;
        }

        public Builder setDestinationFromUrl(String subDir) {
            if (mUrl == null) {
                throw new IllegalStateException("URL must be set first");
            }
            return setDestination(getDownloadTargetForUrl(subDir, mUrl));
        }

        public Builder setDialog(boolean dialog) {
            mDialog = dialog;
            return this;
        }

        public DownloadInfo download() {
            return add(this);
        }
    }

    public static String DOWNLOAD_FRAMEWORK = "framework";
    public static String DOWNLOAD_MODULES = "modules";

    /**
     * 注：在new File(fileName) 不管fileName是否存在或者是否是文件或者目录，创建的单单只是一个file类的对象
     * 它不会帮你自动创建不存在的文件合作目录只有自定义，目录而言.mkdir() 文件而言.createNewFile()
     */
    public static File[] getDownloadDirs(String subDir) {
        Context context = XposedApp.getInstance();
        ArrayList<File> dirs = new ArrayList<>(2);
        /**
        * ontextCompat.getExternalCacheDirs(context)用于获取外部存储器上应用程序的缓存目录数组
        * 在/storage/emulated/0/Android/data/<package_name>/cache中 
        * 而下面是获取了/storage/emulated/0/Android/data/<package_name>/cache/downloads/{subDir}的文件类对象
        */
        for (File dir : ContextCompat.getExternalCacheDirs(context)) {
            //查看dir是否被挂载
            if (dir != null && EnvironmentCompat.getStorageState(dir).equals(Environment.MEDIA_MOUNTED)) {
                dirs.add(new File(new File(dir, "downloads"), subDir));
            }
        }
        //获取了/data/user/0/<package_Name>/cache/downloads/{subDir} 意义不明 经过as的测试
        dirs.add(new File(new File(context.getCacheDir(), "downloads"), subDir));
        return dirs.toArray(new File[dirs.size()]);
    }

    public static File getDownloadTarget(String subDir, String filename) {
        return new File(getDownloadDirs(subDir)[0], filename);
    }

    public static File getDownloadTargetForUrl(String subDir, String url) {
        //Uri.parse(url).getLastPathSegment() 解析 URL 字符串并获取其路径的最后一部分（即最后一个路径段）
        // /storage/emulated/0/Android/data/<package_name>/cache/downloads/{subDir}/{Uri.parse(url).getLastPathSegment()}
        return getDownloadTarget(subDir, Uri.parse(url).getLastPathSegment());
    }

    public static DownloadInfo addModule(Context context, String title, String url, DownloadFinishedCallback callback) {
        return new Builder(context)
                .setTitle(title)
                .setUrl(url)
                .setDestinationFromUrl(DownloadsUtil.DOWNLOAD_MODULES)
                .setCallback(callback)
                .setMimeType(MIME_TYPES.APK)
                .download();
    }

    private static DownloadInfo add(Builder b) {
        Context context = b.mContext;
        //移除b.murl的下载任务
        removeAllForUrl(context, b.mUrl);

        if (!b.mDialog) {
            synchronized (mCallbacks) {
                mCallbacks.put(b.mUrl, b.mCallback);
            }
        }

        // request封装一个下载请求 增加新下载任务  uri.parse(b.murl)将url转换成uri
        Request request = new Request(Uri.parse(b.mUrl));
        request.setTitle(b.mTitle);
        request.setMimeType(b.mMimeType.toString());
        //创建文件
        if (b.mDestination != null) {
            b.mDestination.getParentFile().mkdirs();

            removeAllForLocalFile(context, b.mDestination);
            //将b.mDestination转为uri 设置下载文件后存入哪里，他必须是外部内存的
            request.setDestinationUri(Uri.fromFile(b.mDestination));
        }
        request.setNotificationVisibility(Request.VISIBILITY_VISIBLE);

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = dm.enqueue(request);

        if (b.mDialog) {
            showDownloadDialog(b, id);
        }

        return getById(context, id);
    }

    private static void showDownloadDialog(final Builder b, final long id) {
        final Context context = b.mContext;
        //设置下载进度对话框
        final DownloadDialog dialog = new DownloadDialog(new MaterialDialog.Builder(context)
                .title(b.mTitle)
                .content(R.string.download_view_waiting)
                .progress(false, 0, true)
                .progressNumberFormat(context.getString(R.string.download_progress))
                .canceledOnTouchOutside(false)
                .negativeText(R.string.download_view_cancel)
                .onNegative(new SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        dialog.cancel();
                    }
                })
                .cancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        removeById(context, id);
                    }
                })
        );
        dialog.setShowProcess(false);
        dialog.show();

        new Thread("DownloadDialog") {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                    //获得id的下载任务信息 应该是显示下载进度对于上面的下载对话框
                    final DownloadInfo info = getById(context, id);
                    if (info == null) {
                        dialog.cancel();
                        return;
                    } else if (info.status == DownloadManager.STATUS_FAILED) {//下载失败
                        dialog.cancel();
                        XposedApp.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(context,
                                        context.getString(R.string.download_view_failed, info.reason),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    } else if (info.status == DownloadManager.STATUS_SUCCESSFUL) {
                        dialog.dismiss();
                        // Hack to reset stat information.
                        new File(info.localFilename).setExecutable(false);
                        if (b.mCallback != null) {
                            b.mCallback.onDownloadFinished(context, info);
                        }
                        return;
                    }
                    // 传输下载进度
                    XposedApp.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (info.totalSize <= 0 || info.status != DownloadManager.STATUS_RUNNING) {
                                dialog.setContent(R.string.download_view_waiting);
                                dialog.setShowProcess(false);
                            } else {
                                dialog.setContent(R.string.download_running);
                                dialog.setProgress(info.bytesDownloaded / 1024);
                                dialog.setMaxProgress(info.totalSize / 1024);
                                dialog.setShowProcess(true);
                            }
                        }
                    });
                }
            }
        }.start();
    }

    private static class DownloadDialog extends MaterialDialog {
        public DownloadDialog(Builder builder) {
            super(builder);
        }

        @UiThread
        public void setShowProcess(boolean show) {
            int visibility = show ? View.VISIBLE : View.GONE;
            mProgress.setVisibility(visibility);
            mProgressLabel.setVisibility(visibility);
            mProgressMinMax.setVisibility(visibility);
        }
    }

    public static ModuleVersion getStableVersion(Module m) {
        for (int i = 0; i < m.versions.size(); i++) {
            ModuleVersion mvTemp = m.versions.get(i);

            if (mvTemp.relType == ReleaseType.STABLE) {
                return mvTemp;
            }
        }
        return null;
    }
    //获得任务信息(这个任务还没执行完)
    public static DownloadInfo getById(Context context, long id) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query().setFilterById(id));
        if (!c.moveToFirst()) {
            c.close();
            return null;
        }

        int columnUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI);
        int columnTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE);
        int columnLastMod = c.getColumnIndexOrThrow(
                DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
        int columnLocalUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI);
        int columnStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
        int columnTotalSize = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
        int columnBytesDownloaded = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        int columnReason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);

        int status = c.getInt(columnStatus);
        //获得任务储存地址，在设置DestinationUri子目录
        String localFilename = getFilenameFromUri(c.getString(columnLocalUri));
        if (status == DownloadManager.STATUS_SUCCESSFUL && !new File(localFilename).isFile()) {
            dm.remove(id);
            c.close();
            return null;
        }

        DownloadInfo info = new DownloadInfo(id, c.getString(columnUri),
                c.getString(columnTitle), c.getLong(columnLastMod),
                localFilename, status,
                c.getInt(columnTotalSize), c.getInt(columnBytesDownloaded),
                c.getInt(columnReason));
        c.close();
        return info;
    }

    public static DownloadInfo getLatestForUrl(Context context, String url) {
        List<DownloadInfo> all = getAllForUrl(context, url);
        return all.isEmpty() ? null : all.get(0);
    }
    //获得所有任务信息
    public static List<DownloadInfo> getAllForUrl(Context context, String url) {
        DownloadManager dm = (DownloadManager) context
                .getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query());
        int columnId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
        int columnUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI);
        int columnTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE);
        int columnLastMod = c.getColumnIndexOrThrow(
                DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
        int columnLocalUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI);
        int columnStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
        int columnTotalSize = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
        int columnBytesDownloaded = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        int columnReason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);

        List<DownloadInfo> downloads = new ArrayList<>();
        while (c.moveToNext()) {
            if (!url.equals(c.getString(columnUri)))
                continue;

            int status = c.getInt(columnStatus);
            //获得uri资源路径中的文件路径
            String localFilename = getFilenameFromUri(c.getString(columnLocalUri));
            if (status == DownloadManager.STATUS_SUCCESSFUL && !new File(localFilename).isFile()) {
                dm.remove(c.getLong(columnId));
                continue;
            }

            downloads.add(new DownloadInfo(c.getLong(columnId),
                    c.getString(columnUri), c.getString(columnTitle),
                    c.getLong(columnLastMod), localFilename,
                    status, c.getInt(columnTotalSize),
                    c.getInt(columnBytesDownloaded), c.getInt(columnReason)));
        }
        c.close();

        Collections.sort(downloads);
        return downloads;
    }

    public static void removeById(Context context, long id) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        dm.remove(id);
    }

    public static void removeAllForUrl(Context context, String url) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query());
        /**
         * 通过c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)和
         * c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)获取下载任务在Cursor中的列索引。
         * COLUMN_ID表示任务ID列，COLUMN_URI表示任务的下载地址列
         * */
        int columnId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
        int columnUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI);

        List<Long> idsList = new ArrayList<>(1);
        while (c.moveToNext()) {
            if (url.equals(c.getString(columnUri)))
                idsList.add(c.getLong(columnId));
        }
        c.close();

        if (idsList.isEmpty())
            return;

        long ids[] = new long[idsList.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = idsList.get(i);
        //移除下载的任务以任务的id来移除
        dm.remove(ids);
    }
    //移除以file的uri任务
    public static void removeAllForLocalFile(Context context, File file) {
        file.delete();

        String filename;
        try {
            //  getCanonicalPath()返回文件的绝对路径
            filename = file.getCanonicalPath();
        } catch (IOException e) {
            Log.w(XposedApp.TAG, "Could not resolve path for " + file.getAbsolutePath(), e);
            return;
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query());
        int columnId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
        int columnLocalUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI);

        List<Long> idsList = new ArrayList<>(1);
        while (c.moveToNext()) {
            //获得uri中的文件路径
            String itemFilename = getFilenameFromUri(c.getString(columnLocalUri));
            if (itemFilename != null) {
                if (filename.equals(itemFilename)) {
                    idsList.add(c.getLong(columnId));
                } else {
                    try {
                        if (filename.equals(new File(itemFilename).getCanonicalPath())) {
                            idsList.add(c.getLong(columnId));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        c.close();

        if (idsList.isEmpty())
            return;

        long ids[] = new long[idsList.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = idsList.get(i);

        dm.remove(ids);
    }
    //删除过时任务以修改下载任务的时间戳来判断
    public static void removeOutdated(Context context, long cutoff) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query());
        int columnId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
        int columnLastMod = c.getColumnIndexOrThrow(
                DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);

        List<Long> idsList = new ArrayList<>();
        while (c.moveToNext()) {
            if (c.getLong(columnLastMod) < cutoff)
                idsList.add(c.getLong(columnId));
        }
        c.close();

        if (idsList.isEmpty())
            return;

        long ids[] = new long[idsList.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = idsList.get(0);

        dm.remove(ids);
    }
    //执行下载任务完成后的函数
    public static void triggerDownloadFinishedCallback(Context context, long id) {
        DownloadInfo info = getById(context, id);
        if (info == null || info.status != DownloadManager.STATUS_SUCCESSFUL)
            return;

        DownloadFinishedCallback callback;
        synchronized (mCallbacks) {
            callback = mCallbacks.get(info.url);
        }

        if (callback == null)
            return;

        // Hack to reset stat information.
        new File(info.localFilename).setExecutable(false);
        callback.onDownloadFinished(context, info);
    }

    /**
     * URI：
     * URI用于标识和定位资源，可以是本地文件路径、网络地址、数据库URI等。它是一个通用的资源标识符。
     * URI由多个部分组成，包括方案（Scheme）、主机（Host）、路径（Path）、查询参数（Query）等，但不一定包含所有部分。
     * URI常用于表示文件路径、Content Provider的URI等也包括了想url网络链接 uri可以说是一个相对的资源定位符它可以定位很多的不同类型的资源
     * URL：
     * URL是一种具体的资源定位方式，它包含了协议、主机、路径、查询参数等信息，用于访问网络资源.
     * URL是URI的一种特殊形式，包含了协议、主机、路径、查询参数等信息，具体的结构根据协议的规范而定。
     * URL用于定位和访问网络资源，可以通过URL进行网络请求、下载文件等操作.
     * 
     * 也要注意这里的uri它只识别file or content类型 没有识别网络连接
     * 这里获得uri的文件路径
     */
    private static String getFilenameFromUri(String uriString) {
        if (uriString == null) {
            return null;
        }
        //URI是用于标识和定位资源的字符串表示形式，由多个部分组成，包括方案、主机、路径、查询参数等
        Uri uri = Uri.parse(uriString);
        //getScheme是获取这个uri是什么类型
        if (uri.getScheme().equals("file")) {
            return uri.getPath();//获得文件的路径
        } else if (uri.getScheme().equals("content")) {
            //使用contentResolver解析uri获得文件路径
            Context context = XposedApp.getInstance();
            Cursor c = null;
            try {
                c = context.getContentResolver().query(uri, new String[]{MediaStore.Files.FileColumns.DATA}, null, null, null);
                c.moveToFirst();
                return c.getString(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA));
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        } else {
            throw new UnsupportedOperationException("Unexpected URI: " + uriString);
        }
    }
    //设置更新资源
    public static SyncDownloadInfo downloadSynchronously(String url, File target) {
        //是否存在
        final boolean useNotModifiedTags = target.exists();

        URLConnection connection = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            connection = new URL(url).openConnection();
            connection.setDoOutput(false);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            if (connection instanceof HttpURLConnection) {
                // Disable transparent gzip encoding for gzipped files
                if (url.endsWith(".gz")) {
                    connection.addRequestProperty("Accept-Encoding", "identity");
                }

                if (useNotModifiedTags) {
                    //mPred=download_cache.xml
                    String modified = mPref.getString("download_" + url + "_modified", null);
                    String etag = mPref.getString("download_" + url + "_etag", null);

                    if (modified != null) {
                        /**用于指定上次请求资源的修改时间。服务器可以根据这个属性值进行判断，
                         * 如果上次请求的资源自从指定时间以来没有修改，则返回 HTTP 304 Not Modified 状态码，
                         * 表示资源未修改，客户端可以使用缓存的版本 */
                        connection.addRequestProperty("If-Modified-Since", modified);
                    }
                    if (etag != null) {
                        /**
                         * 它用于指定上次请求资源时服务器返回的实体标签（ETag）。
                         * 服务器会将资源的实体标签（通常是一个哈希值或版本号）与客户端提供的 If-None-Match 属性值进行比较。如果两者匹配，
                         * 表示资源未发生改变，服务器将返回 HTTP 304 Not Modified 状态码，客户端可以使用缓存的版本
                         */
                        connection.addRequestProperty("If-None-Match", etag);
                    }
                }
            }

            connection.connect();

            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConnection = (HttpURLConnection) connection;
                int responseCode = httpConnection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return new SyncDownloadInfo(SyncDownloadInfo.STATUS_NOT_MODIFIED, null);
                } else if (responseCode < 200 || responseCode >= 300) {
                    return new SyncDownloadInfo(SyncDownloadInfo.STATUS_FAILED,
                            mApp.getString(R.string.repo_download_failed_http,
                                    url, responseCode,
                                    httpConnection.getResponseMessage()));
                }
            }

            in = connection.getInputStream();
            out = new FileOutputStream(target);
            byte buf[] = new byte[1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }

            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConnection = (HttpURLConnection) connection;
                String modified = httpConnection.getHeaderField("Last-Modified");
                String etag = httpConnection.getHeaderField("ETag");

                mPref.edit()
                        .putString("download_" + url + "_modified", modified)
                        .putString("download_" + url + "_etag", etag).apply();
            }

            return new SyncDownloadInfo(SyncDownloadInfo.STATUS_SUCCESS, null);

        } catch (Throwable t) {
            return new SyncDownloadInfo(SyncDownloadInfo.STATUS_FAILED,
                    mApp.getString(R.string.repo_download_failed, url,
                            t.getMessage()));

        } finally {
            if (connection != null && connection instanceof HttpURLConnection)
                ((HttpURLConnection) connection).disconnect();
            if (in != null)
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            if (out != null)
                try {
                    out.close();
                } catch (IOException ignored) {
                }
        }
    }

    public static void clearCache(String url) {
        if (url != null) {
            mPref.edit().remove("download_" + url + "_modified")
                    .remove("download_" + url + "_etag").apply();
        } else {
            mPref.edit().clear().apply();
        }
    }

    public enum MIME_TYPES {
        APK {
            public String toString() {
                return MIME_TYPE_APK;
            }

            public String getExtension() {
                return ".apk";
            }
        },
        ZIP {
            public String toString() {
                return MIME_TYPE_ZIP;
            }

            public String getExtension() {
                return ".zip";
            }
        };

        public String getExtension() {
            return null;
        }
    }

    public interface DownloadFinishedCallback {
        void onDownloadFinished(Context context, DownloadInfo info);
    }

    //comparable 给list设置排序条件的
    //这个类是给downloadmanager传输参数的类
    public static class DownloadInfo implements Comparable<DownloadInfo> {
        public final long id;
        public final String url;
        public final String title;
        public final long lastModification;
        public final String localFilename;
        public final int status;
        public final int totalSize;
        public final int bytesDownloaded;
        public final int reason;

        private DownloadInfo(long id, String url, String title, long lastModification, String localFilename, int status, int totalSize, int bytesDownloaded, int reason) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.lastModification = lastModification;
            this.localFilename = localFilename;
            this.status = status;
            this.totalSize = totalSize;
            this.bytesDownloaded = bytesDownloaded;
            this.reason = reason;
        }

        @Override
        public int compareTo(@NonNull DownloadInfo another) {
            int compare = (int) (another.lastModification
                    - this.lastModification);
            if (compare != 0)
                return compare;
            return this.url.compareTo(another.url);
        }
    }

    public static class SyncDownloadInfo {
        public static final int STATUS_SUCCESS = 0;
        public static final int STATUS_NOT_MODIFIED = 1;
        public static final int STATUS_FAILED = 2;

        public final int status;
        public final String errorMessage;

        private SyncDownloadInfo(int status, String errorMessage) {
            this.status = status;
            this.errorMessage = errorMessage;
        }
    }
}
