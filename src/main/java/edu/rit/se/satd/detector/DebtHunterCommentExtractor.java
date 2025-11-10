package edu.rit.se.satd.detector;

import edu.rit.se.git.GitUtil;
import edu.rit.se.satd.comment.model.GroupedComment;
import edu.rit.se.satd.comment.model.RepositoryComments;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import parsing.JavaParsing;
import tool.DataHandler;
import tool.UseCaseOne;
import weka.core.Instance;
import weka.core.Instances;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class DebtHunterCommentExtractor {

    private static final Path DEBTHUNTER_ROOT = Paths.get("lib", "DebtHunter-Tool").toAbsolutePath().normalize();
    private static final Path BINARY_MODEL = DEBTHUNTER_ROOT.resolve(Paths.get("preTrainedModels", "DHbinaryClassifier.model"));
    private static final Path MULTI_MODEL = DEBTHUNTER_ROOT.resolve(Paths.get("preTrainedModels", "DHmultiClassifier.model"));

    private DebtHunterCommentExtractor() {
    }

    public static Map<String, RepositoryComments> extractSatdComments(Git gitInstance,
                                                                      RevCommit commit,
                                                                      List<String> filesToSearch)
            throws Exception {
        final Set<String> filterSet = filesToSearch == null ? Collections.emptySet()
                : filesToSearch.stream()
                .map(path -> path.replace('\\', '/'))
                .collect(Collectors.toSet());

        Path tempRoot = Files.createTempDirectory("debthunter-" + commit.getName());
        try {
            Path projectPath = tempRoot.resolve("project");
            Files.createDirectories(projectPath);

            boolean exported = exportCommitToDirectory(gitInstance, commit, projectPath, filterSet);
            if (!exported) {
                return new HashMap<>();
            }

            Path outputPath = tempRoot.resolve("output");
            Files.createDirectories(outputPath);

            Instances labeledInstances = runDebtHunterLabeling(projectPath, outputPath);

            return convertInstancesToRepositoryComments(labeledInstances, projectPath, filterSet);
        } finally {
            cleanup(tempRoot);
        }
    }

    private static Instances runDebtHunterLabeling(Path projectPath, Path outputPath) throws Exception {
        Instances dataset = JavaParsing.processDirectory(projectPath.toString(), outputPath.toString());

        Instances onlyComments = new Instances(dataset);
        while (onlyComments.numAttributes() > 1) {
            onlyComments = DataHandler.removeAttribute(onlyComments, "first");
            onlyComments = DataHandler.removeAttribute(onlyComments, "1,2,4,5,6,7");
        }

        return UseCaseOne.userClassifierLabeling(
                onlyComments,
                dataset,
                BINARY_MODEL.toString(),
                MULTI_MODEL.toString()
        );
    }

    private static boolean exportCommitToDirectory(Git gitInstance,
                                                   RevCommit commit,
                                                   Path outputRoot,
                                                   Set<String> filterSet) throws IOException {
        int exportedCount = 0;
        try (TreeWalk treeWalk = GitUtil.getTreeWalker(gitInstance, commit)) {
            while (treeWalk.next()) {
                String pathString = treeWalk.getPathString();
                if (!pathString.endsWith(".java")) {
                    continue;
                }
                if (!filterSet.isEmpty() && !filterSet.contains(pathString)) {
                    continue;
                }
                ObjectLoader loader = gitInstance.getRepository().open(treeWalk.getObjectId(0));
                Path targetFile = outputRoot.resolve(pathString);
                Files.createDirectories(targetFile.getParent());
                try (InputStream in = loader.openStream()) {
                    Files.copy(in, targetFile);
                }
                exportedCount++;
            }
        }
        return exportedCount > 0;
    }

    private static Map<String, RepositoryComments> convertInstancesToRepositoryComments(Instances labeledInstances,
                                                                                        Path projectPath,
                                                                                        Set<String> filterSet) {
        Map<String, RepositoryComments> result = new HashMap<>();
        if (labeledInstances == null || labeledInstances.isEmpty()) {
            return result;
        }
        Path normalizedProjectPath = projectPath.toAbsolutePath().normalize();

        int classificationIndex = labeledInstances.attribute("classification").index();
        int fileNameIndex = labeledInstances.attribute("fileName").index();
        int methodNameIndex = labeledInstances.attribute("methodName").index();
        int beginIndex = labeledInstances.attribute("beginLine").index();
        int endIndex = labeledInstances.attribute("endLine").index();
        int commentIndex = labeledInstances.attribute("comment").index();
        int packageIndex = labeledInstances.attribute("package").index();

        for (int i = 0; i < labeledInstances.numInstances(); i++) {
            Instance instance = labeledInstances.instance(i);

            String classification = instance.stringValue(classificationIndex);
            if (classification == null || classification.isEmpty()
                    || "WITHOUT_CLASSIFICATION".equalsIgnoreCase(classification)) {
                continue;
            }

            String methodName = instance.stringValue(methodNameIndex);
            if (methodName == null || methodName.isEmpty() || "None".equalsIgnoreCase(methodName)) {
                continue;
            }

            String fileNameRaw = instance.stringValue(fileNameIndex);
            if (fileNameRaw == null || fileNameRaw.isEmpty()) {
                continue;
            }

            String normalizedPath = normalizeDebtHunterFilePath(fileNameRaw, normalizedProjectPath);
            if (normalizedPath == null || !normalizedPath.endsWith(".java")) {
                continue;
            }

            if (!filterSet.isEmpty() && !filterSet.contains(normalizedPath)) {
                continue;
            }

            Path absoluteFile = normalizedProjectPath.resolve(normalizedPath).normalize();
            if (!absoluteFile.startsWith(normalizedProjectPath)) {
                continue;
            }

            String commentText = instance.stringValue(commentIndex);
            int startLine = parseIntSafely(instance.stringValue(beginIndex), -1);
            int endLine = parseIntSafely(instance.stringValue(endIndex), startLine);
            String packageName = instance.stringValue(packageIndex);
            String containingClass = deriveContainingClass(normalizedPath, packageName);
                    GroupedComment groupedComment = GroupedComment.fromDebtHunterData(
                    startLine,
                    endLine,
                    commentText,
                    containingClass,
                    methodName
            );
                    groupedComment.setSatdClassification(classification);

            RepositoryComments repositoryComments = result.computeIfAbsent(normalizedPath, key -> new RepositoryComments());
            repositoryComments.addComments(Collections.singletonList(groupedComment));
        }

        return result;
    }

    private static String normalizeDebtHunterFilePath(String fileNameRaw, Path normalizedProjectPath) {
        if (fileNameRaw == null) {
            return null;
        }

        String sanitized = fileNameRaw.trim();
        if (sanitized.isEmpty()) {
            return null;
        }

        sanitized = sanitized.replace('\\', '/');
        sanitized = sanitized.replaceAll("/{2,}", "/");

        String projectPathString = normalizedProjectPath.toString().replace('\\', '/');
        if (!projectPathString.endsWith("/")) {
            projectPathString = projectPathString + "/";
        }

        if (sanitized.startsWith(projectPathString)) {
            sanitized = sanitized.substring(projectPathString.length());
        } else {
            String lowered = sanitized.toLowerCase();
            String[] markers = {
                    "/src/main/java/",
                    "/src/test/java/",
                    "/src/main/resources/",
                    "/src/test/resources/",
                    "/src/"
            };
            for (String marker : markers) {
                int idx = lowered.indexOf(marker);
                if (idx >= 0) {
                    sanitized = sanitized.substring(idx + 1); // remove leading slash before marker
                    break;
                }
            }
        }

        sanitized = sanitized.replaceAll("^/+", "");
        if (sanitized.isEmpty()) {
            return null;
        }

        Path candidate = normalizedProjectPath.resolve(sanitized).normalize();
        if (!candidate.startsWith(normalizedProjectPath)) {
            return null;
        }

        return normalizedProjectPath.relativize(candidate).toString().replace(File.separatorChar, '/');
    }

    private static int parseIntSafely(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String deriveContainingClass(String normalizedPath, String packageName) {
        String pathWithoutExtension = normalizedPath.substring(0, normalizedPath.length() - ".java".length());
        String classPath = pathWithoutExtension;
        if (classPath.startsWith("src/")) {
            classPath = classPath.substring("src/".length());
        }
        if (classPath.startsWith("main/java/")) {
            classPath = classPath.substring("main/java/".length());
        } else if (classPath.startsWith("main/resources/")) {
            classPath = classPath.substring("main/resources/".length());
        } else if (classPath.startsWith("test/java/")) {
            classPath = classPath.substring("test/java/".length());
        }
        String simpleClass = classPath.contains("/") ? classPath.substring(classPath.lastIndexOf('/') + 1) : classPath;
        if (packageName != null && !packageName.isEmpty()) {
            return packageName + "." + simpleClass;
        }
        return classPath.replace('/', '.');
    }

    private static void cleanup(Path tempRoot) {
        try {
            FileUtils.deleteDirectory(tempRoot.toFile());
        } catch (IOException ignored) {
        }
    }

}
