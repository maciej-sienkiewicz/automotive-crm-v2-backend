package pl.detailing.crm.trends.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Creates the Trends module tables on startup if they don't already exist.
 * All statements are idempotent (CREATE TABLE/INDEX IF NOT EXISTS).
 */
@Component
class TrendsSchemaInitializer(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(TrendsSchemaInitializer::class.java)

    @PostConstruct
    fun initSchema() {
        log.info("Initializing Trends module schema.")
        STATEMENTS.forEach { jdbc.execute(it) }
        log.info("Trends module schema ready.")
    }

    companion object {
        private val STATEMENTS = listOf(
            """CREATE TABLE IF NOT EXISTS tracked_keywords (
                id              BIGSERIAL PRIMARY KEY,
                keyword         TEXT        NOT NULL UNIQUE,
                status          TEXT        NOT NULL DEFAULT 'PENDING',
                source          TEXT        NOT NULL DEFAULT 'SEED',
                added_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                last_fetched_at TIMESTAMPTZ,
                relevance_score DOUBLE PRECISION
            )""",

            """CREATE TABLE IF NOT EXISTS keyword_metrics (
                id                   BIGSERIAL PRIMARY KEY,
                keyword_id           BIGINT NOT NULL REFERENCES tracked_keywords(id),
                location_code        INT    NOT NULL,
                search_volume        INT,
                cpc                  DOUBLE PRECISION,
                competition          TEXT,
                competition_index    INT,
                low_top_of_page_bid  DOUBLE PRECISION,
                high_top_of_page_bid DOUBLE PRECISION,
                fetched_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE (keyword_id, location_code)
            )""",

            """CREATE TABLE IF NOT EXISTS monthly_searches (
                id            BIGSERIAL PRIMARY KEY,
                keyword_id    BIGINT NOT NULL REFERENCES tracked_keywords(id),
                location_code INT    NOT NULL,
                year          INT    NOT NULL,
                month         INT    NOT NULL,
                search_volume INT,
                UNIQUE (keyword_id, location_code, year, month)
            )""",

            """CREATE TABLE IF NOT EXISTS trend_data (
                id            BIGSERIAL PRIMARY KEY,
                keyword_id    BIGINT NOT NULL REFERENCES tracked_keywords(id),
                date          DATE   NOT NULL,
                trend_index   INT,
                location_code INT    NOT NULL DEFAULT 2616,
                UNIQUE (keyword_id, date, location_code)
            )""",

            """CREATE TABLE IF NOT EXISTS sync_status (
                task_name       TEXT PRIMARY KEY,
                last_run_at     TIMESTAMPTZ,
                last_success_at TIMESTAMPTZ,
                status          TEXT NOT NULL DEFAULT 'IDLE',
                details         TEXT
            )""",

            "CREATE INDEX IF NOT EXISTS idx_tracked_keywords_status          ON tracked_keywords (status)",
            "CREATE INDEX IF NOT EXISTS idx_tracked_keywords_relevance_score ON tracked_keywords (relevance_score DESC NULLS LAST)",
            "CREATE INDEX IF NOT EXISTS idx_keyword_metrics_keyword_id       ON keyword_metrics (keyword_id)",
            "CREATE INDEX IF NOT EXISTS idx_keyword_metrics_location         ON keyword_metrics (location_code)",
            "CREATE INDEX IF NOT EXISTS idx_monthly_searches_keyword_loc     ON monthly_searches (keyword_id, location_code)",
            "CREATE INDEX IF NOT EXISTS idx_trend_data_keyword_date          ON trend_data (keyword_id, date)"
        )
    }
}
