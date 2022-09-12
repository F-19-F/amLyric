package com.ff19.applelyric;

public class LyricInfo {
    public String lyricStr;
    public int begin,end;
    LyricInfo(int begin, int end, String lyricStr){
        this.begin = begin;
        this.end = end;
        this.lyricStr = lyricStr;
    }

    @Override
    public String toString() {
        return "LyricInfo{" +
                "lyricStr='" + lyricStr + '\'' +
                ", begin=" + begin +
                ", end=" + end +
                '}';
    }
}
