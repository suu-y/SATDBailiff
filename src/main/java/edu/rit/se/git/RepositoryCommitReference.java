package edu.rit.se.git;

import edu.rit.se.satd.comment.model.RepositoryComments;
import edu.rit.se.satd.detector.DebtHunterCommentExtractor;
import edu.rit.se.satd.detector.SATDDetector;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class used to represent a diff inside a git repository
 */
@RequiredArgsConstructor
public class RepositoryCommitReference {

    @Getter
    final private Git gitInstance;
    @Getter
    final private String projectName;
    @Getter
    final private String projectURI;
    @Getter
    final private RevCommit commit;

    /**
     * @return A list of the diff's parents
     */
    public List<RepositoryCommitReference> getParentCommitReferences() {
        // Debugging code -- should NOT be included in any releases.
        // Used to start a search at a specific diff
//        if( this.commit.getName().equals("e394516307697ad4ace3d0c0b1155362eeefa2d6") ) {
//            return new ArrayList<>();
//        }
        try (RevWalk rw = new RevWalk(this.gitInstance.getRepository())) {
            return Arrays.stream(this.commit.getParents())
                    .map(RevCommit::toObjectId)
                    .map(id -> {
                        try {
                            return rw.parseCommit(id);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .map(parent -> new RepositoryCommitReference(
                            this.gitInstance,
                            this.projectName,
                            this.projectURI,
                            parent
                    ))
                    .collect(Collectors.toList());
        }
    }

    /**
     * @param detector a detector to classify comments in the files as SATD
     * @param filesToSearch a list of files to limit the search to
     * @return a mapping of files to the SATD Occurrences in each of those files
     */
    public Map<String, RepositoryComments> getFilesToSATDOccurrences(
            SATDDetector detector, List<String> filesToSearch) {
        try {
            return DebtHunterCommentExtractor.extractSatdComments(this.gitInstance, this.commit, filesToSearch);
        } catch (Exception e) {
            System.err.println("Failed to extract SATD comments using DebtHunter: " + e.getMessage());
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    public String getCommitHash() {
        return this.commit.getName();
    }

    public long getAuthoredTime() {
        return this.commit.getAuthorIdent().getWhen().getTime();
    }

    public int getCommitTime() {
        return this.commit.getCommitTime();
    }

    @Override
    public int hashCode() {
        return this.getCommit().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if( obj instanceof  RepositoryCommitReference ) {
            return this.getCommit().hashCode() == ((RepositoryCommitReference) obj).getCommit().hashCode();
        }
        return false;
    }
}
