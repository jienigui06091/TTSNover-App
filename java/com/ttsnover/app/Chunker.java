package com.ttsnover.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一章正文切成适合朗读的小段（纯 Java，无 Android 依赖，可在桌面上单测）。
 * 目标 90 字左右：Fish Audio 语速 1.18 时约 4.9 字/秒，90 字 ≈ 18 秒，句间空档小。
 */
public class Chunker {
    public static final int TARGET = 90;
    public static final int MAX = 130;
    private static final String SOFT = "。！？!?…；;\n";
    private static final String HARD = "，,、：: ";

    public static List<String> split(List<String> paras) {
        StringBuilder all = new StringBuilder();
        if (paras != null) {
            for (String p : paras) {
                if (p == null) continue;
                String t = p.trim();
                if (t.isEmpty()) continue;
                if (all.length() > 0) all.append('\n');
                all.append(t);
            }
        }
        return splitText(all.toString());
    }

    public static List<String> splitText(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String s = text.replace("\r", "").trim();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n) break;
            int end = Math.min(n, i + MAX);
            if (end < n) {
                int cut = -1;
                // 先找句末标点（在 TARGET 之后）
                for (int k = i + TARGET; k < end; k++) {
                    if (SOFT.indexOf(s.charAt(k)) >= 0) { cut = k + 1; break; }
                }
                if (cut < 0) {
                    for (int k = i + TARGET; k < end; k++) {
                        if (HARD.indexOf(s.charAt(k)) >= 0) { cut = k + 1; break; }
                    }
                }
                if (cut > 0) end = cut;
            }
            String piece = s.substring(i, end).trim();
            if (!piece.isEmpty()) out.add(piece);
            i = end;
        }
        return out;
    }
}
