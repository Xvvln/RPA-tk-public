package com.xwell.rpatt.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only state contract for TikTok's share sheet. */
public final class RepostSheetState {
    public enum State { AVAILABLE, ALREADY_REPOSTED, UNKNOWN }

    public static final class Result {
        public final State state;
        public final ControlResolver.Match createEntry;
        public final ControlResolver.Match removeEntry;
        public final List<String> evidence;

        private Result(State state, ControlResolver.Match createEntry,
                       ControlResolver.Match removeEntry, List<String> evidence) {
            this.state = state;
            this.createEntry = createEntry;
            this.removeEntry = removeEntry;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }
    }

    private RepostSheetState() {}

    public static Result analyze(UiSnapshot snapshot) {
        ControlResolver.Match create = ControlResolver.resolve(
                snapshot, ControlResolver.Role.REPOST);
        ControlResolver.Match remove = ControlResolver.resolve(
                snapshot, ControlResolver.Role.REPOST_REMOVE);
        List<String> evidence = new ArrayList<>();
        evidence.add(create == null ? "repost:create_missing" : "repost:create_present");
        evidence.add(remove == null ? "repost:remove_missing" : "repost:remove_present");
        if (create != null && remove == null) {
            return new Result(State.AVAILABLE, create, null, evidence);
        }
        if (create == null && remove != null) {
            return new Result(State.ALREADY_REPOSTED, null, remove, evidence);
        }
        if (create != null) evidence.add("repost:conflicting_entries");
        return new Result(State.UNKNOWN, create, remove, evidence);
    }
}
