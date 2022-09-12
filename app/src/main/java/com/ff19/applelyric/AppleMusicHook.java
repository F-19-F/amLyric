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
import android.util.Log;
import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AppleMusicHook {
    private Object mediaCallBack, lyricViewModel, curLyricObj, PlaybackStateCompat, stateLock, LyricsViewFragment,LYRICS;
    private Context context;
    private boolean timeStarted;
    private Handler handler, mainHandler;
    public String TAG = "applemusiclyric";
    public Class<?> MediaMetadataCompatClass;
    public Constructor lbcConstructor;
    public Lyric curLyrics;
    public LyricInfo curInfo, lastShow;
    public int nextUpdateTime;
    private String last;
    private StatusLyricApi api;


    AppleMusicHook(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader classLoader = lpparam.classLoader;
        curLyrics = new Lyric();
        stateLock = new Object();
        last = new String();
        HandlerThread handlerThread = new HandlerThread("lyric_thread");
        handlerThread.start();
        mainHandler = new Handler(Looper.getMainLooper());
        handler = new Handler(handlerThread.getLooper());
        try {
            MediaMetadataCompatClass = classLoader.loadClass("android.support.v4.media.MediaMetadataCompat");
            Class<?> lbcClass = classLoader.loadClass("lb.c");
            Class<?> LyricsSectionVectorClass = classLoader.loadClass("com.apple.android.music.ttml.javanative.model.LyricsSectionVector");
            lbcConstructor = lbcClass.getConstructor(LyricsSectionVectorClass);
            // 保证在后台依旧更新歌词
            XposedHelpers.findAndHookConstructor("com.apple.android.music.player.fragment.b$c", classLoader, classLoader.loadClass("com.apple.android.music.player.fragment.b"), classLoader.loadClass("androidx.appcompat.widget.t0"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    mediaCallBack = param.thisObject;
                }
            });
            // 歌词fragment对象记录
            XposedHelpers.findAndHookConstructor("com.apple.android.music.player.fragment.PlayerLyricsViewFragment", classLoader, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
//                    Log.d(TAG,"construct");
//                    记录歌词fragment是否已经实例化
                    LyricsViewFragment = param.thisObject;
                }
            });
            // 保证即使在后台也能得到对应对象
            XposedHelpers.findAndHookMethod("android.support.v4.media.session.MediaControllerCompat$a$a", classLoader, "onMetadataChanged", android.media.MediaMetadata.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object metadataCompat = XposedHelpers.callStaticMethod(MediaMetadataCompatClass, "a", param.args[0]);
//                    任何时候都发送歌曲更新信息
                    MediaMetadata metadata = (MediaMetadata) XposedHelpers.getObjectField(metadataCompat,"t");
                    String newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                    synchronized (stateLock) {
                        if (!last.equals(newTitle)) {
                            last = newTitle;
                            if (api != null) {
                                curLyrics.clean();
                                api.stopLyric();
                            }
                        }
                    }
                    mainHandler.post(() -> {
                        if (mediaCallBack != null) {
                            XposedHelpers.callMethod(mediaCallBack, "onMetadataChanged", metadataCompat);
                        }
                    });
//                    Log.d(TAG,metadata.getString(MediaMetadata.METADATA_KEY_ART_URI));

                }
            });
            // 获得类实例以及context
            XposedHelpers.findAndHookConstructor("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel", classLoader, android.app.Application.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    lyricViewModel = param.thisObject;
                    Application application = (Application) param.args[0];
                    context = application.getApplicationContext();
                    api = new StatusLyricApi(context);
                }
            });
            // 加载歌词
            XposedHelpers.findAndHookMethod("com.apple.android.music.player.fragment.PlayerLyricsViewFragment", classLoader, "R1", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    Object A = XposedHelpers.getObjectField(param.thisObject, "A");
                    Object T = XposedHelpers.getObjectField(param.thisObject, "T");
                    Object z = XposedHelpers.getObjectField(param.thisObject, "z");
                    if (lyricViewModel == null) {
                        return;
                    }
                    XposedHelpers.callMethod(lyricViewModel, "loadLyrics", A, T, z);
                }
            });
            // 歌词对应的viewmodel
            XposedHelpers.findAndHookMethod("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel", classLoader, "buildTimeRangeToLyricsMap", classLoader.loadClass("com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    Object SongInfo = XposedHelpers.callMethod(param.args[0], "get");
                    Object LyricsSectionVector = XposedHelpers.callMethod(SongInfo, "getSections");
                    curLyricObj = lbcConstructor.newInstance(LyricsSectionVector);
                    updateLyricDict();
                    if (api != null) {
                        api.stopLyric();
                    }
//                    Log.d(TAG, "update llyric");
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
                            Log.d(TAG, "playBackState is null");
                        }
                        PlaybackState playbackState = (PlaybackState) D;
//                        Log.d(TAG,playbackState.toString());
//                        onUpdate()
                        updateTime();
                    }
                }
            });
            // 获取枚举类对象，对应的歌词fragment
            Class<?> kClass = classLoader.loadClass("com.apple.android.music.player.fragment.n$k");
            for (Object o : kClass.getEnumConstants()) {
//                Log.d(TAG,o.toString());
                if (LyricsViewFragment == null) {
                    if (o.toString().equals("LYRICS")) {
//                        Log.d(TAG,"get object");
                        LYRICS = o;
                    }
                }
            }
            // 优先构造歌词fragment
            XposedHelpers.findAndHookMethod("com.apple.android.music.player.fragment.n", classLoader, "K0", classLoader.loadClass("fb.c"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if(LyricsViewFragment == null){
                        XposedHelpers.setObjectField(param.thisObject,"w",LYRICS);
                    }
                }
            });
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.d(TAG, Log.getStackTraceString(e));
            e.printStackTrace();
        }
    }

    public void updateLyricDict() {
        curLyrics.clean();
        int i = 0;
        Object LyricsLinePtr = XposedHelpers.callMethod(curLyricObj, "a", i);
        while (LyricsLinePtr != null) {
            Object LyricsLine = XposedHelpers.callMethod(LyricsLinePtr, "get");
            String str = (String) XposedHelpers.callMethod(LyricsLine, "getHtmlLineText");
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
                            Log.d(TAG, "exiting");
                            return;
                        }
                        handler.postDelayed(this::run, 400);
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
