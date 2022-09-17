package com.ff19.applelyric;

import android.content.Context;
import android.content.Intent;

public class StatusLyricApi {
    private final Context context;
    StatusLyricApi(Context context){
        this.context = context;
    }
    public void sendLyric(String str){
        Intent intent = new Intent();
        intent.setAction("Lyric_Server");
        intent.putExtra("Lyric_Data",str);
        intent.putExtra("Lyric_Icon","");
        intent.putExtra("Lyric_Type","app");
        intent.putExtra("Lyric_PackName","com.apple.android.music");
        intent.putExtra("Lyric_UseSystemMusicActive",false);
        context.sendBroadcast(intent);
    }
    public void stopLyric(){
        Intent intent = new Intent();
        intent.setAction("Lyric_Server");
        intent.putExtra("Lyric_Type","app_stop");
        context.sendBroadcast(intent);
    }
}
