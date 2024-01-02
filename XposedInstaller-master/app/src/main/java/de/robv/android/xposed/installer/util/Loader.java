package de.robv.android.xposed.installer.util;

import android.support.v4.widget.SwipeRefreshLayout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.installer.XposedApp;

public abstract class Loader<T> implements SwipeRefreshLayout.OnRefreshListener {
    //获取当前对象所属类的简单名称
    protected final String CLASS_NAME = getClass().getSimpleName();
    private boolean mIsLoading = false;
    private boolean mReloadTriggeredOnce = false;
    /**
     * Listener是自定义的一个接口
     * CopyOnWriteArrayList<>() 是对 List 接口的实现类，它是线程安全的，支持并发读写操作。
     * 它是通过在写操作时创建底层数组的副本来实现线程安全，因此适用于读操作频繁、写操作相对较少的场景。
     */
    private final List<Listener<T>> mListeners = new CopyOnWriteArrayList<>();
    private SwipeRefreshLayout mSwipeRefreshLayout;

    public void triggerReload(final boolean force) {
        synchronized (this) {
            if (!mReloadTriggeredOnce) {
                onFirstLoad();
                mReloadTriggeredOnce = true;
            }
        }

        if (!force && !shouldUpdate()) {
            return;
        }
        //第一次加载
        synchronized (this) {
            //如果已加载退出
            if (mIsLoading) {
                return;
            }
            mIsLoading = true;
            updateProgressIndicator();
        }

        new Thread("Reload" + CLASS_NAME) {
            public void run() {
                boolean hasChanged = onReload();
                if (hasChanged) {
                    notifyListeners();
                }

                synchronized (this) {
                    mIsLoading = false;
                    updateProgressIndicator();
                }
            }
        }.start();
    }

    protected synchronized void onFirstLoad() {
        // Empty by default.
    }

    protected boolean shouldUpdate() {
        return true;
    }

    protected abstract boolean onReload();

    public void clear(boolean notify) {
        synchronized (this) {
            // TODO Stop reloading repository when it should be cleared
            //当为下拉更新时不能清除更新时间
            if (mIsLoading) {
                return;
            }
            //清除更新的时间
            onClear();
        }

        //是否通知所有listeners
        if (notify) {
            notifyListeners();
        }
    }

    protected abstract void onClear();

    public void triggerFirstLoadIfNecessary() {
        synchronized (this) {
            if (mReloadTriggeredOnce) {
                return;
            }
        }
        triggerReload(false);
    }

    public synchronized boolean isLoading() {
        return mIsLoading;
    }

    public interface Listener<T> {
        void onReloadDone(T loader);
    }

    public void addListener(Listener<T> listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener<T> listener) {
        mListeners.remove(listener);
    }

    protected void notifyListeners() {
        for (Listener<T> listener : mListeners) {
            //noinspection unchecked
            listener.onReloadDone((T) this);
        }
    }

    public synchronized void setSwipeRefreshLayout(SwipeRefreshLayout swipeRefreshLayout) {
        this.mSwipeRefreshLayout = swipeRefreshLayout;
        if (swipeRefreshLayout == null) {
            return;
        }
        //是否刷新
        swipeRefreshLayout.setRefreshing(mIsLoading);
        swipeRefreshLayout.setOnRefreshListener(this);
    }

    @Override
    public void onRefresh() {
        triggerReload(true);
    }

    private synchronized void updateProgressIndicator() {
        if (mSwipeRefreshLayout == null) {
            return;
        }

        XposedApp.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (Loader.this) {
                    //在主线程中更新控件
                    if (mSwipeRefreshLayout != null) {
                        /**
                         * 是否刷新 到这里就是说作者是按照SwipeRefreshLoayout机制对应用更新
                         * 但自己设置的条件进行跟新，比如mIsLoading，无论请求刷新多少次mIsLoading为false不更新
                         * SwipeRefreshLoayout 下拉刷新 true为刷新
                         */
                        mSwipeRefreshLayout.setRefreshing(mIsLoading);
                    }
                }
            }
        });
    }
}
