package com.xwell.rpatt.automation;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class KeywordMatcherTest {
    @Test public void shortLatinKeywordUsesTokenBoundaries() {
        assertTrue(KeywordMatcher.matches("AI tools for creators", "AI"));
        assertTrue(KeywordMatcher.matches("#AI is changing work", "AI"));
        assertFalse(KeywordMatcher.matches("Daily life update", "AI"));
        assertFalse(KeywordMatcher.matches("She said hello", "AI"));
    }

    @Test public void chineseAndPhrasesKeepContainsSemantics() {
        assertTrue(KeywordMatcher.matches("这是人工智能相关内容", "人工智能"));
        assertTrue(KeywordMatcher.matches("new machine learning tutorial", "machine learning"));
    }

    @Test public void latinWordDoesNotMatchALongerWord() {
        assertTrue(KeywordMatcher.matches("I want to learn coding", "learn"));
        assertFalse(KeywordMatcher.matches("machine learning tips", "learn"));
    }
}
