package de.robv.android.xposed.installer.installation;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.zip.ZipFile;

import de.robv.android.xposed.installer.util.FrameworkZips;
import de.robv.android.xposed.installer.util.InstallZipUtil;
import de.robv.android.xposed.installer.util.InstallZipUtil.ZipCheckResult;
import de.robv.android.xposed.installer.util.RootUtil;

import static de.robv.android.xposed.installer.util.InstallZipUtil.closeSilently;
import static de.robv.android.xposed.installer.util.InstallZipUtil.reportMissingFeatures;
import static de.robv.android.xposed.installer.util.InstallZipUtil.triggerError;
/**Parcelable是在intend中存放数据集中的类
 * 可以将Parcel看成是一个流，通过writeToParcel把对象写到流里面，在通过createFromParcel从流里读取对象
*/
public abstract class Flashable implements Parcelable {
    public static final String KEY = "flash";

    protected final File mZipPath;
    protected final FrameworkZips.Type mType;
    protected final String mTitle;

    public Flashable(File zipPath, FrameworkZips.Type type, String title) {
        mZipPath = zipPath;
        mType = type;
        mTitle = title;
    }

    //从parcel流里取数据
    protected Flashable(Parcel in) {
        mZipPath = (File) in.readSerializable();
        mType = (FrameworkZips.Type) in.readSerializable();
        mTitle = in.readString();
    }

    public abstract void flash(Context context, FlashCallback callback);

    protected ZipCheckResult openAndCheckZip(FlashCallback callback) {
        // Open the ZIP file.
        ZipFile zip;
        try {
            zip = new ZipFile(mZipPath);
        } catch (IOException e) {
            triggerError(callback, FlashCallback.ERROR_INVALID_ZIP, e.getLocalizedMessage());
            return null;
        }

        // Do some checks.
        ZipCheckResult zipCheck = InstallZipUtil.checkZip(zip);
        if (!zipCheck.isValidZip()) {
            triggerError(callback, FlashCallback.ERROR_INVALID_ZIP);
            closeSilently(zip);
            return null;
        }
        //xposedprop是否为空
        if (zipCheck.hasXposedProp()) {
            Set<String> missingFeatures = zipCheck.getXposedProp().getMissingInstallerFeatures();
            if (!missingFeatures.isEmpty()) {
                reportMissingFeatures(missingFeatures);
                triggerError(callback, FlashCallback.ERROR_INSTALLER_NEEDS_UPDATE);
                closeSilently(zip);
                return null;
            }
        }

        return zipCheck;
    }

    public FrameworkZips.Type getType() {
        return mType;
    }

    public String getTitle() {
        return mTitle;
    }

    //给rootutil什么指令
    public RootUtil.RebootMode getRebootMode() {
        return RootUtil.RebootMode.NORMAL;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    //存入数据给parcel
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeSerializable(mZipPath);
        dest.writeSerializable(mType);
        dest.writeString(mTitle);
    }
}
