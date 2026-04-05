package com.claudecode.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场管理器 —— 对应 claude-code 中 marketplace 的目录获取与缓存。
 * <p>
 * 功能：
 * <ul>
 *   <li>获取远程市场插件目录</li>
 *   <li>本地缓存（TTL 24小时）</li>
 *   <li>搜索和过滤</li>
 *   <li>热门推荐</li>
 * </ul>
 * <p>
 * 缓存位置: ~/.claude-code-java/marketplace-cache.json
 */
public class MarketplaceManager {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认市场 URL（可配置） */
    private static final String DEFAULT_MARKETPLACE_URL =
            "https://marketplace.claude-code.dev/api/v1";

    /** 缓存 TTL：24小时 */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final String marketplaceUrl;
    private final Path cacheFile;
    private final HttpClient httpClient;

    /** 内存缓存 */
    private final ConcurrentHashMap<String, PluginManifest.MarketplaceEntry> catalog = new ConcurrentHashMap<>();
    private Instant lastFetchTime = Instant.EPOCH;

    public MarketplaceManager() {
        this(DEFAULT_MARKETPLACE_URL);
    }

    public MarketplaceManager(String marketplaceUrl) {
        this.marketplaceUrl = marketplaceUrl;
        this.cacheFile = Path.of(
                System.getProperty("user.home"), ".claude-code-java", "marketplace-cache.json");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 尝试从本地缓存加载
        loadCache();
    }

    /**
     * 获取完整目录（必要时从远程刷新）。
     */
    public List<PluginManifest.MarketplaceEntry> getCatalog(boolean forceRefresh) {
        if (forceRefresh || isCacheExpired()) {
            fetchRemoteCatalog();
        }
        return new ArrayList<>(catalog.values());
    }

    /**
     * 搜索插件（按名称、描述、标签）。
     */
    public List<PluginManifest.MarketplaceEntry> search(String query) {
        if (isCacheExpired()) {
            fetchRemoteCatalog();
        }

        String lowerQuery = query.toLowerCase();
        return catalog.values().stream()
                .filter(e -> matchesQuery(e, lowerQuery))
                .sorted(Comparator.comparingLong(PluginManifest.MarketplaceEntry::downloads).reversed())
                .toList();
    }

    /**
     * 获取单个插件信息。
     */
    public Optional<PluginManifest.MarketplaceEntry> getPlugin(String pluginId) {
        if (isCacheExpired()) {
            fetchRemoteCatalog();
        }
        return Optional.ofNullable(catalog.get(pluginId));
    }

    /**
     * 获取热门插件。
     */
    public List<PluginManifest.MarketplaceEntry> getPopular(int limit) {
        return catalog.values().stream()
                .sorted(Comparator.comparingLong(PluginManifest.MarketplaceEntry::downloads).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * 获取指定标签的插件。
     */
    public List<PluginManifest.MarketplaceEntry> getByTag(String tag) {
        return catalog.values().stream()
                .filter(e -> e.tags() != null && e.tags().contains(tag))
                .toList();
    }

    /**
     * 目录大小。
     */
    public int size() {
        return catalog.size();
    }

    /**
     * 上次获取时间。
     */
    public Instant getLastFetchTime() {
        return lastFetchTime;
    }

    // ==================== 远程获取 ====================

    private void fetchRemoteCatalog() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(marketplaceUrl + "/plugins"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "claude-code-java/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<PluginManifest.MarketplaceEntry> entries =
                        PluginManifest.MarketplaceEntry.fromJsonArray(response.body());

                catalog.clear();
                for (var entry : entries) {
                    catalog.put(entry.id(), entry);
                }
                lastFetchTime = Instant.now();
                saveCache();

                log.info("Fetched {} plugins from marketplace", entries.size());
            } else {
                log.warn("Marketplace API returned status: {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch marketplace catalog: {}", e.getMessage());
            // 使用本地缓存（如果有）
        }
    }

    // ==================== 本地缓存 ====================

    private void loadCache() {
        if (!Files.isRegularFile(cacheFile)) return;
        try {
            CacheData data = MAPPER.readValue(cacheFile.toFile(), CacheData.class);
            if (data.entries != null) {
                for (var entry : data.entries) {
                    catalog.put(entry.id(), entry);
                }
            }
            if (data.fetchTime != null) {
                lastFetchTime = Instant.parse(data.fetchTime);
            }
            log.debug("Loaded {} entries from marketplace cache", catalog.size());
        } catch (Exception e) {
            log.debug("Failed to load marketplace cache: {}", e.getMessage());
        }
    }

    private void saveCache() {
        try {
            Files.createDirectories(cacheFile.getParent());
            CacheData data = new CacheData(
                    lastFetchTime.toString(), new ArrayList<>(catalog.values()));
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(cacheFile.toFile(), data);
        } catch (Exception e) {
            log.debug("Failed to save marketplace cache: {}", e.getMessage());
        }
    }

    private boolean isCacheExpired() {
        return Duration.between(lastFetchTime, Instant.now()).compareTo(CACHE_TTL) > 0;
    }

    private boolean matchesQuery(PluginManifest.MarketplaceEntry entry, String query) {
        if (entry.name() != null && entry.name().toLowerCase().contains(query)) return true;
        if (entry.description() != null && entry.description().toLowerCase().contains(query)) return true;
        if (entry.id() != null && entry.id().toLowerCase().contains(query)) return true;
        if (entry.author() != null && entry.author().toLowerCase().contains(query)) return true;
        if (entry.tags() != null) {
            for (String tag : entry.tags()) {
                if (tag.toLowerCase().contains(query)) return true;
            }
        }
        return false;
    }

    // ==================== 缓存数据结构 ====================

    private record CacheData(
            String fetchTime,
            List<PluginManifest.MarketplaceEntry> entries
    ) {}
}
