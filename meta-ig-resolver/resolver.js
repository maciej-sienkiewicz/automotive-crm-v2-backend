import { chromium } from 'playwright-core';

/**
 * Nazwa profilu na Instagramie odczytana ze strony reklamodawcy w Bibliotece
 * reklam Meta.
 *
 * DLACZEGO PRZEGLĄDARKA. Publiczne `ads_archive` nie ma pola z Instagramem —
 * sprawdzone, nie ma go w żadnym wariancie zapytania. Webowa Biblioteka reklam
 * pokazuje ten uchwyt w sekcji „Informacje o reklamodawcy", ale dociąga go
 * dopiero JavaScript po rozwinięciu sekcji.
 *
 * SKĄD BIERZEMY DANE. Nie z wyrenderowanego HTML-a, tylko z ODPOWIEDZI SIECIOWEJ,
 * którą strona sama pobiera. Powód jest konkretny: w wyrenderowanym widoku
 * uchwyt Facebooka i uchwyt Instagrama są nie do odróżnienia inaczej niż po
 * przesunięciu ikony w arkuszu sprite'ów (`-605px` kontra `-639px`). Kod oparty
 * na współrzędnej w obrazku przestałby działać przy pierwszej przebudowie
 * arkusza i — co gorsza — zacząłby PO CICHU zwracać alias Facebooka jako
 * Instagram. W odpowiedzi sieciowej te same dane mają nazwy: `ig_username`
 * i `page_alias`. Nazwa pola jest nieporównanie trwalsza niż piksel.
 *
 * ZASADA NADRZĘDNA: przy jakiejkolwiek wątpliwości zwracamy null. Nigdy
 * `page_alias`, nigdy „ten drugi w kolejności", nigdy zgadywania z nazwy firmy.
 * Brak nazwy jest tani — błędna nazwa to cudzy profil pokazany właścicielowi
 * studia jako konkurent.
 */

/** Rozpoznajemy odpowiedź po nazwie pola, nie po adresie — adresy Meta zmienia częściej. */
const IG_FIELD = /"ig_username"\s*:\s*"([A-Za-z0-9._]{1,30})"/;

/**
 * Ścieżka kliknięć do sekcji z danymi. To najkruchszy element całości: opiera
 * się na napisach interfejsu. Trzymamy kilka wariantów i akceptujemy, że
 * kiedyś przestaną pasować — wtedy wynik to `blocked`, nie zła nazwa.
 */
const OPEN_DETAILS = [
    /Zobacz szczegóły reklamy/i,
    /See ad details/i,
    /Zestawienie danych/i,
    /szczegół/i,
];

const OPEN_ADVERTISER = [
    /Informacje o reklamodawcy/i,
    /About the advertiser/i,
    /reklamodawc/i,
];

const libraryUrl = (pageId) =>
    'https://www.facebook.com/ads/library/' +
    `?active_status=active&ad_type=all&country=PL&view_all_page_id=${encodeURIComponent(pageId)}`;

/**
 * Wynik rozróżnia „reklamodawca nie ma Instagrama" od „nie udało się sprawdzić".
 * To rozróżnienie jest podstawą alertowania: pierwszy przypadek jest normalny,
 * drugi — gdy zdarza się masowo — znaczy, że Meta przebudowała stronę.
 */
const EMPTY = 'empty';      // strona wczytana, sekcja rozwinięta, pola nie ma
const OK = 'ok';
const BLOCKED = 'blocked';  // strona się nie wczytała albo nie doszliśmy do sekcji
const TIMEOUT = 'timeout';
const ERROR = 'error';

export async function resolve(pageId, { timeoutMs = 45_000 } = {}) {
    const started = Date.now();
    let browser;

    try {
        browser = await chromium.launch({
            executablePath: process.env.CHROMIUM_PATH || undefined,
            args: ['--disable-dev-shm-usage', '--disable-gpu'],
        });

        const page = await browser.newPage({
            locale: 'pl-PL',
            viewport: { width: 1400, height: 1000 },
        });

        // Nasłuch MUSI być podpięty przed nawigacją — odpowiedź z danymi
        // potrafi przyjść w trakcie ładowania, zanim zaczniemy klikać.
        let handle = null;
        let sawPageInfo = false;

        page.on('response', async (response) => {
            if (handle) return;
            try {
                const body = await response.text();
                if (body.includes('ad_library_page_info')) sawPageInfo = true;
                const match = body.match(IG_FIELD);
                if (match) handle = match[1];
            } catch {
                // Odpowiedzi binarne, przerwane i te bez ciała. Nic tu po nas.
            }
        });

        const deadline = () => Math.max(1_000, timeoutMs - (Date.now() - started));

        await page.goto(libraryUrl(pageId), {
            waitUntil: 'domcontentloaded',
            timeout: deadline(),
        });
        await page.waitForTimeout(6_000);

        const bodyText = await page.evaluate(() => document.body.innerText).catch(() => '');
        // Kikut z wyzwaniem antybotowym ma kilkaset bajtów. Prawdziwa strona
        // reklamodawcy ma dziesiątki kilobajtów tekstu.
        if (bodyText.length < 500) {
            return { status: BLOCKED, igUsername: null, reason: 'strona nie wczytała treści' };
        }

        await clickFirst(page, OPEN_DETAILS, deadline);
        if (!handle) await clickFirst(page, OPEN_ADVERTISER, deadline);
        if (!handle) await page.waitForTimeout(2_500);

        if (handle) return { status: OK, igUsername: handle, reason: null };

        // Dotarliśmy do danych o stronie, ale pola z Instagramem w nich nie ma
        // — reklamodawca go po prostu nie podał. To poprawny wynik, nie awaria.
        if (sawPageInfo) return { status: EMPTY, igUsername: null, reason: null };

        return { status: BLOCKED, igUsername: null, reason: 'nie dotarliśmy do danych o reklamodawcy' };
    } catch (error) {
        const message = String(error?.message || error).slice(0, 200);
        const status = /timeout|Timeout/.test(message) ? TIMEOUT : ERROR;
        return { status, igUsername: null, reason: message };
    } finally {
        // Przeglądarka startuje na każde żądanie i ginie po nim. Przy kilkunastu
        // wywołaniach w miesiącu sekunda startu nic nie kosztuje, a proces
        // żyjący tygodniami z Chromium w tle wycieka pamięcią.
        await browser?.close().catch(() => {});
    }
}

async function clickFirst(page, patterns, deadline) {
    for (const pattern of patterns) {
        const target = page.getByText(pattern).first();
        const found = await target.count().catch(() => 0);
        if (!found) continue;
        await target.click({ timeout: Math.min(5_000, deadline()) }).catch(() => {});
        await page.waitForTimeout(3_000);
        return true;
    }
    return false;
}
