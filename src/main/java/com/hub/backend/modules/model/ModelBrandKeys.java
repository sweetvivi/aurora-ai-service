package com.hub.backend.modules.model;

/**
 * Shared slug helpers for brand keys and company → family matching.
 */
public final class ModelBrandKeys {

    private ModelBrandKeys() {
    }

    /**
     * @return stable machine key for a vendor name, {@code other} when unknown / non-Latin only
     */
    public static String familyKeyOf(String company) {
        if (company == null || company.isBlank()) {
            return "other";
        }
        String slug = company.strip().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "other" : slug;
    }

    /** @return true when {@code brandKey} is a non-empty kebab-case ascii slug */
    public static boolean isValidBrandKey(String brandKey) {
        return brandKey != null && brandKey.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")
                && brandKey.length() <= 64
                && !"other".equals(brandKey);
    }

    /**
     * Derive a brand key from a display name. CJK-only names fall back to {@code brand} so the
     * caller can still require an explicit key.
     */
    public static String suggestBrandKey(String displayName) {
        String key = familyKeyOf(displayName);
        return "other".equals(key) ? "" : key;
    }
}
