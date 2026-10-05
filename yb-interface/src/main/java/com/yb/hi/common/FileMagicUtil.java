package com.yb.hi.common;

/**
 * 上传文件魔数校验工具: 在扩展名白名单之外增加文件头校验, 防止伪造扩展名上传非预期内容。
 */
public final class FileMagicUtil {

    private FileMagicUtil() {
    }

    public static boolean matchesImage(byte[] data, String ext) {
        if (data == null || ext == null) {
            return false;
        }
        String e = ext.toLowerCase();
        if ("jpg".equals(e) || "jpeg".equals(e)) {
            return startsWith(data, 0xFF, 0xD8, 0xFF);
        }
        if ("png".equals(e)) {
            return startsWith(data, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        }
        if ("gif".equals(e)) {
            return startsWith(data, 'G', 'I', 'F', '8', '7', 'a')
                    || startsWith(data, 'G', 'I', 'F', '8', '9', 'a');
        }
        if ("bmp".equals(e)) {
            return startsWith(data, 'B', 'M');
        }
        if ("webp".equals(e)) {
            return data.length >= 12
                    && startsWith(data, 'R', 'I', 'F', 'F')
                    && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        }
        return false;
    }

    public static boolean matchesSupplierDoc(byte[] data, String ext) {
        if (data == null || ext == null) {
            return false;
        }
        String e = ext.toLowerCase();
        if ("pdf".equals(e)) {
            return startsWith(data, '%', 'P', 'D', 'F');
        }
        if ("jpg".equals(e) || "jpeg".equals(e) || "png".equals(e)) {
            return matchesImage(data, e);
        }
        if ("zip".equals(e)) {
            return startsWith(data, 'P', 'K', 0x03, 0x04)
                    || startsWith(data, 'P', 'K', 0x05, 0x06)
                    || startsWith(data, 'P', 'K', 0x07, 0x08);
        }
        if ("rar".equals(e)) {
            return startsWith(data, 'R', 'a', 'r', '!', 0x1A, 0x07, 0x00)
                    || startsWith(data, 'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00);
        }
        return false;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
