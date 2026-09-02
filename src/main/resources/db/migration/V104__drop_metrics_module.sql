-- Wyburzenie modułu metryk (V65–V67). Analityka biznesowa w czasie rzeczywistym
-- żyje teraz w Redisie (moduł live-metrics: strumień zdarzeń + liczniki kubełkowe),
-- a technicznego eksportu do Prometheusa/Grafany już nie ma. Tabele poniżej nie mają
-- żadnego czytelnika w kodzie — zostają usunięte razem z widokami raportowymi.

DROP VIEW IF EXISTS metric_endpoint_usage;
DROP VIEW IF EXISTS metric_studio_directory;

DROP TABLE IF EXISTS metric_error_group_impacts;
DROP TABLE IF EXISTS metric_error_events;
DROP TABLE IF EXISTS metric_error_groups;
DROP TABLE IF EXISTS metric_api_endpoint_daily;
DROP TABLE IF EXISTS metric_studio_api_daily;
DROP TABLE IF EXISTS metric_api_endpoints;
DROP TABLE IF EXISTS metric_user_sessions;
DROP TABLE IF EXISTS metric_events;
DROP TABLE IF EXISTS metric_daily_studio_snapshots;
DROP TABLE IF EXISTS metric_daily_platform_snapshots;
