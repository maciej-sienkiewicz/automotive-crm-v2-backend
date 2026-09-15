import { createServer } from 'node:http';
import { resolve } from './resolver.js';

/**
 * Cienki serwer HTTP wokół [resolve]. Bez frameworka — jedna trasa robocza
 * i zdrowie, wbudowane `node:http` wystarcza, a każda zależność w obrazie
 * z przeglądarką to kolejna rzecz do aktualizowania.
 *
 * Kontrakt:
 *   POST /resolve  {"pageId":"101137765342120"}
 *     → 200 {"status":"ok","igUsername":"pro_garage_performance","tookMs":9123}
 *     → 200 {"status":"empty","igUsername":null,...}      reklamodawca nie ma IG
 *     → 200 {"status":"blocked"|"timeout"|"error",...}    nie udało się sprawdzić
 *   GET /health → 200 {"status":"up"}
 *
 * Zawsze 200 dla rozpoznanych stanów: dla wywołującego „nie udało się" nie jest
 * błędem protokołu, tylko wynikiem, który ma trafić do statystyk i alertu.
 */

const PORT = Number(process.env.PORT || 8080);
const TIMEOUT_MS = Number(process.env.RESOLVE_TIMEOUT_MS || 45_000);

/**
 * Jedno żądanie naraz. Każde uruchamia własne Chromium; dwa równolegle na
 * kontenerze z limitem 512 MB kończą się zabiciem przez OOM. Kolejka jest
 * uczciwsza niż przewracanie się pod obciążeniem — a przy kilkunastu
 * wywołaniach na miesiąc i tak nikt nie czeka.
 */
let busy = false;

const send = (res, code, body) => {
    const payload = JSON.stringify(body);
    res.writeHead(code, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': Buffer.byteLength(payload),
    });
    res.end(payload);
};

const readJson = (req) =>
    new Promise((ok, fail) => {
        let raw = '';
        req.on('data', (chunk) => {
            raw += chunk;
            // Zapora przed żądaniem, które chce nas wysycić pamięcią.
            if (raw.length > 4096) fail(new Error('żądanie zbyt duże'));
        });
        req.on('end', () => {
            try {
                ok(raw ? JSON.parse(raw) : {});
            } catch {
                fail(new Error('treść nie jest poprawnym JSON-em'));
            }
        });
        req.on('error', fail);
    });

const server = createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
        return send(res, 200, { status: 'up', busy });
    }

    if (req.method !== 'POST' || !req.url?.startsWith('/resolve')) {
        return send(res, 404, { error: 'nieznana trasa' });
    }

    if (busy) {
        return send(res, 429, { status: 'busy', igUsername: null });
    }

    let body;
    try {
        body = await readJson(req);
    } catch (error) {
        return send(res, 400, { error: String(error.message) });
    }

    const pageId = String(body.pageId ?? '').trim();
    // Identyfikator strony to u Meta liczba. Wpuszczenie czegokolwiek innego
    // oznaczałoby sklejanie cudzego tekstu w adres URL.
    if (!/^\d{5,25}$/.test(pageId)) {
        return send(res, 400, { error: 'pageId musi być liczbą o długości 5-25 znaków' });
    }

    busy = true;
    const started = Date.now();
    try {
        const result = await resolve(pageId, { timeoutMs: TIMEOUT_MS });
        const tookMs = Date.now() - started;
        console.log(JSON.stringify({ pageId, ...result, tookMs }));
        return send(res, 200, { ...result, tookMs });
    } catch (error) {
        console.error(JSON.stringify({ pageId, status: 'error', reason: String(error?.message) }));
        return send(res, 200, { status: 'error', igUsername: null, reason: 'wyjątek serwera' });
    } finally {
        busy = false;
    }
});

server.headersTimeout = TIMEOUT_MS + 15_000;
server.requestTimeout = TIMEOUT_MS + 15_000;

server.listen(PORT, () => console.log(`meta-ig-resolver nasłuchuje na :${PORT}`));

for (const signal of ['SIGTERM', 'SIGINT']) {
    process.on(signal, () => server.close(() => process.exit(0)));
}
