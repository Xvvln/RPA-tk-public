package com.xwell.rpatt.automation;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VideoContent {
    public final String author;
    public final String description;
    public final String music;

    public VideoContent(String author, String description, String music) {
        this.author = author;
        this.description = description;
        this.music = music;
    }

    public static VideoContent from(UiSnapshot snapshot) {
        String author = "";
        String description = "";
        String music = "";
        UiNode authorNode = null;
        UiNode descriptionNode = null;
        for (UiNode node : snapshot.nodes) {
            // Adjacent ViewPager items stay in TikTok's accessibility tree. Bind extracted
            // metadata to the item whose node is both visible-to-user and on this display.
            if (!snapshot.isVisible(node)
                    || !PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)) continue;
            if (isAuthorCandidate(snapshot, node)
                    && (authorNode == null || authorScore(node) > authorScore(authorNode))) {
                authorNode = node;
                author = authorText(node);
            }
            if (isDescriptionCandidate(snapshot, node)
                    && (descriptionNode == null || descriptionScore(node) > descriptionScore(descriptionNode))) {
                descriptionNode = node;
                description = node.text;
            }
            if (music.isEmpty() && (node.description.startsWith("音乐：") || node.description.toLowerCase(Locale.ROOT).startsWith("music:"))) {
                int separator = node.description.indexOf('：');
                if (separator < 0) separator = node.description.indexOf(':');
                music = separator >= 0 ? node.description.substring(separator + 1).trim() : node.description;
            }
        }
        return new VideoContent(author, description, music);
    }

    private static boolean isAuthorCandidate(UiSnapshot snapshot, UiNode node) {
        if (node.text.isEmpty() && node.description.isEmpty()) return false;
        boolean lowerLeft = centerX(node) < snapshot.displayWidth * 0.62f
                && centerY(node) > snapshot.displayHeight * 0.55f;
        boolean profileSemantic = node.description.endsWith("主页")
                || node.description.toLowerCase(Locale.ROOT).endsWith("profile");
        return lowerLeft && (profileSemantic
                || (node.id.endsWith(":id/title") && node.clickable && node.text.length() <= 80));
    }

    private static int authorScore(UiNode node) {
        int score = node.id.endsWith(":id/title") ? 2 : 0;
        if (node.description.endsWith("主页")
                || node.description.toLowerCase(Locale.ROOT).endsWith("profile")) score += 3;
        return score;
    }

    private static String authorText(UiNode node) {
        if (!node.text.isEmpty()) return node.text;
        String value = node.description;
        int suffix = value.lastIndexOf('.');
        return suffix > 0 ? value.substring(0, suffix).trim() : value;
    }

    private static boolean isDescriptionCandidate(UiSnapshot snapshot, UiNode node) {
        if (node.text.trim().length() < 8 || node.clickable && node.text.length() < 20) return false;
        boolean lowerLeft = node.bounds.left < snapshot.displayWidth * 0.18f
                && node.bounds.right <= snapshot.displayWidth * 0.82f
                && centerY(node) > snapshot.displayHeight * 0.58f;
        boolean textLike = node.className.contains("Text") || node.className.startsWith("X.");
        return lowerLeft && (node.id.endsWith(":id/desc")
                || (textLike && node.text.length() >= 20));
    }

    private static int descriptionScore(UiNode node) {
        return (node.id.endsWith(":id/desc") ? 1000 : 0) + node.text.length();
    }

    private static int centerX(UiNode node) {
        return node.bounds.left + (node.bounds.right - node.bounds.left) / 2;
    }

    private static int centerY(UiNode node) {
        return node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
    }

    public List<String> matchedKeywords(List<String> keywords) {
        String value = (author + " " + description + " " + music).toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        for (String keyword : keywords) {
            String normalized = keyword.trim().toLowerCase(Locale.ROOT);
            if (KeywordMatcher.matches(value, normalized)) result.add(normalized);
        }
        return new ArrayList<>(result);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("author", author).put("description", description).put("music", music);
    }

    public String fingerprint() { return author.trim() + "\n" + description.trim(); }

    /**
     * Compare a previously captured video identity without requiring TikTok's description
     * affordance to remain byte-for-byte identical. TikTok may replace a truncated “…展开” value
     * with the expanded text while a modal opens or closes.
     */
    public static boolean matchesFingerprint(String expectedFingerprint, VideoContent observed) {
        if (expectedFingerprint == null || expectedFingerprint.isEmpty() || observed == null) {
            return false;
        }
        int separator = expectedFingerprint.indexOf('\n');
        String expectedAuthor = separator < 0
                ? expectedFingerprint : expectedFingerprint.substring(0, separator);
        String expectedDescription = separator < 0
                ? "" : expectedFingerprint.substring(separator + 1);
        String normalizedExpectedAuthor = normalizeIdentityText(expectedAuthor, false);
        String normalizedObservedAuthor = normalizeIdentityText(observed.author, false);
        if (!normalizedExpectedAuthor.equals(normalizedObservedAuthor)) return false;

        String normalizedExpectedDescription =
                normalizeIdentityText(expectedDescription, true);
        String normalizedObservedDescription =
                normalizeIdentityText(observed.description, true);
        if (normalizedExpectedDescription.equals(normalizedObservedDescription)) {
            return !normalizedExpectedAuthor.isEmpty()
                    || !normalizedExpectedDescription.isEmpty();
        }
        if (normalizedExpectedDescription.isEmpty()
                || normalizedObservedDescription.isEmpty()) return false;
        boolean expectedIsShorter = normalizedExpectedDescription.length()
                <= normalizedObservedDescription.length();
        String shorter = expectedIsShorter
                ? normalizedExpectedDescription : normalizedObservedDescription;
        String longer = expectedIsShorter
                ? normalizedObservedDescription : normalizedExpectedDescription;
        return shorter.length() >= 24 && longer.startsWith(shorter);
    }

    private static String normalizeIdentityText(String value, boolean description) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        if (!description) return normalized;
        return normalized
                .replaceFirst("(?:…|\\.\\.\\.)\\s*(?:展开|more)?\\s*$", "")
                .replaceFirst("\\s+(?:展开|more)\\s*$", "")
                .trim();
    }

    /** External writes require both independent fields so recycled pager nodes are not enough. */
    public boolean hasStableActionIdentity() {
        return !author.trim().isEmpty() && !description.trim().isEmpty();
    }

    public String actionFingerprint() {
        if (!hasStableActionIdentity()) return "";
        return author.trim() + "\n" + description.trim() + "\n" + music.trim();
    }
}
