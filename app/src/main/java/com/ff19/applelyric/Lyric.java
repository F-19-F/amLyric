package com.ff19.applelyric;

import android.util.Log;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lyric {
    public Map<Integer, LyricInfo> lyricInfoMap;
    public List<Integer> points;
    public Object changeLock;

    public Lyric() {
        lyricInfoMap = new HashMap<>();
        points = new ArrayList<>();
        changeLock = new Object();
    }

    public void addInfo(int begin, int end, String str) {
        synchronized (changeLock) {
            points.add(begin);
            lyricInfoMap.put(begin, new LyricInfo(begin, end, str));
        }
    }

    public LyricInfo getLyricByPosition(long pos) {
//        Log.d("applemusiclyric","getLyric now pos ="+pos);
        synchronized (changeLock) {
            if(points.size()==0){
                return null;
            }
            int bPos = 0;
            int i = 0;
            int len = points.size();
            while (i < len){
                if(pos>points.get(i)){
                    bPos = points.get(i);
                }
                i++;
            }
            return lyricInfoMap.get(bPos);
        }
    }

    public void clean() {
        synchronized (changeLock) {
            this.points.clear();
            this.lyricInfoMap.clear();
        }
    }

    @Override
    public String toString() {
        return "Lyric{" +
                "lyricInfoMap=" + lyricInfoMap +
                ", points=" + points +
                '}';
    }
}
