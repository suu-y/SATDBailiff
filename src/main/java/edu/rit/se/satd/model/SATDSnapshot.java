package edu.rit.se.satd.model;

import edu.rit.se.satd.comment.model.GroupedComment;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the SATD detected within a single repository revision.
 */
@RequiredArgsConstructor
public class SATDSnapshot {

    @Getter
    @NonNull
    private final String projectName;
    @Getter
    @NonNull
    private final String projectURI;
    @Getter
    @NonNull
    private final RevCommit commit;

    @Getter
    private final List<SATDSnapshotEntry> entries = new ArrayList<>();

    public void addEntry(SATDSnapshotEntry entry) {
        this.entries.add(entry);
    }

    @RequiredArgsConstructor
    public static class SATDSnapshotEntry {

        @Getter
        @NonNull
        private final String filePath;
        @Getter
        @NonNull
        private final GroupedComment comment;
        @Getter
        @NonNull
        private final String classification;

        public int getSnapshotInstanceId() {
            return Objects.hash(
                    this.filePath,
                    this.comment.getComment(),
                    this.comment.getContainingClass(),
                    this.comment.getContainingMethod(),
                    this.classification
            );
        }
    }
}

