package com.xwell.rpatt.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class InterestSignalTest {
    @Test public void commentsCanAddASecondaryInterestSignal() {
        List<String> matched = new ArrayList<>();

        WarmupEngine.mergeCommentInterestSignals(
                matched,
                Arrays.asList("The camera is great", "I use GPT for this too"),
                Arrays.asList("AI", "GPT"));

        assertEquals(Collections.singletonList("gpt"), matched);
    }

    @Test public void existingSignalIsNotDuplicated() {
        List<String> matched = new ArrayList<>(Collections.singletonList("gpt"));

        WarmupEngine.mergeCommentInterestSignals(
                matched,
                Collections.singletonList("GPT is useful"),
                Collections.singletonList("GPT"));

        assertEquals(Collections.singletonList("gpt"), matched);
    }
}
