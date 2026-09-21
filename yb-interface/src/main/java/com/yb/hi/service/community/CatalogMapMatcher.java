package com.yb.hi.service.community;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 医保目录对照匹配器: 对"医疗机构目录条目"与"标准字典(医保目录)条目"做名称/规格/厂家相似度打分。
 *
 * 打分规则(归一化后比较):
 *  - 名称全同 + 规格全同 = 0.95; 再叠加厂家/单位全同 +0.03, 封顶 0.99;
 *  - 名称全同 = 0.85(厂家全同同样 +0.03 封顶 0.99);
 *  - 名称互含 + 规格全同 = 0.75; 名称互含 = 0.60;
 *  - 否则名称二元组 Jaccard >= 0.6 时 = 0.5*J + (规格全同 ? 0.2 : 0)。
 * 归一化: 小写 + 全角转半角 + 仅保留字母/数字/汉字(去空白与标点)。
 */
public final class CatalogMapMatcher {

    /** 自动写入默认阈值: 仅"名称+规格全同"才允许自动对照 */
    public static final double DEFAULT_AUTO_THRESHOLD = 0.95;

    private CatalogMapMatcher() {
    }

    /** 归一化: 小写、全角转半角、仅保留 [a-z0-9\u4e00-\u9fa5] */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String half = fullToHalf(s).toLowerCase();
        StringBuilder sb = new StringBuilder(half.length());
        for (int i = 0; i < half.length(); i++) {
            char c = half.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c >= 0x4e00 && c <= 0x9fa5)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 全角字符转半角(空格除外, 空格最终会被归一化剔除) */
    private static String fullToHalf(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] >= 0xFF01 && cs[i] <= 0xFF5E) {
                cs[i] = (char) (cs[i] - 0xFEE0);
            } else if (cs[i] == 0x3000) {
                cs[i] = ' ';
            }
        }
        return new String(cs);
    }

    /** 名称二元组集合(长度不足 2 时退化为单字) */
    private static Set<String> bigrams(String norm) {
        Set<String> set = new LinkedHashSet<>();
        if (norm == null || norm.isEmpty()) {
            return set;
        }
        if (norm.length() < 2) {
            set.add(norm);
            return set;
        }
        for (int i = 0; i < norm.length() - 1; i++) {
            set.add(norm.substring(i, i + 2));
        }
        return set;
    }

    /** Jaccard 相似度 |∩|/|∪|, 并集为空返回 0 */
    public static double jaccard(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0d;
        }
        int inter = 0;
        for (String x : sa) {
            if (sb.contains(x)) {
                inter++;
            }
        }
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0d : (double) inter / union;
    }

    /**
     * 打分。返回 [score, reasons...] 不便, 改用结果对象。
     */
    public static Result score(String hospName, String hospSpec, String hospMfr,
                               String stdName, String stdSpec, String stdMfr) {
        String nH = normalize(hospName);
        String nS = normalize(stdName);
        String sH = normalize(hospSpec);
        String sS = normalize(stdSpec);
        String mH = normalize(hospMfr);
        String mS = normalize(stdMfr);

        List<String> reasons = new ArrayList<>();
        double score = 0d;
        if (nH.isEmpty() || nS.isEmpty()) {
            return new Result(0d, reasons);
        }
        boolean nameExact = nH.equals(nS);
        boolean specExact = !sH.isEmpty() && sH.equals(sS);
        boolean mfrExact = !mH.isEmpty() && mH.equals(mS);
        boolean nameContain = nH.contains(nS) || nS.contains(nH);

        if (nameExact) {
            score = specExact ? 0.95 : 0.85;
            reasons.add(specExact ? "名称全同+规格全同" : "名称全同");
            if (mfrExact) {
                score += 0.03;
                reasons.add("厂家/单位全同");
            }
        } else if (nameContain && specExact) {
            score = 0.75;
            reasons.add("名称互含+规格全同");
        } else if (nameContain) {
            score = 0.60;
            reasons.add("名称互含");
        } else {
            double j = jaccard(nH, nS);
            if (j >= 0.6) {
                score = 0.5 * j + (specExact ? 0.2 : 0.0);
                reasons.add(String.format("名称相似(%.2f)", j));
                if (specExact) {
                    reasons.add("规格全同");
                }
            }
        }
        score = Math.min(score, 0.99);
        return new Result(score, reasons);
    }

    /** 打分结果 */
    public static class Result {
        private final double score;
        private final List<String> reasons;

        public Result(double score, List<String> reasons) {
            this.score = score;
            this.reasons = reasons;
        }

        public double getScore() {
            return score;
        }

        public List<String> getReasons() {
            return reasons;
        }
    }
}
