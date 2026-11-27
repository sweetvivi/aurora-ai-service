package com.hub.backend.modules.appconfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.dto.NavigationSection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class NavigationSettingsLayoutCodec {

    private static final TypeReference<List<NavigationSection>> SECTION_LIST_TYPE =
            new TypeReference<>() {
            };
    private static final Pattern SECTION_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private final ObjectMapper objectMapper;

    public NavigationSettingsLayoutCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Invalid or legacy layout values fall back to the default so the public navigation always
     * remains usable.
     */
    public List<NavigationSection> readStored(String value) {
        if (value == null || value.isBlank()) {
            return NavigationSettingsCatalog.defaultSections();
        }
        try {
            List<NavigationSection> sections =
                    objectMapper.readValue(value, SECTION_LIST_TYPE);
            return normalizeAndValidate(sections);
        } catch (JsonProcessingException | RuntimeException exception) {
            return NavigationSettingsCatalog.defaultSections();
        }
    }

    public String writeStored(List<NavigationSection> sections) {
        List<NavigationSection> normalized = normalizeAndValidate(sections);
        try {
            String encoded = objectMapper.writeValueAsString(normalized);
            if (encoded.length() > NavigationSettingsCatalog.MAX_LAYOUT_SETTING_LENGTH) {
                throw new InvalidRequestException("navigation layout is too large");
            }
            return encoded;
        } catch (JsonProcessingException exception) {
            throw new InvalidRequestException("navigation layout could not be serialized");
        }
    }

    public List<NavigationSection> normalizeAndValidate(List<NavigationSection> sections) {
        if (sections == null) {
            throw new InvalidRequestException("navigationSections must not be null");
        }
        if (sections.isEmpty()) {
            throw new InvalidRequestException("At least one navigation section is required");
        }
        if (sections.size() > NavigationSettingsCatalog.MAX_NAVIGATION_SECTIONS) {
            throw new InvalidRequestException(
                    "At most " + NavigationSettingsCatalog.MAX_NAVIGATION_SECTIONS
                            + " navigation sections are supported");
        }

        List<NavigationSection> normalized = new ArrayList<>(sections.size());
        Set<String> sectionIds = new HashSet<>();
        Set<String> assignedKeys = new HashSet<>();

        for (NavigationSection section : sections) {
            if (section == null) {
                throw new InvalidRequestException("Navigation section must not be null");
            }
            String id = section.id() == null ? "" : section.id().strip();
            if (id.isEmpty()
                    || id.length() > NavigationSettingsCatalog.MAX_SECTION_ID_LENGTH
                    || !SECTION_ID_PATTERN.matcher(id).matches()) {
                throw new InvalidRequestException(
                        "Invalid navigation section id: " + section.id());
            }
            if (!sectionIds.add(id)) {
                throw new InvalidRequestException("Duplicate navigation section id: " + id);
            }

            String title = section.title() == null ? "" : section.title().strip();
            if (title.length() > NavigationSettingsCatalog.MAX_SECTION_TITLE_LENGTH) {
                throw new InvalidRequestException(
                        "Navigation section title is too long: " + id);
            }
            if (section.itemKeys() == null) {
                throw new InvalidRequestException(
                        "Navigation section itemKeys must not be null: " + id);
            }

            List<String> itemKeys = new ArrayList<>(section.itemKeys().size());
            for (String itemKey : section.itemKeys()) {
                if (!NavigationSettingsCatalog.isKnownNavigationKey(itemKey)) {
                    throw new InvalidRequestException(
                            "Unknown navigation key in layout: " + itemKey);
                }
                if (!assignedKeys.add(itemKey)) {
                    throw new InvalidRequestException(
                            "Duplicate navigation key in layout: " + itemKey);
                }
                itemKeys.add(itemKey);
            }
            normalized.add(new NavigationSection(id, title, List.copyOf(itemKeys)));
        }

        List<String> missingKeys = NavigationSettingsCatalog.NAVIGATION_KEYS.stream()
                .filter(key -> !assignedKeys.contains(key))
                .toList();
        if (!missingKeys.isEmpty()) {
            throw new InvalidRequestException(
                    "Navigation layout is missing keys: " + String.join(",", missingKeys));
        }
        return List.copyOf(normalized);
    }
}
