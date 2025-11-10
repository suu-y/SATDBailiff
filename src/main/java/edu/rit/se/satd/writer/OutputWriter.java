package edu.rit.se.satd.writer;

import edu.rit.se.satd.model.SATDDifference;
import edu.rit.se.satd.model.SATDSnapshot;

import java.io.IOException;

public interface OutputWriter {

    /**
     * Writes the SATD diff instance to an output format
     * @param diff an SATDDifference object from a comparison between two project tags
     * @throws IOException thrown if an error is encountered during processing
     */
    void writeDiff(SATDDifference diff) throws IOException;

    /**
     * Writes the SATD snapshot for a single revision to an output format.
     * Default implementation is a no-op for writers that do not support snapshots.
     * @param snapshot snapshot of SATD instances in a revision
     * @throws IOException if an error occurs while writing the snapshot
     */
    default void writeSnapshot(SATDSnapshot snapshot) throws IOException {
        // optional
    }

    /**
     * Finishes any write processes and terminated the writer
     */
    void close();

}
