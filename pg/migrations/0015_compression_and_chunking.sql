-- 0015_compression_and_chunking.sql
-- Native TimescaleDB compression on the five high-volume hypertables, plus a
-- saner chunk interval for heart_rate_sample.
--
-- Why: the DB was 725 MB with heart_rate_sample alone at 495 MB (1.4M rows),
-- entirely uncompressed, on an RPi SD card. Time-series compression typically
-- yields 3-8x here. heart_rate_sample also used 7-day chunks (117 chunks for
-- ~2.2 years) while every other hypertable uses 30+ days; the 117 Append nodes
-- cost ~80-240 ms of planning on any query that can't prune by time.
--
-- Layout choices (matter for the /sync write paths in routes.py):
--   - samples tables (heart_rate_sample, speed_sample): orderby includes
--     source_id so each compressed batch carries source_id min/max metadata —
--     the idempotency DELETE (WHERE source_id = ANY(...)) then decompresses
--     only matching batches, not the whole chunk, when a re-sync touches
--     compressed history.
--   - interval tables (steps, distance, total_calories_burned): orderby
--     start_at, so ON CONFLICT (start_at, id) probes prune by batch metadata.
--   - segmentby user_id in all cases (single-digit user count; whole-user
--     batches compress best).
--
-- Operational note: writes into chunks older than 30 days (Force Sync over an
-- old range, backfill/ importers) decompress the touched batches in place and
-- are slower; the policy recompresses them on its next run. Normal delta syncs
-- only ever touch recent, uncompressed chunks.
--
-- Only future heart_rate_sample chunks get the 30-day interval — existing
-- 7-day chunks stay, but compression collapses their per-chunk cost.

-- chunk interval: future chunks only
SELECT set_chunk_time_interval('heart_rate_sample', INTERVAL '30 days');

-- compression layout
ALTER TABLE heart_rate_sample SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'user_id',
    timescaledb.compress_orderby   = 'source_id, time'
);
ALTER TABLE speed_sample SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'user_id',
    timescaledb.compress_orderby   = 'source_id, time'
);
ALTER TABLE steps SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'user_id',
    timescaledb.compress_orderby   = 'start_at'
);
ALTER TABLE distance SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'user_id',
    timescaledb.compress_orderby   = 'start_at'
);
ALTER TABLE total_calories_burned SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'user_id',
    timescaledb.compress_orderby   = 'start_at'
);

-- compress chunks once they're fully older than 30 days
SELECT add_compression_policy('heart_rate_sample',     INTERVAL '30 days');
SELECT add_compression_policy('speed_sample',          INTERVAL '30 days');
SELECT add_compression_policy('steps',                 INTERVAL '30 days');
SELECT add_compression_policy('distance',              INTERVAL '30 days');
SELECT add_compression_policy('total_calories_burned', INTERVAL '30 days');

-- One-time: compress the existing backlog now instead of waiting for the
-- policy's first scheduled run. compress_chunk(…, true) skips chunks that are
-- already compressed, so this block is re-runnable.
SELECT compress_chunk(c, true) FROM show_chunks('heart_rate_sample',     older_than => INTERVAL '30 days') c;
SELECT compress_chunk(c, true) FROM show_chunks('speed_sample',          older_than => INTERVAL '30 days') c;
SELECT compress_chunk(c, true) FROM show_chunks('steps',                 older_than => INTERVAL '30 days') c;
SELECT compress_chunk(c, true) FROM show_chunks('distance',              older_than => INTERVAL '30 days') c;
SELECT compress_chunk(c, true) FROM show_chunks('total_calories_burned', older_than => INTERVAL '30 days') c;
