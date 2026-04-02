package com.claudecode.permission;

import com.claudecode.permission.PermissionTypes.PermissionBehavior;
import com.claudecode.permission.PermissionTypes.PermissionRule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限设置持久化 —— 管理用户级和项目级权限规则文件。
 * <p>
 * 存储结构：
 * <ul>
 *   <li>用户级: ~/.claude-code-java/settings.json</li>
 *   <li>项目级: .claude-code-java/settings.json</li>
 * </ul>
 * 加载优先级: 项目级 > 用户级 > 会话级
 */
public class PermissionSettings {

    private static final String SETTINGS_DIR = ".claude-code-java";
    private static final String SETTINGS_FILE = "settings.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 内存中的合并规则（从所有来源加载后合并） */
    private final List<PermissionRule> sessionRules = new ArrayList<>();
    private PermissionTypes.PermissionMode currentMode = PermissionTypes.PermissionMode.DEFAULT;

    private final Path userSettingsPath;
    private final Path projectSettingsPath;

    private SettingsData userData = new SettingsData();
    private SettingsData projectData = new SettingsData();

    public PermissionSettings() {
        this(Path.of(System.getProperty("user.home")),
             Path.of(System.getProperty("user.dir")));
    }

    public PermissionSettings(Path userHome, Path projectDir) {
        this.userSettingsPath = userHome.resolve(SETTINGS_DIR).resolve(SETTINGS_FILE);
        this.projectSettingsPath = projectDir.resolve(SETTINGS_DIR).resolve(SETTINGS_FILE);
    }

    /** 从磁盘加载所有设置 */
    public void load() {
        userData = loadFromFile(userSettingsPath);
        projectData = loadFromFile(projectSettingsPath);
        // 项目级模式优先
        if (projectData.permissions.mode != null) {
            currentMode = projectData.permissions.mode;
        } else if (userData.permissions.mode != null) {
            currentMode = userData.permissions.mode;
        }
    }

    /** 获取所有合并后的规则（项目级 > 用户级 > 会话级） */
    public List<PermissionRule> getAllRules() {
        var rules = new ArrayList<PermissionRule>();
        // 项目级优先
        rules.addAll(toRules(projectData.permissions.alwaysAllow, PermissionBehavior.ALLOW));
        rules.addAll(toRules(projectData.permissions.alwaysDeny, PermissionBehavior.DENY));
        // 用户级
        rules.addAll(toRules(userData.permissions.alwaysAllow, PermissionBehavior.ALLOW));
        rules.addAll(toRules(userData.permissions.alwaysDeny, PermissionBehavior.DENY));
        // 会话级
        rules.addAll(sessionRules);
        return rules;
    }

    /** 添加规则并保存到用户级设置 */
    public void addUserRule(PermissionRule rule) {
        if (rule.behavior() == PermissionBehavior.ALLOW) {
            userData.permissions.alwaysAllow.add(formatRule(rule));
        } else if (rule.behavior() == PermissionBehavior.DENY) {
            userData.permissions.alwaysDeny.add(formatRule(rule));
        }
        saveToFile(userSettingsPath, userData);
    }

    /** 添加规则到会话级（不持久化） */
    public void addSessionRule(PermissionRule rule) {
        sessionRules.add(rule);
    }

    /** 移除用户级规则 */
    public void removeUserRule(String ruleStr) {
        userData.permissions.alwaysAllow.remove(ruleStr);
        userData.permissions.alwaysDeny.remove(ruleStr);
        saveToFile(userSettingsPath, userData);
    }

    /** 清除所有规则 */
    public void clearAll() {
        userData.permissions.alwaysAllow.clear();
        userData.permissions.alwaysDeny.clear();
        projectData.permissions.alwaysAllow.clear();
        projectData.permissions.alwaysDeny.clear();
        sessionRules.clear();
        saveToFile(userSettingsPath, userData);
    }

    public PermissionTypes.PermissionMode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(PermissionTypes.PermissionMode mode) {
        this.currentMode = mode;
        userData.permissions.mode = mode;
        saveToFile(userSettingsPath, userData);
    }

    /** 获取所有已保存规则的可读列表 */
    public List<String> listRules() {
        var result = new ArrayList<String>();
        for (var r : userData.permissions.alwaysAllow) {
            result.add("[user] ALLOW " + r);
        }
        for (var r : userData.permissions.alwaysDeny) {
            result.add("[user] DENY  " + r);
        }
        for (var r : projectData.permissions.alwaysAllow) {
            result.add("[proj] ALLOW " + r);
        }
        for (var r : projectData.permissions.alwaysDeny) {
            result.add("[proj] DENY  " + r);
        }
        for (var r : sessionRules) {
            result.add("[sess] " + r.behavior() + " " + formatRule(r));
        }
        return result;
    }

    // ── 内部方法 ──

    private SettingsData loadFromFile(Path path) {
        if (!Files.exists(path)) return new SettingsData();
        try {
            return MAPPER.readValue(path.toFile(), SettingsData.class);
        } catch (IOException e) {
            return new SettingsData();
        }
    }

    private void saveToFile(Path path, SettingsData data) {
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writeValue(path.toFile(), data);
        } catch (IOException e) {
            // 静默失败，不影响主流程
        }
    }

    private List<PermissionRule> toRules(List<String> ruleStrings, PermissionBehavior behavior) {
        return ruleStrings.stream()
                .map(s -> parseRule(s, behavior))
                .toList();
    }

    /** 解析规则字符串，格式: "ToolName(pattern)" 或 "ToolName" */
    static PermissionRule parseRule(String ruleStr, PermissionBehavior behavior) {
        int parenStart = ruleStr.indexOf('(');
        if (parenStart > 0 && ruleStr.endsWith(")")) {
            String toolName = ruleStr.substring(0, parenStart);
            String content = ruleStr.substring(parenStart + 1, ruleStr.length() - 1);
            return new PermissionRule(toolName, content, behavior);
        }
        return PermissionRule.forTool(ruleStr, behavior);
    }

    /** 格式化规则为字符串 */
    static String formatRule(PermissionRule rule) {
        if ("*".equals(rule.ruleContent())) {
            return rule.toolName();
        }
        return rule.toolName() + "(" + rule.ruleContent() + ")";
    }

    // ── JSON 数据结构 ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SettingsData {
        public PermissionsBlock permissions = new PermissionsBlock();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PermissionsBlock {
        public PermissionTypes.PermissionMode mode;
        public List<String> alwaysAllow = new ArrayList<>();
        public List<String> alwaysDeny = new ArrayList<>();
        public List<String> additionalDirectories = new ArrayList<>();
    }
}
