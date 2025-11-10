-- SELECT COUNT(DISTINCT CONCAT_WS('|', f.f_path, f.start_line, f.end_line, f.containing_method)) AS distinct_firstfile_satd
-- FROM satd.SATD AS s
-- JOIN satd.SATDInFile AS f
--   ON s.first_file = f.f_id;

-- -- List distinct SATD entries from the new revision (second_file side).
-- SELECT COUNT(DISTINCT CONCAT_WS('|', f.f_path, f.start_line, f.end_line, f.containing_method)) AS distinct_secondfile_satd
-- FROM satd.SATD AS s
-- JOIN satd.SATDInFile AS f
--   ON s.second_file = f.f_id;

-- -- Summarize how SATD instances changed between first_file and second_file.
-- WITH satd_pairs AS (
--     SELECT
--         s.satd_id,
--         f1.f_path  AS first_path,
--         f1.start_line AS first_start_line,
--         f1.end_line   AS first_end_line,
--         f1.containing_method AS first_method,
--         f1.f_comment AS first_comment,
--         f2.f_path  AS second_path,
--         f2.start_line AS second_start_line,
--         f2.end_line   AS second_end_line,
--         f2.containing_method AS second_method,
--         f2.f_comment AS second_comment
--     FROM satd.SATD AS s
--     LEFT JOIN satd.SATDInFile AS f1 ON s.first_file = f1.f_id
--     LEFT JOIN satd.SATDInFile AS f2 ON s.second_file = f2.f_id
-- )
-- SELECT
--     CASE
--         WHEN first_path IS NULL AND second_path IS NOT NULL THEN 'ADDED'
--         WHEN first_path IS NOT NULL AND second_path IS NULL THEN 'REMOVED'
--         WHEN first_path = second_path
--              AND first_start_line = second_start_line
--              AND first_end_line = second_end_line
--              AND first_method = second_method
--              AND first_comment = second_comment THEN 'UNCHANGED'
--         ELSE 'MODIFIED'
--     END AS change_type,
--     COUNT(*) AS satd_count
-- FROM satd_pairs
-- GROUP BY change_type
-- ORDER BY change_type;

-- -- Count the number of SATD instances for each resolution
-- SELECT
--     resolution,
--     COUNT(*) AS cnt
-- FROM satd.SATD
-- GROUP BY resolution ORDER BY resolution;

-- Project-wise SATD resolution summary
SELECT
    p.p_name AS project_name,
    COALESCE(s.resolution, 'UNKNOWN') AS resolution,
    COUNT(*) AS satd_count
FROM satd.SATD AS s
JOIN satd.Projects AS p
  ON s.p_id = p.p_id
GROUP BY
    p.p_name,
    s.resolution
ORDER BY
    p.p_name,
    resolution;

-- Project / release pair wise SATD resolution summary
SELECT
    p.p_name AS project_name,
    s.first_commit,
    s.second_commit,
    COALESCE(s.resolution, 'UNKNOWN') AS resolution,
    COUNT(*) AS satd_count
FROM satd.SATD AS s
JOIN satd.Projects AS p
  ON s.p_id = p.p_id
GROUP BY
    p.p_name,
    s.first_commit,
    s.second_commit,
    s.resolution
ORDER BY
    p.p_name,
    s.first_commit,
    s.second_commit,
    resolution;

-- Snapshot counts per project and commit
SELECT
    p.p_name AS project_name,
    snap.commit_hash,
    snap.classification,
    COUNT(*) AS satd_count
FROM satd.SATDSnapshots AS snap
JOIN satd.Projects AS p
  ON snap.p_id = p.p_id
GROUP BY
    p.p_name,
    snap.commit_hash,
    snap.classification
ORDER BY
    p.p_name,
    snap.commit_hash,
    snap.classification;