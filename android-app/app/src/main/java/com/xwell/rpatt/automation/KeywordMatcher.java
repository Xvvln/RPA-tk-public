package com.xwell.rpatt.automation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared keyword semantics for video metadata and collected comments. */
public final class KeywordMatcher {
    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9_]+");

    private KeywordMatcher() {}

    public static boolean matches(String text, String keyword) {
        String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return false;
        if (!ASCII_TOKEN.matcher(needle).matches()) return haystack.contains(needle);
        Pattern token = Pattern.compile(
                "(?<![a-z0-9_])" + Pattern.quote(needle) + "(?![a-z0-9_])");
        return token.matcher(haystack).find();
    }
}
