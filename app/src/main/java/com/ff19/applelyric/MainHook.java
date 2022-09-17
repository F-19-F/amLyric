package com.ff19.applelyric;


import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;
        switch (packageName){
            case "com.apple.android.music":
                new AppleMusicHook(lpparam);
                break;
            default:
                break;
        }
    }
}
