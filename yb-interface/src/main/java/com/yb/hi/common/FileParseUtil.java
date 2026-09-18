package com.yb.hi.common;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 字典文件解析工具
 * 医保字典下载返回的是ZIP压缩包，内含TXT文件，各数据项以TAB分隔
 */
@Slf4j
public class FileParseUtil {

    /**
     * 解压ZIP并解析TXT内容为行列表
     * 每行按TAB分割为字段数组
     */
    public static List<String[]> parseZipToRows(byte[] zipBytes) {
        List<String[]> allRows = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".txt") || entry.getName().endsWith(".TXT")) {
                    log.info("解析字典文件: {}", entry.getName());
                    List<String[]> rows = parseTxtStream(zis);
                    allRows.addAll(rows);
                    log.info("解析完成, 共{}条记录", rows.size());
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.error("解压字典文件失败", e);
        }
        return allRows;
    }

    /**
     * 解析TXT流，按行读取，TAB分割
     */
    private static List<String[]> parseTxtStream(InputStream is) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                rows.add(fields);
            }
        }
        return rows;
    }

    /**
     * 安全获取字段值(防越界)
     */
    public static String getField(String[] row, int index) {
        if (row == null || index >= row.length) {
            return "";
        }
        String val = row[index];
        return "null".equals(val) ? "" : val;
    }

    /**
     * 保存文件到本地
     */
    public static void saveFile(byte[] data, String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
        log.info("文件已保存: {}", filePath);
    }
}
