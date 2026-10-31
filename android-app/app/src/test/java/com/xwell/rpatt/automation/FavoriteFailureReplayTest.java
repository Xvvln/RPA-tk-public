package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class FavoriteFailureReplayTest {
    @Test public void capturedFailureResolvesVisibleFavoriteAndUnoccludedCenter() throws Exception {
        UiSnapshot snapshot = fixture("fixtures/favorite-not-applied.json");

        ControlResolver.Match favorite = ControlResolver.resolve(
                snapshot, ControlResolver.Role.VIDEO_FAVORITE);
        assertNotNull(favorite);
        assertEquals("com.zhiliaoapp.musically:id/hrs", favorite.node.id);
        assertEquals(888, favorite.node.bounds.left);
        assertEquals(1659, favorite.node.bounds.top);

        VisibleHitRegion.Point point = VisibleHitRegion.resolve(snapshot, favorite.node);
        assertNotNull(point);
        assertEquals(984f, point.x, 0.01f);
        assertEquals(1749f, point.y, 0.01f);
        assertTrue(GestureTargetContract.sameVisibleTarget(
                snapshot, favorite.node, favorite.node));
    }

    private static UiSnapshot fixture(String name) throws Exception {
        try (InputStream stream = FavoriteFailureReplayTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("missing fixture " + name);
            byte[] bytes = stream.readAllBytes();
            return UiSnapshot.fromJson(new JSONObject(
                    new String(bytes, StandardCharsets.UTF_8)));
        }
    }
}
