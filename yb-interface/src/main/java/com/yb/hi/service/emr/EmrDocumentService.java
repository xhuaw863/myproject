package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.framework.common.BizException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 病历文档编解码服务(结构化病历"双轨"存储核心):
 *  1) 密文轨: Tiptap/ProseMirror JSON 以 AES-GCM 加密落库(合规要求), 密文格式 = Base64( IV(12字节) + 密文 + 认证标签(16字节) );
 *  2) 字段轨: 递归解析文档树抽取全部 emrField 节点(fieldKey/value/valueType/dictSource), 同步 his_emr_element
 *     数据元, 支撑字段检索/统计/病案首页透视/上报(数据共享)。
 *
 * 密钥: sys_param[emr.encryption.key](hex; 32位hex=16字节按 AES-128-GCM, 64位hex=32字节按 AES-256-GCM)。
 *  @PostConstruct 启动预热 + 首次使用懒加载, 双检锁缓存 SecretKey; 密钥缺失或长度非法视为配置错误直接抛业务异常, 不静默降级明文。
 * 兼容: 载入时历史明文(以 { 或 [ 开头, 或非密文/解密失败)一律原样透传, 保证旧病历可读。
 * 调用姿势: 调用方持有病历实体(住院 his_inp_medical_record / 门诊 his_visit), 调 {@link #saveDocument} 取密文自行落库,
 *  调 {@link #loadDocument} 取明文渲染; 要素表同步经 EmrElementService 完成, 失败不阻断主流程。
 */
@Slf4j
@Service
public class EmrDocumentService {

    /** 加密密钥参数键(sys_param 全局定义行, allow_scope=0) */
    private static final String KEY_PARAM = "emr.encryption.key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** GCM 标准 IV 长度: 96 bit */
    private static final int GCM_IV_BYTES = 12;
    /** GCM 认证标签长度: 128 bit(doFinal 输出自动附加于密文尾部) */
    private static final int GCM_TAG_BITS = 128;
    /** 文本值截断长度(与 his_emr_element.value_text 列长一致) */
    private static final int TEXT_MAX = 2000;
    private static final DateTimeFormatter DT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DT_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final EmrElementService elementService;
    private final EmrFragmentService fragmentService;

    /** 密钥缓存(懒加载+双检锁; volatile 保证多线程可见性) */
    private volatile SecretKey cachedKey;

    public EmrDocumentService(JdbcTemplate jdbcTemplate, EmrElementService elementService,
                              EmrFragmentService fragmentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.elementService = elementService;
        this.fragmentService = fragmentService;
    }

    /* ==================== 加密 / 解密 ==================== */

    /**
     * AES-GCM 加密 Tiptap JSON。
     * 输出 = Base64( IV(12字节随机) + 密文 + 认证标签(16字节) ); null/空白原样返回(不产生密文)。
     * 密钥缺失/非法抛 {@link BizException}(合规要求, 不降级明文落库)。
     */
    public String encrypt(String plainJson) {
        if (!StringUtils.hasText(plainJson)) {
            return plainJson;
        }
        SecretKey key = secretKey();
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[GCM_IV_BYTES + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, GCM_IV_BYTES);
            System.arraycopy(cipherText, 0, packed, GCM_IV_BYTES, cipherText.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            log.error("病历内容加密失败: {}", e.getMessage(), e);
            throw new BizException(500, "病历内容加密失败: " + e.getMessage());
        }
    }

    /**
     * 解密回明文 Tiptap JSON(向后兼容: 历史明文原样返回)。
     * 判定次序: 以 { / [ 开头 → 明文透传; 解密失败(非密文/密钥不匹配/长度不足) → 告警并按明文透传。
     */
    public String decrypt(String encryptedBase64) {
        if (!StringUtils.hasText(encryptedBase64)) {
            return encryptedBase64;
        }
        String input = encryptedBase64.trim();
        if (looksLikePlainJson(input)) {
            return encryptedBase64;
        }
        SecretKey key = secretKey();
        try {
            byte[] packed = Base64.getDecoder().decode(input);
            if (packed.length <= GCM_IV_BYTES) {
                log.warn("病历密文长度不足({}字节), 判定为明文原样返回", packed.length);
                return encryptedBase64;
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, GCM_IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(packed, GCM_IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("病历内容解密失败(按明文原样返回): {}", e.getMessage());
            return encryptedBase64;
        }
    }

    /* ==================== 双轨编排 ==================== */

    /**
     * 病历文档双轨保存:
     *  1) 加密 JSON(密文由调用方更新至病历实体); 2) 抽取 emrField 要素; 3) 同步 his_emr_element(先删后插)。
     *
     * @param scope      1住院 2门诊
     * @param refId      住院 his_inp_medical_record.id / 门诊 his_visit.id
     * @param tiptapJson Tiptap/ProseMirror 明文 JSON(null/空白不触碰任何数据, 原样返回)
     * @param staffId    书写人ID(his_staff.id; 仅作审计日志, 要素行医生归属自动解析病历/就诊实体)
     * @return 加密后的内容串(供调用方更新至病历实体)
     */
    public String saveDocument(int scope, Long refId, String tiptapJson, Long staffId) {
        if (!StringUtils.hasText(tiptapJson)) {
            return tiptapJson;
        }
        String encrypted = resolveAndExtract(scope, refId, tiptapJson);
        log.info("病历文档双轨保存完成: scope={}, refId={}, staffId={}", scope, refId, staffId);
        return encrypted;
    }

    /**
     * 双轨保存(无操作人口径): 加密 + 抽取 + 同步数据元, 返回密文。
     * 与 {@link #saveDocument} 等价, 便于无登录/批处理场景复用。
     */
    public String resolveAndExtract(int scope, Long refId, String tiptapJson) {
        if (!StringUtils.hasText(tiptapJson)) {
            return tiptapJson;
        }
        String encrypted = encrypt(tiptapJson);
        List<EmrFieldValue> fields = extractFields(tiptapJson);
        elementService.syncFromTiptap(scope, refId, fields);
        log.info("病历文档字段抽取: scope={}, refId={}, 要素{}条", scope, refId, fields.size());
        return encrypted;
    }

    /**
     * 载入病历文档: 解密回明文 Tiptap JSON(历史明文兼容透传)。
     *
     * @param scope 1住院 2门诊(仅用于日志定位)
     * @param refId 病历/就诊ID(仅用于日志定位)
     */
    public String loadDocument(int scope, Long refId, String encryptedContent) {
        String plain = decrypt(encryptedContent);
        log.debug("病历文档载入: scope={}, refId={}", scope, refId);
        return plain;
    }

    /* ==================== Tiptap 字段抽取 ==================== */

    /** 抽取 Tiptap JSON 中的全部 emrField 节点为要素列表(门面方法, 静态实现见 {@link #parseTiptapFields}) */
    public List<EmrFieldValue> extractFields(String tiptapJson) {
        return parseTiptapFields(tiptapJson);
    }

    /**
     * 抽取 Tiptap JSON 全部 emrField 为 fieldKey→valueText 扁平映射(P3 门诊双轨):
     * 门诊 his_visit.structure 仍维护扁平 JSON(EmrStructureReader 下游兼容), 本方法即双轨派生口径。
     * 同 fieldKey 多值(如 multiSelect)以「、」连接保序; 无有效值的键不入映射。
     */
    public Map<String, String> extractFieldMap(String tiptapJson) {
        Map<String, String> out = new LinkedHashMap<>();
        for (EmrFieldValue f : parseTiptapFields(tiptapJson)) {
            if (f == null || !StringUtils.hasText(f.getFieldKey()) || !StringUtils.hasText(f.getValueText())) {
                continue;
            }
            out.merge(f.getFieldKey(), f.getValueText(), (a, b) -> a + "、" + b);
        }
        return out;
    }

    /**
     * 静态抽取实现(供 EmrElementService 等复用, 避免服务间循环依赖):
     * 解析 ProseMirror 文档树 {type:"doc", content:[{type:"emrSection", content:[{type:"emrField", attrs:{...}}]}]},
     * 递归任意深度收集 type=emrField 的节点; value 为数组(multiSelect/table 类)时逐元素展开为多行。
     * 跳过: fieldKey 缺失、value 为空、valueType=signature(签名大图不进数据元表, 与模板同步口径一致)。
     * JSON 解析失败仅告警并返回空列表(不抛异常)。
     */
    public static List<EmrFieldValue> parseTiptapFields(String tiptapJson) {
        List<EmrFieldValue> out = new ArrayList<>();
        if (!StringUtils.hasText(tiptapJson)) {
            return out;
        }
        try {
            JSONObject doc = JSON.parseObject(tiptapJson);
            if (doc != null) {
                walkNode(doc, out);
            }
        } catch (Exception e) {
            log.warn("Tiptap JSON 解析失败, 返回空要素列表: {}", e.getMessage());
        }
        return out;
    }

    /** 递归遍历节点树(任意深度): 命中 emrField 收集, 并向 content 子节点下钻 */
    private static void walkNode(JSONObject node, List<EmrFieldValue> out) {
        if (node == null) {
            return;
        }
        if ("emrField".equalsIgnoreCase(str(node.get("type")))) {
            collectField(node, out);
        }
        JSONArray content = node.getJSONArray("content");
        if (content == null) {
            return;
        }
        for (int i = 0; i < content.size(); i++) {
            Object child = content.get(i);
            if (child instanceof JSONObject) {
                walkNode((JSONObject) child, out);
            }
        }
    }

    /** 收集单个 emrField 节点的 attrs(fieldKey/value/valueType/dictSource), 数组值逐元素展开 */
    private static void collectField(JSONObject node, List<EmrFieldValue> out) {
        JSONObject attrs = node.getJSONObject("attrs");
        if (attrs == null) {
            return;
        }
        String fieldKey = str(attrs.get("fieldKey"));
        if (!StringUtils.hasText(fieldKey)) {
            return;
        }
        String valueType = str(attrs.get("valueType"));
        if ("signature".equalsIgnoreCase(valueType)) {
            return;
        }
        String dictSource = str(attrs.get("dictSource"));
        String label = firstStr(attrs, "label", "fieldLabel");
        Object value = attrs.get("value");
        if (value == null) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.size(); i++) {
                emit(out, fieldKey, label, valueType, dictSource, arr.get(i));
            }
        } else {
            emit(out, fieldKey, label, valueType, dictSource, value);
        }
    }

    /** 单值 → 要素行(按 valueType 解析数值/日期; 对象值取 code/name, 无则落原始 JSON) */
    private static void emit(List<EmrFieldValue> out, String fieldKey, String label,
                             String valueType, String dictSource, Object v) {
        if (v == null) {
            return;
        }
        EmrFieldValue f = new EmrFieldValue();
        f.setFieldKey(fieldKey);
        f.setFieldLabel(label);
        f.setValueType(valueType);
        f.setDictSource(dictSource);
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            String code = firstStr(o, "code", "value", "termCode");
            String name = firstStr(o, "name", "label", "text", "diagName", "itemName");
            if (StringUtils.hasText(code) || StringUtils.hasText(name)) {
                f.setTermCode(code);
                f.setValueText(truncate(StringUtils.hasText(name) ? name : code));
            } else {
                f.setValueText(truncate(o.toJSONString()));
            }
        } else {
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equals(s)) {
                return; // 空值不产要素
            }
            f.setValueText(truncate(s));
            if ("number".equalsIgnoreCase(valueType)) {
                f.setValueNum(toBigDecimal(s));
            } else if ("date".equalsIgnoreCase(valueType)) {
                f.setValueDate(parseDate(s));
            } else if ("datetime".equalsIgnoreCase(valueType)) {
                f.setValueDate(parseDateTime(s));
            }
        }
        if (!hasAnyValue(f)) {
            return;
        }
        out.add(f);
    }

    private static boolean hasAnyValue(EmrFieldValue f) {
        return StringUtils.hasText(f.getValueText()) || StringUtils.hasText(f.getTermCode())
                || f.getValueNum() != null || f.getValueDate() != null;
    }

    /* ==================== 病历片段展开(打印口径) ==================== */

    /**
     * 展开文档中的 emrFragment 片段引用(打印/导出用, 库内仍存引用态):
     * 递归遍历 ProseMirror 树收集全部 emrFragment 节点, 经 {@link EmrFragmentService#resolveFragments} 批量解析后
     * 以片段文档的 content 子节点原地替换引用节点; 片段内容中的嵌套片段按需懒加载解析, 祖先链防环。
     * 未解析到内容(片段已删/停用/ID非法/构成环)的节点原样保留, 保证打印件可见占位提示而非内容凭空消失。
     * 无 emrFragment 节点或解析失败时原样返回输入(仅告警, 不阻断打印主流程)。
     */
    public String resolveFragmentsInDocument(String tiptapJson) {
        if (!StringUtils.hasText(tiptapJson)) {
            return tiptapJson;
        }
        try {
            JSONObject doc = JSON.parseObject(tiptapJson);
            if (doc == null) {
                return tiptapJson;
            }
            List<Long> ids = new ArrayList<>();
            collectFragmentIds(doc, ids);
            if (ids.isEmpty()) {
                return tiptapJson;
            }
            // 首批批量解析(减少库交互); attempted 记录已尝试ID, 嵌套片段按需懒加载
            Map<Long, String> resolved = new LinkedHashMap<>(fragmentService.resolveFragments(ids));
            Set<Long> attempted = new HashSet<>(ids);
            int expanded = expandContent(doc.getJSONArray("content"), resolved, attempted, new HashSet<>());
            if (expanded == 0) {
                return tiptapJson; // 全部片段未展开(已删/停用), 保持引用态
            }
            log.info("病历片段展开: 引用{}个, 展开{}个", ids.size(), expanded);
            return doc.toJSONString();
        } catch (Exception e) {
            log.warn("病历片段展开失败(原样返回): {}", e.getMessage());
            return tiptapJson;
        }
    }

    /** 递归收集 emrFragment 节点引用的片段ID(attrs.fragmentId, 支持纯数字或 F-数字 形式) */
    private static void collectFragmentIds(JSONObject node, List<Long> out) {
        if (node == null) {
            return;
        }
        if ("emrFragment".equalsIgnoreCase(str(node.get("type")))) {
            Long id = parseFragmentId(node.getJSONObject("attrs"));
            if (id != null) {
                out.add(id);
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content == null) {
            return;
        }
        for (int i = 0; i < content.size(); i++) {
            Object child = content.get(i);
            if (child instanceof JSONObject) {
                collectFragmentIds((JSONObject) child, out);
            }
        }
    }

    /**
     * 就地展开 content 数组中的 emrFragment 节点: 以片段文档 content 子节点替换引用节点。
     * 嵌套片段: 先递归展开片段内容再拼入; ancestors 记录祖先链片段ID, 构成环时保留占位节点。
     *
     * @return 展开的片段节点数
     */
    private int expandContent(JSONArray content, Map<Long, String> resolved,
                              Set<Long> attempted, Set<Long> ancestors) {
        if (content == null) {
            return 0;
        }
        int expanded = 0;
        for (int i = 0; i < content.size(); ) {
            Object child = content.get(i);
            if (!(child instanceof JSONObject)) {
                i++;
                continue;
            }
            JSONObject node = (JSONObject) child;
            if ("emrFragment".equalsIgnoreCase(str(node.get("type")))) {
                Long fid = parseFragmentId(node.getJSONObject("attrs"));
                String fragDoc = fid == null ? null : resolved.get(fid);
                if (fragDoc == null && fid != null && !attempted.contains(fid)) {
                    // 嵌套片段未在首批批次内, 按需懒加载
                    attempted.add(fid);
                    try {
                        fragDoc = fragmentService.resolveFragments(Collections.singletonList(fid)).get(fid);
                    } catch (Exception e) {
                        log.warn("嵌套片段解析失败: id={}, {}", fid, e.getMessage());
                    }
                    if (fragDoc != null) {
                        resolved.put(fid, fragDoc);
                    }
                }
                JSONArray fragContent = null;
                if (fragDoc != null && fid != null && !ancestors.contains(fid)) {
                    try {
                        JSONObject fragJson = JSON.parseObject(fragDoc);
                        if (fragJson != null) {
                            fragContent = fragJson.getJSONArray("content");
                        }
                    } catch (Exception e) {
                        log.warn("片段文档解析失败: id={}, {}", fid, e.getMessage());
                    }
                }
                if (fragContent != null && !fragContent.isEmpty()) {
                    ancestors.add(fid);
                    expanded += expandContent(fragContent, resolved, attempted, ancestors);
                    ancestors.remove(fid);
                    // 原地替换: 片段内容子节点拼入父数组
                    content.set(i, fragContent.get(0));
                    for (int j = 1; j < fragContent.size(); j++) {
                        content.add(i + j, fragContent.get(j));
                    }
                    i += fragContent.size();
                    expanded++;
                } else {
                    i++; // 未解析到内容或构成环: 保留占位节点原样
                }
                continue;
            }
            JSONArray sub = node.getJSONArray("content");
            if (sub != null) {
                expanded += expandContent(sub, resolved, attempted, ancestors);
            }
            i++;
        }
        return expanded;
    }

    /** 片段ID解析: attrs.fragmentId 支持纯数字(雪花ID)或 "F-123" 前缀形式(取数字尾段); 非数字返回 null */
    private static Long parseFragmentId(JSONObject attrs) {
        if (attrs == null) {
            return null;
        }
        Object v = attrs.get("fragmentId");
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1))) {
            i--;
        }
        if (i == s.length()) {
            return null;
        }
        try {
            return Long.valueOf(s.substring(i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ==================== 密钥加载与缓存 ==================== */

    /** 启动预热: 提前加载密钥入缓存; 启动期 sys_param 未就绪时告警降级, 首次使用时懒加载重试 */
    @PostConstruct
    public void warmupKeyCache() {
        try {
            secretKey();
        } catch (Exception e) {
            log.warn("EMR加密密钥预热失败(将在首次使用时重试): {}", e.getMessage());
        }
    }

    /** 密钥(懒加载+双检锁缓存): 读取 sys_param[emr.encryption.key] hex → SecretKeySpec */
    private SecretKey secretKey() {
        SecretKey k = cachedKey;
        if (k != null) {
            return k;
        }
        synchronized (this) {
            if (cachedKey == null) {
                String hex = loadKeyHex();
                if (!StringUtils.hasText(hex)) {
                    throw new BizException(500, "病历加密密钥未配置, 请检查 sys_param[" + KEY_PARAM + "](32/48/64位hex)");
                }
                byte[] keyBytes = hexToBytes(hex);
                if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
                    throw new BizException(500, "病历加密密钥长度非法(" + hex.length() + "位hex, 需32/48/64位): " + KEY_PARAM);
                }
                if (keyBytes.length == 16) {
                    log.warn("病历加密密钥为16字节(32位hex), 实际按 AES-128-GCM 运行; 如需 AES-256 合规口径请配置64位hex");
                }
                cachedKey = new SecretKeySpec(keyBytes, "AES");
                log.info("EMR病历加密密钥已加载并缓存: hexLen={}, keyBytes={}", hex.length(), keyBytes.length);
            }
            return cachedKey;
        }
    }

    /** 读取全局定义行(scope_level=0, scope_id=0): param_value 优先, 空则回退 default_value */
    private String loadKeyHex() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT param_value, default_value FROM sys_param"
                            + " WHERE param_key = ? AND scope_level = 0 AND scope_id = 0 AND deleted = 0"
                            + " ORDER BY id ASC LIMIT 1", KEY_PARAM);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            Object v = row.get("param_value");
            if (v == null || !StringUtils.hasText(String.valueOf(v))) {
                v = row.get("default_value");
            }
            return v == null ? null : String.valueOf(v).trim();
        } catch (Exception e) {
            log.warn("读取病历加密密钥参数失败(将在下次使用时重试): {}", e.getMessage());
            return null;
        }
    }

    /** hex 串 → 字节数组(AES 合法长度校验交由调用方) */
    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new BizException(500, "病历加密密钥hex长度必须为偶数: " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new BizException(500, "病历加密密钥含非法hex字符");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 明文 JSON 特征: 以 { 或 [ 开头(历史数据兼容判定) */
    private static boolean looksLikePlainJson(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    /* ==================== 解析辅助(与 EmrElementService 口径一致) ==================== */

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > TEXT_MAX ? s.substring(0, TEXT_MAX) : s;
    }

    private static BigDecimal toBigDecimal(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseDate(String s) {
        try {
            String v = s.trim();
            if (v.length() >= 10) {
                v = v.substring(0, 10);
            }
            return LocalDate.parse(v).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String s) {
        String v = s.trim().replace('T', ' ');
        try {
            if (v.length() >= 19) {
                return LocalDateTime.parse(v.substring(0, 19), DT_FULL);
            }
            if (v.length() >= 16) {
                return LocalDateTime.parse(v.substring(0, 16), DT_MIN);
            }
            return LocalDateTime.parse(v, DT_FULL);
        } catch (Exception e) {
            return parseDate(s);
        }
    }

    private static String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.get(k);
            if (v != null && !(v instanceof JSONObject) && !(v instanceof JSONArray)) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equals(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    /* ==================== 抽取要素 DTO ==================== */

    /** 抽取出的单个病历要素(字段轨落 his_emr_element 的行模型) */
    @Data
    public static class EmrFieldValue {

        /** 字段键(与模板 fields.fieldKey / structure 键同口径) */
        private String fieldKey;
        /** 文本值(标量原文本; 对象值取 name 优先, 无 code/name 时落原始 JSON) */
        private String valueText;
        /** 数值值(valueType=number 且可解析) */
        private BigDecimal valueNum;
        /** 日期/时间值(valueType=date/datetime 且可解析) */
        private LocalDateTime valueDate;
        /** 字典来源标识(diag/charge/... 取自 emrField.attrs.dictSource) */
        private String dictSource;
        /** 术语/值域编码(对象值含 code 时, 冗余保真) */
        private String termCode;
        /** 字段显示名(attrs.label, 可空) */
        private String fieldLabel;
        /** 值类型原样保留(text/number/date/datetime/select/dict/...) */
        private String valueType;
    }
}
