package com.vestudio.dmmod.formula;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vestudio.dmmod.DamageModernization;

import net.minecraft.resources.ResourceLocation;

/**
 * 数据文件的加载与仓库。
 *
 * <h2>存放位置</h2>
 * <pre>
 *   config/damagemodernization/*.json
 * </pre>
 * 目录下所有 {@code .json} 都会被读取并按顺序合并；同 id 的定义以<b>后加载者为准</b>，
 * 因此模组包可以用一个额外的文件覆盖默认值。
 *
 * <h2>加载策略</h2>
 * <ul>
 *   <li>文件缺省字段使用默认值，因此只需写关心的部分；</li>
 *   <li>每条定义先<b>校验</b>（公式能否解析、变量是否在白名单内），
 *       校验失败的定义被跳过并记录日志，<b>不影响其余定义</b>；</li>
 *   <li>整个目录缺失时不报错，使用内置默认公式运行。</li>
 * </ul>
 *
 * <p>这样设计的目的：一份写错的数据文件不应该让整个 mod 无法工作。
 */
public final class DataRepository {

    /** 数据目录名（位于 config 下）。 */
    private static final String DATA_DIR = DamageModernization.MODID;

    /**
     * 随 mod 发布的默认数据文件（jar 内资源路径）。
     *
     * <p>首次运行时会被释放到配置目录，作为改写的起点，
     * 这样模组包能直接看到完整的字段与默认公式。
     */
    private static final String DEFAULT_RESOURCE =
            DamageModernization.MODID + "-default-data.json";

    /** 宽松的 Gson，允许注释与多余空白，便于手写。 */
    private static final Gson GSON = new GsonBuilder()
            .setLenient()
            .setPrettyPrinting()
            .create();

    /** 已加载的属性定义，按 id 索引。 */
    private static volatile Map<ResourceLocation, AttributeDefinition> attributes = Map.of();

    /** 已加载的乘区定义，按「作用域 / id」索引。 */
    private static volatile Map<ResourceLocation, ZoneDefinition> zones = Map.of();

    /** 是否已完成首次加载。 */
    private static volatile boolean loaded = false;

    private DataRepository() {
    }

    /**
     * 从数据目录加载全部定义。
     *
     * <p>可重复调用（例如配置文件重载），每次都会整体重建。
     *
     * @param configDir 配置根目录
     */
    public static synchronized void load(Path configDir) {
        Path dir = configDir.resolve(DATA_DIR);

        // 首次运行时释放默认数据文件，方便直接查看与改写。
        extractDefaultsIfAbsent(dir);

        Map<ResourceLocation, AttributeDefinition> loadedAttributes = new LinkedHashMap<>();
        Map<ResourceLocation, ZoneDefinition> loadedZones = new LinkedHashMap<>();

        if (!Files.isDirectory(dir)) {
            // 目录不存在属正常情况：使用内置默认公式。
            DamageModernization.LOGGER.info(
                    "数据目录不存在，使用内置默认公式: {}", dir.toAbsolutePath());
            install(loadedAttributes, loadedZones);
            return;
        }

        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            DamageModernization.LOGGER.error("无法读取数据目录 {}", dir, e);
            install(loadedAttributes, loadedZones);
            return;
        }

        for (Path file : files) {
            loadFile(file, loadedAttributes, loadedZones);
        }

        install(loadedAttributes, loadedZones);
    }

    /**
     * 若配置目录尚无数据文件，则把 jar 内的默认数据释放过去。
     *
     * <p>只在文件不存在时写入，<b>绝不覆盖</b>已有文件——
     * 否则玩家的修改会在每次启动时被冲掉。
     *
     * @param dir 数据目录
     */
    private static void extractDefaultsIfAbsent(Path dir) {
        Path target = dir.resolve("damagemodernization.json");
        if (Files.exists(target)) {
            return;
        }

        try (var in = DataRepository.class.getClassLoader()
                .getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                // 资源缺失不是致命问题：没有数据文件时使用内置默认公式。
                DamageModernization.LOGGER.warn("默认数据资源不存在: {}", DEFAULT_RESOURCE);
                return;
            }
            Files.createDirectories(dir);
            Files.copy(in, target);
            DamageModernization.LOGGER.info("已释放默认数据文件到 {}", target.toAbsolutePath());
        } catch (IOException e) {
            DamageModernization.LOGGER.error("释放默认数据文件失败", e);
        }
    }

    /**
     * 加载单个数据文件。
     *
     * @param file       文件路径
     * @param attributes 属性结果集（会被写入）
     * @param zones      乘区结果集（会被写入）
     */
    private static void loadFile(Path file,
                                 Map<ResourceLocation, AttributeDefinition> attributes,
                                 Map<ResourceLocation, ZoneDefinition> zones) {
        JsonElement json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            json = GSON.fromJson(reader, JsonElement.class);
        } catch (Exception e) {
            DamageModernization.LOGGER.error("数据文件解析失败，已跳过: {}", file.getFileName(), e);
            return;
        }

        if (json == null || !json.isJsonObject()) {
            DamageModernization.LOGGER.error("数据文件不是 JSON 对象，已跳过: {}", file.getFileName());
            return;
        }

        ModData data;
        try {
            data = parse(json, file.getFileName().toString());
        } catch (Exception e) {
            // 打印具体原因，避免只看到"结构不合法"却不知错在哪。
            DamageModernization.LOGGER.error(
                    "数据文件结构不合法，已跳过: {} | 原因: {}",
                    file.getFileName(), e.getMessage());
            return;
        }

        for (AttributeDefinition def : data.attributes()) {
            List<String> errors = def.validate();
            if (!errors.isEmpty()) {
                DamageModernization.LOGGER.error(
                        "属性定义被拒绝 {}: {}", def.id(), String.join("; ", errors));
                continue;
            }
            attributes.put(def.id(), def);
        }

        for (ZoneDefinition def : data.zones()) {
            List<String> errors = def.validate();
            if (!errors.isEmpty()) {
                DamageModernization.LOGGER.error(
                        "乘区定义被拒绝 {}: {}", def.key(), String.join("; ", errors));
                continue;
            }
            zones.put(def.key(), def);
        }
    }

    /**
     * 宽松解析数据文件。
     *
     * <h2>为什么不用严格的编解码器</h2>
     * Mojang 的数据编解码器<b>不容忍未知字段</b>，而手写数据文件里
     * {@code _comment} 这类说明字段很常见，一旦出现就会整份文件被拒。
     *
     * <p>此外它的错误信息常常是 {@code null}，难以定位问题。
     * 因此这里改为手工逐字段读取：忽略未知字段、缺省字段用默认值、
     * 出错时给出明确的原因。
     *
     * @param json  JSON 根元素
     * @param where 来源说明（用于报错）
     * @return 解析结果
     */
    private static ModData parse(JsonElement json, String where) {
        JsonObject root = json.getAsJsonObject();

        List<AttributeDefinition> attributes = new ArrayList<>();
        for (JsonElement e : arrayOf(root, "attributes")) {
            attributes.add(parseAttribute(e.getAsJsonObject(), where));
        }

        List<ZoneDefinition> zones = new ArrayList<>();
        for (JsonElement e : arrayOf(root, "zones")) {
            zones.add(parseZone(e.getAsJsonObject(), where));
        }

        return new ModData(attributes, zones);
    }

    /**
     * 读取数组字段；缺失时返回空列表。
     *
     * @param root JSON 对象
     * @param key  字段名
     * @return 数组元素
     */
    private static List<JsonElement> arrayOf(JsonObject root, String key) {
        JsonElement e = root.get(key);
        if (e == null || e.isJsonNull()) {
            return List.of();
        }
        if (!e.isJsonArray()) {
            throw new IllegalStateException(key + " 必须是数组");
        }
        List<JsonElement> out = new ArrayList<>();
        e.getAsJsonArray().forEach(out::add);
        return out;
    }

    /**
     * 解析一条属性定义。
     *
     * @param obj   JSON 对象
     * @param where 来源说明
     * @return 属性定义
     */
    private static AttributeDefinition parseAttribute(JsonObject obj, String where) {
        ResourceLocation id = requireId(obj, "id", where);

        Double def = obj.has("default") && !obj.get("default").isJsonNull()
                ? obj.get("default").getAsDouble()
                : null;

        ResourceLocation vanillaSource = obj.has("vanillaSource") && !obj.get("vanillaSource").isJsonNull()
                ? ResourceLocation.parse(obj.get("vanillaSource").getAsString())
                : null;

        double min = optDouble(obj, "min", 0.0D);
        double max = optDouble(obj, "max", 1_000_000.0D);
        String display = optString(obj, "display", "plain");
        String sentiment = optString(obj, "sentiment", "positive");
        boolean syncable = !obj.has("syncable") || obj.get("syncable").getAsBoolean();
        String comment = optString(obj, "comment", "");

        return AttributeDefinition.of(id, def, vanillaSource, min, max,
                display, sentiment, syncable, comment);
    }

    /**
     * 解析一条乘区定义。
     *
     * @param obj   JSON 对象
     * @param where 来源说明
     * @return 乘区定义
     */
    private static ZoneDefinition parseZone(JsonObject obj, String where) {
        String scope = requireString(obj, "scope", where);
        String id = requireString(obj, "id", where);
        String formula = requireString(obj, "formula", where);

        boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        int priority = obj.has("priority")
                ? obj.get("priority").getAsInt()
                : ZoneDefinition.DEFAULT_PRIORITY;
        double scale = optDouble(obj, "scale", ZoneDefinition.DEFAULT_SCALE);
        String comment = optString(obj, "comment", "");

        List<String> requires = new ArrayList<>();
        for (JsonElement e : arrayOf(obj, "requires")) {
            requires.add(e.getAsString());
        }

        return ZoneDefinition.of(scope, id, enabled, priority, formula, requires, scale, comment);
    }

    /**
     * 读取必填的资源位置字段。
     *
     * @param obj   JSON 对象
     * @param key   字段名
     * @param where 来源说明
     * @return 资源位置
     */
    private static ResourceLocation requireId(JsonObject obj, String key, String where) {
        return ResourceLocation.parse(requireString(obj, key, where));
    }

    /**
     * 读取必填的字符串字段。
     *
     * @param obj   JSON 对象
     * @param key   字段名
     * @param where 来源说明
     * @return 字符串
     */
    private static String requireString(JsonObject obj, String key, String where) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IllegalStateException(where + " 缺少必填字段: " + key);
        }
        return obj.get(key).getAsString();
    }

    /**
     * 读取可选数值字段。
     *
     * @param obj         JSON 对象
     * @param key         字段名
     * @param defaultValue 缺省值
     * @return 数值
     */
    private static double optDouble(JsonObject obj, String key, double defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsDouble()
                : defaultValue;
    }

    /**
     * 读取可选字符串字段。
     *
     * @param obj          JSON 对象
     * @param key          字段名
     * @param defaultValue 缺省值
     * @return 字符串
     */
    private static String optString(JsonObject obj, String key, String defaultValue) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString()
                : defaultValue;
    }

    /**
     * 安装加载结果。
     *
     * @param attributes 属性定义
     * @param zones      乘区定义
     */
    private static void install(Map<ResourceLocation, AttributeDefinition> attributes,
                                Map<ResourceLocation, ZoneDefinition> zones) {
        DataRepository.attributes = Map.copyOf(attributes);
        DataRepository.zones = Map.copyOf(zones);
        loaded = true;

        DamageModernization.LOGGER.info(
                "数据加载完成: 属性 {} 条, 乘区 {} 条", attributes.size(), zones.size());
    }

    /**
     * {@return 指定 id 的属性定义；不存在时返回 null}
     *
     * @param id 属性标识
     */
    public static AttributeDefinition attribute(ResourceLocation id) {
        return attributes.get(id);
    }

    /**
     * {@return 指定作用域与路径的乘区定义；不存在时返回 null}
     *
     * @param scope 作用域
     * @param id    乘区路径
     */
    public static ZoneDefinition zone(ZoneScope scope, String id) {
        return zones.get(scope.key(id));
    }

    /**
     * {@return 指定作用域下的全部乘区，已按优先级排序}
     *
     * @param scope 作用域
     */
    public static List<ZoneDefinition> zonesOf(ZoneScope scope) {
        List<ZoneDefinition> out = new ArrayList<>();
        for (ZoneDefinition def : zones.values()) {
            if (def.scope() == scope && def.enabled()) {
                out.add(def);
            }
        }
        out.sort(Comparator.comparingInt(ZoneDefinition::priority));
        return out;
    }

    /** {@return 是否已加载过数据} */
    public static boolean isLoaded() {
        return loaded;
    }

    /** {@return 已加载的乘区总数（测试与诊断用）} */
    public static int zoneCount() {
        return zones.size();
    }
}
