package com.ff19.applelyric;

import android.app.Application;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.Enumeration;
import java.util.Locale;

import dalvik.system.DexFile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AppleMusicHook {
    private Object curLyricObj;
    private Object curSongInfo;
    private Object PlaybackStateCompat;
    private final Object stateLock;
    private Context context;
    private boolean timeStarted;
    private final Handler handler;
    private final Handler mainHandler;
    public String TAG = "lyricApple";
    public Class<?> MediaMetadataCompatClass, LocaleUtilClass, StringVector$StringVectorNativeCls;
    public Constructor lyricConvertConstructor, LyricReqConstructor;
    public Lyric curLyrics;
    public LyricInfo curInfo, lastShow;
    public int nextUpdateTime;
    private String last;
    private String locale;
    private StatusLyricApi api;
    private String curId;
    private boolean requested;


    AppleMusicHook(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader classLoader = lpparam.classLoader;
        curLyrics = new Lyric();
        stateLock = new Object();
        last = "";
        HandlerThread handlerThread = new HandlerThread("lyric_thread");
        handlerThread.start();
        mainHandler = new Handler(Looper.getMainLooper());
        handler = new Handler(handlerThread.getLooper());
        requested = false;

        try {
            MediaMetadataCompatClass = classLoader.loadClass("android.support.v4.media.MediaMetadataCompat");
            Class<?> LyricsSectionVectorClass = classLoader.loadClass("com.apple.android.music.ttml.javanative.model.LyricsSectionVector");
            LocaleUtilClass = classLoader.loadClass("com.apple.android.music.playback.util.LocaleUtil");
            StringVector$StringVectorNativeCls = classLoader.loadClass("com.apple.android.mediaservices.javanative.common.StringVector$StringVectorNative");
            Class<?> musicCls = classLoader.loadClass("com.apple.android.music.model.Song");
            XposedHelpers.findAndHookMethod("com.apple.android.music.playback.util.LocaleUtil", classLoader, "getSystemLyricsLanguage", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
//                    简体中文
                    param.setResult("zh-Hans");
                }
            });
            locale = (String) XposedHelpers.callStaticMethod(LocaleUtilClass, "getSystemLyricsLanguage");

            new Thread(() -> {
                try {
                    Class OnLoadCallbackClass = classLoader.loadClass("com.apple.android.music.ttml.javanative.LyricsController$LyricsControllerNative$OnLoadCallback");
                    DexFile dexFile = new DexFile(lpparam.appInfo.sourceDir);
                    Enumeration<String> classNames = dexFile.entries();
                    while (classNames.hasMoreElements()) {
                        String classname = classNames.nextElement();
                        try {
                            Class<?> cls = lpparam.classLoader.loadClass(classname);
                            if (cls.getSuperclass() == OnLoadCallbackClass) {
//                                callback
                                handleLyricReqHook(cls, classLoader);

                            } else {
                                for (Constructor<?> constructor : cls.getConstructors()) {
                                    if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] == LyricsSectionVectorClass && cls.getFields().length == 1) {
//                                            Log.d(TAG, "found constructor " + classname);
                                        lyricConvertConstructor = constructor;
                                    }
                                }
                            }
                        } catch (Throwable e) {

                        }

                    }
                } catch (Throwable e) {

                }
            }).start();

            XposedHelpers.findAndHookMethod("com.apple.android.music.model.BaseContentItem", classLoader, "setId", java.lang.String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    String trace = Log.getStackTraceString(new Exception());
                    if (musicCls.isInstance(param.thisObject) && (trace.contains("getItemAtIndex") && trace.contains("i7.u.accept") || trace.contains("e3.h.w"))) {
                        curId = (String) param.args[0];
                        Log.d(TAG, "cur music id :" + curId + "request lyric now");
                        reqLyric(Long.parseLong(curId), 0, Locale.getDefault().getLanguage() + "_" + Locale.getDefault());
                    }
                }
            });
//            hook metaDATA change
            XposedHelpers.findAndHookMethod("android.support.v4.media.session.MediaControllerCompat$a$a", classLoader, "onMetadataChanged", android.media.MediaMetadata.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object metadataCompat = XposedHelpers.callStaticMethod(MediaMetadataCompatClass, "a", param.args[0]);
                    MediaMetadata metadata = (MediaMetadata) XposedHelpers.getObjectField(metadataCompat, "t");
                    String newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                    synchronized (stateLock) {
                        if (!last.equals(newTitle)) {
                            last = newTitle;
                            Log.d(TAG, "new song " + newTitle);
                            requested = false;
                            if (api != null) {
                                curLyrics.clean();
                                api.stopLyric();
                            }
                        }
                    }

                }
            });
            // 获得类实例以及context
            XposedHelpers.findAndHookMethod("com.apple.android.music.AppleMusicApplication", classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    Application application = (Application) param.thisObject;
                    context = application.getBaseContext();
                    api = new StatusLyricApi(context);
                }
            });
            // 播放状态，时间，是否暂停等
            XposedHelpers.findAndHookMethod("android.support.v4.media.session.MediaControllerCompat$a$b", classLoader, "handleMessage", android.os.Message.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    Message m = (Message) param.args[0];
                    if (m.what == 2) {
                        synchronized (stateLock) {
                            PlaybackStateCompat = m.obj;
                        }
                        Object D = XposedHelpers.getObjectField(PlaybackStateCompat, "D");
                        if (D == null) {
//                            Log.d(TAG, "playBackState is null");
                            return;
                        }
                        updateTime();
                    }
                }
            });
        } catch (Throwable e) {
            Log.d(TAG, Log.getStackTraceString(e));
        }
    }


    public void handleLyricReqHook(Class<?> callBack, ClassLoader classLoader) {
        try {
            Class<?> LyricReqCls = callBack.getEnclosingClass();
            LyricReqConstructor = LyricReqCls.getConstructor(Context.class, long.class, long.class, long.class, StringVector$StringVectorNativeCls, boolean.class);
            XposedHelpers.findAndHookMethod(callBack, "call", classLoader.loadClass("com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr"), int.class, long.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if(lyricConvertConstructor == null){
                        return;
                    }
                    Object appleCb = XposedHelpers.getObjectField(param.thisObject, "s");
                    curSongInfo = XposedHelpers.callMethod(param.args[0], "get");
                    if (curSongInfo == null) {
                        if (appleCb == null) {
                            param.setResult(null);
                            return;
                        }
                        return;
                    }
                    Object LyricsSectionVector = XposedHelpers.callMethod(curSongInfo, "getSections");
                    curLyricObj = lyricConvertConstructor.newInstance(LyricsSectionVector);
                    updateLyricDict();
                    Log.d(TAG, "load new lyrics");
                    if (api != null) {
                        api.stopLyric();
                    }
                    if (appleCb == null) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable e) {
            Log.d(TAG, Log.getStackTraceString(e));
        }
    }

    public void reqLyric(long songId, long songQueue, String locale) {
        try {
            synchronized (stateLock) {
                if (requested || LyricReqConstructor == null) {
                    return;
                }
            }
            requested = true;
            Object localeVector = StringVector$StringVectorNativeCls.newInstance();
            String[] strArr = new String[1];
            strArr[0] = locale;
//            songQueue = 42949672960l;
            XposedHelpers.callMethod(localeVector, "put", (Object) strArr);
//            songQueue = 42949672960l;
            Object lyricClient = LyricReqConstructor.newInstance(context, songId, songId, songQueue, localeVector, false);
            XposedHelpers.callMethod(lyricClient, "subscribe", (Object) null);
            mainHandler.postDelayed(() -> {
                synchronized (stateLock) {
                    requested = false;
                }
            }, 1000);
        } catch (Throwable e) {
            Log.d(TAG, Log.getStackTraceString(e));
        }
    }

    public void updateLyricDict() {
        curLyrics.clean();
        int i = 0;
        Object LyricsLinePtr = XposedHelpers.callMethod(curLyricObj, "a", i);
        while (LyricsLinePtr != null) {
            Object LyricsLine = XposedHelpers.callMethod(LyricsLinePtr, "get");
            String str = (String) XposedHelpers.callMethod(LyricsLine, "getHtmlLineText");
            String transkey = (String) XposedHelpers.callMethod(LyricsLine, "getTranslationKey");
            if (!TextUtils.isEmpty(transkey)) {
                String trans = (String) XposedHelpers.callMethod(curSongInfo, "getTranslation", locale, transkey);
                if (trans != null && !trans.isEmpty()) {
                    str = trans;
                }
            }
            int begin = (int) XposedHelpers.callMethod(LyricsLine, "getBegin");
            int end = (int) XposedHelpers.callMethod(LyricsLine, "getEnd");
//            Log.d(TAG,str);
            curLyrics.addInfo(begin, end, str);
            i++;
            LyricsLinePtr = XposedHelpers.callMethod(curLyricObj, "a", i);
        }
//            Log.d(TAG,curLyrics.toString());
    }

    public void updateTime() {
        synchronized (stateLock) {
            if (timeStarted) {
                return;
            }
            timeStarted = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (stateLock) {
                        Object D = XposedHelpers.getObjectField(PlaybackStateCompat, "D");
                        if (D == null) {
                            Log.d(TAG, "playBackState is null");
                        }
                        PlaybackState playbackState = (PlaybackState) D;
                        if (!onUpdate(playbackState)) {
                            timeStarted = false;
                            return;
                        }
                        handler.postDelayed(this, 400);
                    }
                }
            }, 400);

        }
    }

    public boolean onUpdate(PlaybackState playbackState) {
        if (playbackState.getState() == PlaybackState.STATE_PAUSED) {
            nextUpdateTime = 0;
            curInfo = null;
            return false;
        }
        long currentPosition = (long) (((SystemClock.elapsedRealtime() - playbackState.getLastPositionUpdateTime()) * playbackState.getPlaybackSpeed()) + playbackState.getPosition());
        curInfo = curLyrics.getLyricByPosition(currentPosition);
        if (curInfo != null) {
            nextUpdateTime = curInfo.end;
            if (currentPosition > nextUpdateTime) {
                curInfo = curLyrics.getLyricByPosition(currentPosition);
            }
            if (lastShow != curInfo) {
                if (api != null) {
                    api.sendLyric(curInfo.lyricStr);
                }
//                Log.d(TAG, curInfo.lyricStr);
                lastShow = curInfo;
            }

        }
        return true;
    }
}
