package edu.rit.se.satd.detector;

import weka.classifiers.meta.FilteredClassifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Maintains a wrapper implementation for the DebtHunter Tool:
 * https://github.com/ibu00024/DebtHunter-Tool
 * 
 * This detector uses two classifiers:
 * 1. Binary classifier: Determines if a comment is SATD or not
 * 2. Multi-class classifier: Classifies SATD into types (TEST, IMPLEMENTATION, DESIGN, DEFECT, DOCUMENTATION)
 */
public class DebtHunterDetectorImpl implements SATDDetector {

    private FilteredClassifier binaryClassifier;
    private FilteredClassifier multiClassifier;
    private Instances binaryDatasetStructure;
    private Instances multiDatasetStructure;

    /**
     * SATD Type enumeration matching DebtHunter's classification
     */
    public enum SATDType {
        TEST,
        IMPLEMENTATION,
        DESIGN,
        DEFECT,
        DOCUMENTATION,
        WITHOUT_CLASSIFICATION  // Non-SATD
    }

    public DebtHunterDetectorImpl() {
        try {
            // Load pre-trained models from JAR resources
            // First try to load from JAR resources, then fall back to file system
            String binaryModelPath = "/lib/DebtHunter-Tool/preTrainedModels/DHbinaryClassifier.model";
            String multiModelPath = "/lib/DebtHunter-Tool/preTrainedModels/DHmultiClassifier.model";
            
            File binaryModelFile = null;
            File multiModelFile = null;
            
            // Try to load from JAR resources first
            try (InputStream binaryStream = getClass().getResourceAsStream(binaryModelPath);
                 InputStream multiStream = getClass().getResourceAsStream(multiModelPath)) {
                
                if (binaryStream != null && multiStream != null) {
                    // Extract from JAR to temporary files
                    Path tempDir = Files.createTempDirectory("debthunter-models-");
                    binaryModelFile = tempDir.resolve("DHbinaryClassifier.model").toFile();
                    multiModelFile = tempDir.resolve("DHmultiClassifier.model").toFile();
                    
                    // Copy streams to temporary files
                    try (FileOutputStream binaryOut = new FileOutputStream(binaryModelFile);
                         FileOutputStream multiOut = new FileOutputStream(multiModelFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = binaryStream.read(buffer)) != -1) {
                            binaryOut.write(buffer, 0, bytesRead);
                        }
                        while ((bytesRead = multiStream.read(buffer)) != -1) {
                            multiOut.write(buffer, 0, bytesRead);
                        }
                    }
                } else {
                    // Fall back to file system path
                    String basePath = "lib/DebtHunter-Tool/preTrainedModels/";
                    binaryModelFile = new File(basePath + "DHbinaryClassifier.model");
                    multiModelFile = new File(basePath + "DHmultiClassifier.model");
                    
                    if (!binaryModelFile.exists()) {
                        throw new RuntimeException("Binary classifier model not found at: " + binaryModelPath + " or " + binaryModelFile.getAbsolutePath());
                    }
                    if (!multiModelFile.exists()) {
                        throw new RuntimeException("Multi-class classifier model not found at: " + multiModelPath + " or " + multiModelFile.getAbsolutePath());
                    }
                }
            }

            // Load the classifiers
            this.binaryClassifier = (FilteredClassifier) weka.core.SerializationHelper.read(binaryModelFile.getAbsolutePath());
            this.multiClassifier = (FilteredClassifier) weka.core.SerializationHelper.read(multiModelFile.getAbsolutePath());

            // Create dataset structure for binary classification
            ArrayList<Attribute> binaryAttributes = new ArrayList<>();
            binaryAttributes.add(new Attribute("comment", (ArrayList<String>) null));
            ArrayList<String> binaryClassValues = new ArrayList<>();
            binaryClassValues.add("0");  // SATD
            binaryClassValues.add("1");  // Non-SATD
            binaryAttributes.add(new Attribute("class", binaryClassValues));
            this.binaryDatasetStructure = new Instances("BinaryCommentClassification", binaryAttributes, 1);
            this.binaryDatasetStructure.setClassIndex(this.binaryDatasetStructure.numAttributes() - 1);
            
            // Create dataset structure for multi-class classification
            ArrayList<Attribute> multiAttributes = new ArrayList<>();
            multiAttributes.add(new Attribute("comment", (ArrayList<String>) null));
            ArrayList<String> multiClassValues = new ArrayList<>();
            multiClassValues.add("TEST");
            multiClassValues.add("IMPLEMENTATION");
            multiClassValues.add("DESIGN");
            multiClassValues.add("DEFECT");
            multiClassValues.add("DOCUMENTATION");
            multiAttributes.add(new Attribute("class", multiClassValues));
            this.multiDatasetStructure = new Instances("MultiCommentClassification", multiAttributes, 1);
            this.multiDatasetStructure.setClassIndex(this.multiDatasetStructure.numAttributes() - 1);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DebtHunter detector: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isSATD(String comment) {
        try {
            // Create instance for the comment
            DenseInstance instance = new DenseInstance(2);
            instance.setDataset(this.binaryDatasetStructure);
            instance.setValue(0, comment);

            // Classify with binary classifier
            // Returns 0.0 for SATD, 1.0 for non-SATD
            double classification = this.binaryClassifier.classifyInstance(instance);
            
            return classification == 0.0;  // true if SATD
        } catch (Exception e) {
            System.err.println("Error classifying comment: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the detailed SATD type for a comment
     * @param comment The comment to classify
     * @return The SATD type, or WITHOUT_CLASSIFICATION if not SATD
     */
    public SATDType getSATDType(String comment) {
        try {
            // First, check if it's SATD with binary classifier
            DenseInstance binaryInstance = new DenseInstance(2);
            binaryInstance.setDataset(this.binaryDatasetStructure);
            binaryInstance.setValue(0, comment);
            double binaryResult = this.binaryClassifier.classifyInstance(binaryInstance);
            
            if (binaryResult == 1.0) {
                // Not SATD
                return SATDType.WITHOUT_CLASSIFICATION;
            }

            // It's SATD, now classify the type with multi-class classifier
            DenseInstance multiInstance = new DenseInstance(2);
            multiInstance.setDataset(this.multiDatasetStructure);
            multiInstance.setValue(0, comment);
            double multiResult = this.multiClassifier.classifyInstance(multiInstance);
            
            // Map result to SATD type
            // 0.0 = TEST, 1.0 = IMPLEMENTATION, 2.0 = DESIGN, 3.0 = DEFECT, 4.0 = DOCUMENTATION
            switch ((int) multiResult) {
                case 0:
                    return SATDType.TEST;
                case 1:
                    return SATDType.IMPLEMENTATION;
                case 2:
                    return SATDType.DESIGN;
                case 3:
                    return SATDType.DEFECT;
                case 4:
                    return SATDType.DOCUMENTATION;
                default:
                    return SATDType.WITHOUT_CLASSIFICATION;
            }
        } catch (Exception e) {
            System.err.println("Error getting SATD type: " + e.getMessage());
            return SATDType.WITHOUT_CLASSIFICATION;
        }
    }

    /**
     * Get the SATD type as a string
     * @param comment The comment to classify
     * @return The SATD type as a string
     */
    public String getSATDTypeString(String comment) {
        return getSATDType(comment).toString();
    }
}

