package com.claudecode.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件安装器 —— 处理插件的下载、解压、版本管理。
 * <p>
 * 对应 claude-code 中 marketplace 的安装逻辑。
 * <p>
 * 安装目录结构：
 * <pre>
 * ~/.claude-code-java/plugins/           (user 级)
 * {project}/.claude-code/plugins/        (project 级)
 * ~/.claude-code-java/plugin-cache/      (下载缓存)
 * </pre>
 */
public class PluginInstaller {

    private static final Logger log = LoggerFactory.getLogger(PluginInstaller.class);

    private final HttpClient httpClient;
    private final Path cacheDir;
    private final Path userPluginDir;
    private final Path projectPluginDir;

    public PluginInstaller(Path workDir) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Path home = Path.of(System.getProperty("user.home"), ".claude-code-java");
        this.cacheDir = home.resolve("plugin-cache");
        this.userPluginDir = home.resolve("plugins");
        this.projectPluginDir = workDir.resolve(".claude-code").resolve("plugins");
    }

    /**
     * 安装插件。
     *
     * @param downloadUrl 下载 URL（JAR 文件）
     * @param scope       安装作用域：user / project
     * @return 安装结果
     */
    public InstallResult install(String downloadUrl, String scope) {
        try {
            // 下载
            Path downloaded = download(downloadUrl);

            // 读取并校验 manifest
            PluginManifest manifest = readManifestFromJar(downloaded);
            if (manifest == null) {
                return new InstallResult(false, null, "No manifest.json found in JAR");
            }

            List<String> errors = manifest.validate();
            if (!errors.isEmpty()) {
                return new InstallResult(false, null, "Invalid manifest: " + String.join(", ", errors));
            }

            if (!manifest.supportsScope(scope)) {
                return new InstallResult(false, manifest.id(),
                        "Plugin does not support scope: " + scope);
            }

            // 确定目标目录
            Path targetDir = "project".equals(scope) ? projectPluginDir : userPluginDir;
            Files.createDirectories(targetDir);

            // 检查已安装版本
            Path existing = targetDir.resolve(manifest.id() + ".jar");
            if (Files.exists(existing)) {
                PluginManifest existingManifest = readManifestFromJar(existing);
                if (existingManifest != null &&
                        existingManifest.version().equals(manifest.version())) {
                    return new InstallResult(true, manifest.id(),
                            "Already installed: " + manifest.name() + " v" + manifest.version());
                }
                // 备份旧版本
                Path backup = targetDir.resolve(manifest.id() + ".jar.bak");
                Files.move(existing, backup, StandardCopyOption.REPLACE_EXISTING);
                log.info("Backed up existing version to {}", backup.getFileName());
            }

            // 复制到目标
            Files.copy(downloaded, existing, StandardCopyOption.REPLACE_EXISTING);

            log.info("Installed plugin: {} v{} to {} ({})",
                    manifest.name(), manifest.version(), targetDir, scope);
            return new InstallResult(true, manifest.id(),
                    "Installed " + manifest.name() + " v" + manifest.version());

        } catch (Exception e) {
            log.error("Plugin installation failed: {}", e.getMessage(), e);
            return new InstallResult(false, null, "Installation failed: " + e.getMessage());
        }
    }

    /**
     * 卸载插件。
     */
    public boolean uninstall(String pluginId, String scope) {
        Path targetDir = "project".equals(scope) ? projectPluginDir : userPluginDir;
        Path jarFile = targetDir.resolve(pluginId + ".jar");
        try {
            if (Files.deleteIfExists(jarFile)) {
                // 也清理备份
                Files.deleteIfExists(targetDir.resolve(pluginId + ".jar.bak"));
                log.info("Uninstalled plugin: {} from {}", pluginId, scope);
                return true;
            }
            log.warn("Plugin JAR not found: {}", jarFile);
            return false;
        } catch (IOException e) {
            log.error("Failed to uninstall plugin: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查插件是否有更新。
     */
    public CompletableFuture<UpdateCheckResult> checkUpdate(
            String pluginId, String currentVersion, String checkUrl) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(checkUrl + "/" + pluginId + "/latest"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    PluginManifest.MarketplaceEntry entry =
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readValue(response.body(),
                                            PluginManifest.MarketplaceEntry.class);
                    boolean hasUpdate = compareVersions(entry.version(), currentVersion) > 0;
                    return new UpdateCheckResult(pluginId, currentVersion,
                            entry.version(), hasUpdate, entry.downloadUrl());
                }
                return new UpdateCheckResult(pluginId, currentVersion, null, false, null);
            } catch (Exception e) {
                log.debug("Update check failed for {}: {}", pluginId, e.getMessage());
                return new UpdateCheckResult(pluginId, currentVersion, null, false, null);
            }
        });
    }

    /**
     * 列出已安装的插件 JAR。
     */
    public Map<String, InstalledPluginInfo> listInstalled() {
        Map<String, InstalledPluginInfo> result = new TreeMap<>();
        scanInstalled(userPluginDir, "user", result);
        scanInstalled(projectPluginDir, "project", result);
        return result;
    }

    private void scanInstalled(Path dir, String scope, Map<String, InstalledPluginInfo> result) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(jar -> {
                        PluginManifest m = readManifestFromJar(jar);
                        if (m != null) {
                            result.put(m.id(), new InstalledPluginInfo(
                                    m.id(), m.name(), m.version(), scope, jar));
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to scan {}: {}", dir, e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private Path download(String url) throws Exception {
        Files.createDirectories(cacheDir);
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (!fileName.endsWith(".jar")) fileName += ".jar";
        Path target = cacheDir.resolve(fileName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<Path> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofFile(target));

        if (response.statusCode() != 200) {
            throw new IOException("Download failed with status: " + response.statusCode());
        }

        log.info("Downloaded {} ({} bytes)", fileName, Files.size(target));
        return target;
    }

    static PluginManifest readManifestFromJar(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("manifest.json");
            if (entry == null) return null;

            try (InputStream is = jar.getInputStream(entry)) {
                return PluginManifest.fromJson(is.readAllBytes());
            }
        } catch (Exception e) {
            log.debug("Failed to read manifest from {}: {}", jarPath.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * 简单版本比较 (a.b.c 格式)。
     * @return 正数表示 v1 > v2, 负数表示 v1 < v2, 0 表示相等
     */
    static int compareVersions(String v1, String v2) {
        // 去掉非数字后缀 (e.g., "1.2.3-beta" → "1.2.3")
        String[] parts1 = v1.replaceAll("[^0-9.].*", "").split("\\.");
        String[] parts2 = v2.replaceAll("[^0-9.].*", "").split("\\.");

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) return p1 - p2;
        }
        return 0;
    }

    // ==================== 结果类型 ====================

    public record InstallResult(boolean success, String pluginId, String message) {}

    public record UpdateCheckResult(
            String pluginId, String currentVersion, String latestVersion,
            boolean hasUpdate, String downloadUrl) {}

    public record InstalledPluginInfo(
            String id, String name, String version, String scope, Path jarPath) {}
}
