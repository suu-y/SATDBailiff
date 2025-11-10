package edu.rit.se.satd.mining;

import edu.rit.se.git.RepositoryCommitReference;
import edu.rit.se.satd.comment.model.OldToNewCommentMapping;
import edu.rit.se.satd.comment.model.RepositoryComments;
import edu.rit.se.satd.detector.SATDDetector;
import edu.rit.se.satd.mining.diff.CommitToCommitDiff;
import edu.rit.se.satd.model.SATDDifference;
import edu.rit.se.satd.model.SATDInstance;
import edu.rit.se.satd.model.SATDInstanceInFile;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mines the differences in SATD between repositories
 */
@RequiredArgsConstructor
public class RepositoryDiffMiner {

    // Required fields
    @NonNull
    private RepositoryCommitReference firstRepo;
    @NonNull
    @Getter
    private RepositoryCommitReference secondRepo;
    @NonNull
    private SATDDetector satdDetector;

    /**
     * Mines the differences in SATD between the two repositories set during generation
     * of the DiffMiner object
     * @return a SATDDifference object representing the SATD as it was changed between the
     * earlier and the latter tags
     * @throws IllegalStateException thrown if the DiffMiner object has not been fully
     * configured before running
     */
    public SATDDifference mineDiff() {
        final SATDDifference diff = new SATDDifference(
                this.secondRepo.getProjectName(),
                this.secondRepo.getProjectURI(),
                this.firstRepo.getCommit(),
                this.secondRepo.getCommit());

        // Load the diffs between versions
        final CommitToCommitDiff cToCDiff = new CommitToCommitDiff(
                this.firstRepo, this.secondRepo, this.satdDetector);

        // Determine which files to analyze
        // For release-to-release comparison, analyze all files, not just modified ones
        // Check if this is a release comparison by comparing commit hashes directly
        // (if the commits are not parent-child, this is likely a release comparison)
        List<String> filesToAnalyzeOld;
        List<String> filesToAnalyzeNew;
        boolean isDirectParent = this.firstRepo.getParentCommitReferences().stream()
                .anyMatch(parent -> parent.getCommitHash().equals(this.secondRepo.getCommitHash()));
        
        if (isDirectParent) {
            // Parent-child relationship: only analyze modified files (original behavior)
            filesToAnalyzeOld = cToCDiff.getModifiedFilesOld();
            filesToAnalyzeNew = cToCDiff.getModifiedFilesNew();
        } else {
            // Release-to-release comparison: analyze all Java files
            filesToAnalyzeOld = edu.rit.se.git.GitUtil.getAllJavaFiles(
                    this.firstRepo.getGitInstance(), this.firstRepo.getCommit());
            filesToAnalyzeNew = edu.rit.se.git.GitUtil.getAllJavaFiles(
                    this.secondRepo.getGitInstance(), this.secondRepo.getCommit());
        }

        // Get the SATD occurrences for each repo
        final Map<String, RepositoryComments> newerSATD = this.secondRepo.getFilesToSATDOccurrences(
                this.satdDetector, filesToAnalyzeNew);
        final Map<String, RepositoryComments> olderSATD = this.firstRepo.getFilesToSATDOccurrences(
                this.satdDetector, filesToAnalyzeOld);

        // Get a list of all SATD instances as a mappable instance
        // Remove duplicates: same file, same comment text, same class (DebtHunter can detect same SATD multiple times)
        final List<OldToNewCommentMapping> oldSATDMappings = olderSATD.keySet().stream()
                .flatMap(oldFile -> olderSATD.get(oldFile).getComments().stream()
                    .map(comment -> new OldToNewCommentMapping(comment, oldFile)))
                .collect(Collectors.toList());
        populateDuplicationIds(oldSATDMappings);
        final List<OldToNewCommentMapping> newSATDMappings = newerSATD.keySet().stream()
                .flatMap(newFile -> newerSATD.get(newFile).getComments().stream()
                        .map(comment -> new OldToNewCommentMapping(comment, newFile)))
                .collect(Collectors.toList());
        populateDuplicationIds(newSATDMappings);
        final List<String> erroredFiles = new ArrayList<>();
        // Add errored files to known errors
        erroredFiles.addAll(newerSATD.values().stream()
                .map(RepositoryComments::getParseErrorFiles)
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));
        erroredFiles.addAll(olderSATD.values().stream()
                .map(RepositoryComments::getParseErrorFiles)
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));

        // Map the new to old and then old to new (done later), so we can determine which SATD instances
        // may have changed
        alignMappingLists(oldSATDMappings, newSATDMappings, erroredFiles);

        // For release-to-release comparison, also align by location (file, method, class)
        // This allows matching SATD even when comment text changes
        if (!isDirectParent) {
            alignMappingsByLocation(oldSATDMappings, newSATDMappings);
        }

        // Get all instances that can be mined from the old repository's mapping data
        final List<SATDInstance> oldInstances =
                mineDiffsFromMappedSATDInstances(cToCDiff, oldSATDMappings, true, isDirectParent);
        // Use the new instance to avoid double-detecting instances that may not have
        // been mapped on the first pass through
        alignMappingLists(newSATDMappings, oldInstances.stream()
                .map(SATDInstance::getNewInstance)
                .map(ni -> new OldToNewCommentMapping(ni.getComment(), ni.getFileName()))
                .collect(Collectors.toList()), erroredFiles);
        // Add SATD instances that were in the NEW repo, but couldn't be mapped to the OLD repo
        final List<SATDInstance> newInstances =
                mineDiffsFromMappedSATDInstances(cToCDiff, newSATDMappings, false, isDirectParent);
        
        // For release-to-release comparison, handle mapped SATD (exists in both releases)
        if (!isDirectParent) {
            // Find SATD that exists in both old and new releases
            // These need to be classified correctly:
            // - If comments are identical: SATD_STAY (maintained unchanged)
            // - If comments are different: SATD_CHANGED (actual change)
            // - If new comment is not SATD: SATD_REMOVED (SATD was resolved - comment is now non-SATD)
            final List<SATDInstance> mappedInstances = new ArrayList<>();
            
            // Create a map of newSATDMappings by location for efficient lookup
            final Map<String, List<OldToNewCommentMapping>> newMappingsByLocation = newSATDMappings.stream()
                    .collect(Collectors.groupingBy(m -> m.getFile() + "::" + m.getComment().getContainingMethod() 
                            + "::" + m.getComment().getContainingClass()));
            
            for (OldToNewCommentMapping oldMapping : oldSATDMappings) {
                if (oldMapping.isMapped()) {
                    // Find matching new mapping by location (file, method, class)
                    String locationKey = oldMapping.getFile() + "::" + oldMapping.getComment().getContainingMethod()
                            + "::" + oldMapping.getComment().getContainingClass();
                    
                    List<OldToNewCommentMapping> matchingNewMappings = newMappingsByLocation.getOrDefault(locationKey, new ArrayList<>());
                    
                    // Find the best matching new mapping (same location, and mapped)
                    OldToNewCommentMapping matchedNewMapping = matchingNewMappings.stream()
                            .filter(newMapping -> newMapping.isMapped())
                            .filter(newMapping -> oldMapping.getFile().equals(newMapping.getFile()))
                            .findFirst()
                            .orElse(null);
                    
                    if (matchedNewMapping != null) {
                        // Check if the new comment is still SATD
                        boolean newCommentIsSATD = this.satdDetector.isSATD(matchedNewMapping.getComment().getComment());
                        
                        // Determine resolution based on comment content
                        SATDInstance.SATDResolution resolution;
                        if (!newCommentIsSATD) {
                            // SATD was resolved (removed from comment - comment is now non-SATD)
                            resolution = SATDInstance.SATDResolution.SATD_REMOVED;
                        } else if (oldMapping.getComment().getComment().equals(matchedNewMapping.getComment().getComment())) {
                            // Comments are identical - SATD maintained unchanged
                            resolution = SATDInstance.SATDResolution.SATD_STAY;
                        } else {
                            // Comments are different - SATD was modified
                            resolution = SATDInstance.SATDResolution.SATD_CHANGED;
                        }
                        
                        mappedInstances.add(new SATDInstance(
                                new SATDInstanceInFile(oldMapping.getFile(), oldMapping.getComment()),
                                new SATDInstanceInFile(matchedNewMapping.getFile(), matchedNewMapping.getComment()),
                                resolution
                        ));
                    }
                }
            }
            
            diff.addSATDInstances(mappedInstances);
        }

        diff.addSATDInstances(oldInstances);
        diff.addSATDInstances(newInstances);


        return diff;
    }

    private static void alignMappingLists(List<OldToNewCommentMapping> list1, List<OldToNewCommentMapping> list2,
                                          List<String> erroredFiles) {
        list1.forEach(mappedComment ->
                list2.stream()
                        .filter(OldToNewCommentMapping::isNotMapped)
                        .filter(mappedComment::commentsMatch)
                        .findFirst()
                        .ifPresent(mappedComment::mapTo)
        );
        list1.stream()
                .filter(c -> erroredFiles.contains(c.getFile()))
                .forEach(c -> c.mapTo(null));
        list2.stream()
                .filter(c -> erroredFiles.contains(c.getFile()))
                .forEach(c -> c.mapTo(null));
    }

    /**
     * Aligns mappings by location (file, method, class) for release-to-release comparison.
     * This allows matching SATD even when comment text changes.
     */
    private static void alignMappingsByLocation(List<OldToNewCommentMapping> oldMappings,
                                                 List<OldToNewCommentMapping> newMappings) {
        for (OldToNewCommentMapping oldMapping : oldMappings) {
            if (oldMapping.isNotMapped()) {
                // Find matching new mapping by location (file, method, class)
                newMappings.stream()
                        .filter(OldToNewCommentMapping::isNotMapped)
                        .filter(newMapping -> oldMapping.getFile().equals(newMapping.getFile()))
                        .filter(newMapping -> oldMapping.getComment().getContainingMethod()
                                .equals(newMapping.getComment().getContainingMethod()))
                        .filter(newMapping -> oldMapping.getComment().getContainingClass()
                                .equals(newMapping.getComment().getContainingClass()))
                        .findFirst()
                        .ifPresent(oldMapping::mapTo);
            }
        }
    }

    private static List<SATDInstance> mineDiffsFromMappedSATDInstances(CommitToCommitDiff cToCDiff,
                                                                       List<OldToNewCommentMapping> mappings,
                                                                       boolean isOld,
                                                                       boolean isDirectParent) {
        return mappings.stream()
                        .filter(OldToNewCommentMapping::isNotMapped)
                        .flatMap(mapping -> {
                                    final List<SATDInstance> minedInstances = (isOld ?
                                            cToCDiff.loadDiffsForOldFile(mapping.getFile(), mapping.getComment(), isDirectParent) :
                                            cToCDiff.loadDiffsForNewFile(mapping.getFile(), mapping.getComment(), isDirectParent));
                                    return minedInstances.stream()
                                            .peek(a -> a.setDuplicationId(mapping.getDuplicationId()));
                        })
                        .collect(Collectors.toList());
    }

    /**
     * Removes duplicate mappings: same file, same comment text, same containing class, same method
     * DebtHunter can detect the same SATD multiple times, but we keep different method overloads as separate SATD
     */
    private static void removeDuplicateMappings(List<OldToNewCommentMapping> mappingList) {
        // Use a set to track unique combinations: file + comment text + containing class + containing method
        // This allows keeping the same comment in different method overloads as separate SATD
        final Set<String> seen = new HashSet<>();
        final List<OldToNewCommentMapping> toRemove = new ArrayList<>();
        
        for (OldToNewCommentMapping mapping : mappingList) {
            // Include method name to distinguish between method overloads
            String key = mapping.getFile() + ":::" + mapping.getComment().getComment() 
                    + ":::" + mapping.getComment().getContainingClass()
                    + ":::" + mapping.getComment().getContainingMethod();
            if (seen.contains(key)) {
                toRemove.add(mapping);
            } else {
                seen.add(key);
            }
        }
        
        mappingList.removeAll(toRemove);
    }

    private static void populateDuplicationIds(List<OldToNewCommentMapping> mappingList) {
        final Map<OldToNewCommentMapping, Integer> curDupIds = new HashMap<>();
        mappingList.forEach(mapping -> {
            if( !curDupIds.containsKey(mapping) ) {
                curDupIds.put(mapping, 0);
            }
            mapping.setDuplicationId(curDupIds.get(mapping));
            curDupIds.put(mapping, curDupIds.get(mapping) + 1);
        });
    }

    public String getDiffString() {
        return this.secondRepo.getCommitHash();
    }
}
