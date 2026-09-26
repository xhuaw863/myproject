package com.yb.hi.framework.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 字典简码工具: 按名称生成拼音首字母缩写(供 py_code 列检索辅助输入)。
 * 规则: 汉字取拼音首字母(pinyin4j 首选音, 多音字取默认读音), 英文/数字保留原字符,
 * 符号与空格跳过, 输出大写, 截断 64。个别多音字不准可由维护页"自定义码"(abbr_code)人工补正。
 */
public final class PinyinUtil {

    private static final int MAX_LEN = 64;

    private static final HanyuPinyinOutputFormat FMT = new HanyuPinyinOutputFormat();

    static {
        FMT.setCaseType(HanyuPinyinCaseType.UPPERCASE);
        FMT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FMT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUtil() {
    }

    /** 生成拼音首字母简码; 名称为空返回 null。 */
    public static String initials(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                continue;
            }
            if (isChinese(ch)) {
                String py = firstPinyin(ch);
                if (py != null && !py.isEmpty()) {
                    sb.append(py.charAt(0));
                }
            } else if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toUpperCase(ch));
            }
            // 其它符号跳过
            if (sb.length() >= MAX_LEN) {
                break;
            }
        }
        String r = sb.length() > MAX_LEN ? sb.substring(0, MAX_LEN) : sb.toString();
        return r.isEmpty() ? null : r;
    }

    private static boolean isChinese(char ch) {
        try {
            return Character.toString(ch).getBytes("GB2312").length > 1;
        } catch (Exception e) {
            return ch > 128 && ch >= 0x4E00 && ch <= 0x9FFF;
        }
    }

    /** 取该汉字首选拼音(全拼, 大写); 无读音返回 null。 */
    private static String firstPinyin(char ch) {
        try {
            String[] arr = PinyinHelper.toHanyuPinyinStringArray(ch, FMT);
            if (arr != null && arr.length > 0) {
                return arr[0];
            }
        } catch (BadHanyuPinyinOutputFormatCombination ignored) {
        }
        return null;
    }

    /** 开发抽查入口(mvn exec 手工调用, 非运行期使用)。 */
    public static void main(String[] args) {
        String[] samples = {"内科门诊", "王医生", "CT检查", "阿莫西林胶囊0.25g", "高血压", "一次性使用无菌注射器"};
        for (String s : samples) {
            System.out.println(s + " -> " + initials(s));
        }
    }
}
