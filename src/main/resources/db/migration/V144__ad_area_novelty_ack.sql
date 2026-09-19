-- „Odznacz nowe": do którego dnia studio potwierdziło, że widziało nowości
-- w swoim rejonie.
--
-- Odznaki „Nowa firma" / „Nowa kampania" gasną same po oknie nowości, ale to
-- jest odpowiedź na upływ czasu, nie na przeczytanie. Kto przejrzał tabelę
-- w poniedziałek, przez kolejne dwa tygodnie patrzy na te same pigułki i
-- przestaje je widzieć - a wtedy nie zauważy tej jednej, która pojawi się w piątek.
--
-- Data, nie znacznik czasu: odznaczenie znaczy „widziałem wszystko, co ruszyło
-- do dziś włącznie". Kampania z jutrzejszym startem jest znowu nowa.
--
-- NULL = studio jeszcze nigdy nie odznaczało (stan domyślny, wszystko w oknie świeci).
ALTER TABLE meta_ad_area_settings
    ADD COLUMN IF NOT EXISTS novelty_acked_through DATE;
