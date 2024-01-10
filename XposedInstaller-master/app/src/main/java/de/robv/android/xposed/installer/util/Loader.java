package de.robv.android.xposed.installer.util;

import android.support.v4.widget.SwipeRefreshLayout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.installer.XposedApp;
/**
 * 这个类里主要是为了对SwipeRefreshLayout进行拉下控件刷新
 * 对linstener<t>进行通知    对于继承的loader他们数据是独立的
 * 
 * 在三个加载类中对应着repo数据库加载,online线上加载,local本地加载
 */
public abstract class Loader<T> implements SwipeRefreshLayout.OnRefreshListener {
    //获取当前对象所属类的简单名称
    protected final String CLASS_NAME = getClass().getSimpleName();
    private boolean mIsLoading = false;
    //trigger触发
    private boolean mReloadTriggeredOnce = false;
    /**
     * Listener是自定义的一个接口
     * CopyOnWriteArrayList<>() 是对 List 接口的实现类，它是线程安全的，支持并发读写操作。
     * 它是通过在写操作时创建底层数组的副本来实现线程安全，因此适用于读操作频繁、写操作相对较少的场景。
     * 
     * <T>为listener监听里要处理的数据
     * 这里测试过在不加static前提下子类数据不会被共享
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
        //shouldUpdate方法当被子类覆盖时，子类调用triggerReload这个shouldUpdate会用子类的
        if (!force && !shouldUpdate()) {
            return;
        }
        synchronized (this) {
            //如果正在加载退出
            if (mIsLoading) {
                return;
            }
            mIsLoading = true;
            //mSwipeRefreshLayout控件下拉刷新
            updateProgressIndicator();
        }

        //调用子类的onReload();
        new Thread("Reload" + CLASS_NAME) {
            public void run() {
                boolean hasChanged = onReload();
                if (hasChanged) {
                    //通知所有的监听者
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

    //这是控件刷新时操作
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
                         * 是否控件是为刷新状态,false关闭控件刷新,true开启控件刷新
                         */
                        mSwipeRefreshLayout.setRefreshing(mIsLoading);
                    }
                }
            }
        });
    }
}
