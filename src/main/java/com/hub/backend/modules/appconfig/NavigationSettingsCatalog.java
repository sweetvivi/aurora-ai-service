package com.hub.backend.modules.appconfig;

import com.hub.backend.modules.appconfig.dto.NavigationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical keys for the client's global navigation entries.
 */
public final class NavigationSettingsCatalog {

    public static final String SETTING_PREFIX = "navigation.";
    public static final String SETTING_SUFFIX = ".visible";
    public static final String KEY_NAVIGATION_LAYOUT = "navigation.layout";
    public static final int MAX_NAVIGATION_SECTIONS = 8;
    public static final int MAX_SECTION_ID_LENGTH = 48;
    public static final int MAX_SECTION_TITLE_LENGTH = 40;
    public static final int MAX_LAYOUT_SETTING_LENGTH = 2048;

    public static final List<String> NAVIGATION_KEYS = List.of(
            "home",
            "astra",
            "chat",
            "draw",
            "agent",
            "retouch",
            "imggen",
            "stock",
            "voice",
            "canvas",
            "image",
            "shop",
            "edu",
            "free",
            "3d",
            "video",
            "sound",
            "search",
            "paper",
            "ppt",
            "audio",
            "aisound",
            "doc");

    private static final Set<String> NAVIGATION_KEY_SET = Set.copyOf(NAVIGATION_KEYS);

    private NavigationSettingsCatalog() {
    }

    /**
     * @return an ordered map in which every known navigation entry is visible
     */
    public static Map<String, Boolean> defaultVisibility() {
        Map<String, Boolean> visibility = new LinkedHashMap<>();
        NAVIGATION_KEYS.forEach(key -> visibility.put(key, true));
        return visibility;
    }

    /**
     * @return the initial section layout used when a site has no custom layout
     */
    public static List<NavigationSection> defaultSections() {
        return List.of(
                new NavigationSection(
                        "main",
                        "",
                        List.of("home", "astra", "chat", "draw", "agent", "retouch", "imggen", "stock")),
                new NavigationSection(
                        "new-features",
                        "新功能",
                        List.of("voice", "canvas", "image", "shop", "edu", "free")),
                new NavigationSection(
                        "ai-media",
                        "AI媒体",
                        List.of("3d", "video", "sound")),
                new NavigationSection(
                        "popular",
                        "近期热门",
                        List.of("search", "paper", "ppt", "audio", "aisound", "doc")));
    }

    public static boolean isKnownNavigationKey(String navigationKey) {
        return navigationKey != null && NAVIGATION_KEY_SET.contains(navigationKey);
    }

    public static String settingKey(String navigationKey) {
        return SETTING_PREFIX + navigationKey + SETTING_SUFFIX;
    }

    /**
     * @return the navigation key when the setting belongs to a known navigation entry,
     * otherwise {@code null}
     */
    public static String navigationKeyOf(String settingKey) {
        if (settingKey == null
                || !settingKey.startsWith(SETTING_PREFIX)
                || !settingKey.endsWith(SETTING_SUFFIX)) {
            return null;
        }
        int start = SETTING_PREFIX.length();
        int end = settingKey.length() - SETTING_SUFFIX.length();
        if (end <= start) {
            return null;
        }
        String navigationKey = settingKey.substring(start, end);
        return isKnownNavigationKey(navigationKey) ? navigationKey : null;
    }
}
