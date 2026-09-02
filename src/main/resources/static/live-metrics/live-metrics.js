/* Live-metrics dashboards — shared runtime (charts + data + live stream).
 * No external dependencies: plain SVG charts, fetch + SSE parsing, STOMP-free.
 * Mode is chosen by the page: LiveMetrics.boot({ mode: 'platform' | 'studio' }). */
(function () {
  'use strict';

  // ── series catalogue (fixed colour per entity, never by rank) ───────────────
  const SERIES = {
    RESERVATION_CREATED:                 { label: 'Rezerwacje',            color: 'var(--s1)' },
    VISIT_CREATED:                       { label: 'Wizyty',                color: 'var(--s2)' },
    'VISIT_CREATED:DIRECT':              { label: 'Wizyty · bezpośrednie', color: 'var(--s2)' },
    'VISIT_CREATED:FROM_RESERVATION':    { label: 'Wizyty · z rezerwacji', color: 'var(--s3)' },
    SERVICE_CREATED:                     { label: 'Nowe usługi',           color: 'var(--s7)' },
    'SERVICE_CREATED:SERVICE':           { label: 'Usługi',                color: 'var(--s7)' },
    'SERVICE_CREATED:PACKAGE':           { label: 'Pakiety',               color: 'var(--s5)' },
    PHOTO_UPLOADED:                      { label: 'Zdjęcia',               color: 'var(--s4)' },
    'PHOTO_UPLOADED:VISIT':              { label: 'Zdjęcia · wizyta',      color: 'var(--s4)' },
    'PHOTO_UPLOADED:VEHICLE':            { label: 'Zdjęcia · pojazd',      color: 'var(--s1)' },
    'PHOTO_UPLOADED:CHECKIN':            { label: 'Zdjęcia · check-in QR', color: 'var(--s3)' },
    'PHOTO_UPLOADED:BATCH_ORDER':        { label: 'Zdjęcia · zlecenie',    color: 'var(--s7)' },
    ACTIVITY_LOGGED:                     { label: 'Aktywność',             color: 'var(--s5)' }
  };
  const BASE = ['RESERVATION_CREATED', 'VISIT_CREATED', 'SERVICE_CREATED', 'PHOTO_UPLOADED', 'ACTIVITY_LOGGED'];
  const ATTR_LABELS = { visitId: 'wizyta', appointmentId: 'rezerwacja', serviceId: 'usługa', name: 'nazwa', module: 'moduł', action: 'akcja', photoId: 'zdjęcie', vehicleId: 'pojazd', checkinId: 'check-in', entryId: 'pozycja', recurrenceSeriesId: 'seria' };

  const fmtInt = n => new Intl.NumberFormat('pl-PL').format(n || 0);
  const pad = n => String(n).padStart(2, '0');
  const hhmm = d => pad(d.getHours()) + ':' + pad(d.getMinutes());
  const dmy = d => pad(d.getDate()) + '.' + pad(d.getMonth() + 1);
  const rel = iso => { if (!iso) return '—'; const s = (Date.now() - new Date(iso)) / 1000; if (s < 60) return 'przed chwilą'; if (s < 3600) return Math.floor(s / 60) + ' min temu'; if (s < 86400) return Math.floor(s / 3600) + ' h temu'; return Math.floor(s / 86400) + ' d temu'; };
  const el = (tag, attrs, children) => { const e = document.createElement(tag); if (attrs) for (const k in attrs) { if (k === 'class') e.className = attrs[k]; else if (k === 'text') e.textContent = attrs[k]; else e.setAttribute(k, attrs[k]); } (children || []).forEach(c => e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c)); return e; };
  const svgEl = (tag, attrs) => { const e = document.createElementNS('http://www.w3.org/2000/svg', tag); for (const k in attrs) e.setAttribute(k, attrs[k]); return e; };

  // ── tooltip (one, shared) ──────────────────────────────────────────────────
  const tip = el('div', { class: 'tooltip', role: 'status' });
  document.addEventListener('DOMContentLoaded', () => document.body.appendChild(tip));
  function showTip(x, y, title, rows) {
    tip.innerHTML = '';
    tip.appendChild(el('b', { text: title }));
    rows.forEach(r => tip.appendChild(el('div', { class: 'row' }, [el('span', {}, [el('i', { style: 'background:' + r.color }), r.label]), el('span', { text: fmtInt(r.value) })])));
    tip.style.display = 'block';
    const w = tip.offsetWidth, h = tip.offsetHeight;
    tip.style.left = Math.min(x + 14, window.innerWidth - w - 8) + 'px';
    tip.style.top = Math.max(8, y - h - 12) + 'px';
  }
  const hideTip = () => { tip.style.display = 'none'; };

  // ── charts ─────────────────────────────────────────────────────────────────
  // series: [{ key, label, color, points: [{at, count}] }]; kind: 'line' | 'bar'; stacked bars share x.
  function renderChart(host, opts) {
    host.innerHTML = '';
    const W = Math.max(320, host.clientWidth || 600), H = 230, m = { t: 14, r: 12, b: 26, l: 36 };
    const iw = W - m.l - m.r, ih = H - m.t - m.b;
    const svg = svgEl('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': opts.aria || '' });
    host.appendChild(svg);
    const series = opts.series.filter(s => s.points && s.points.length);
    if (!series.length) { host.appendChild(el('div', { class: 'empty', text: 'Brak danych' })); return; }
    const n = series[0].points.length;
    const totals = Array.from({ length: n }, (_, i) => opts.stacked ? series.reduce((a, s) => a + (s.points[i]?.count || 0), 0) : Math.max(...series.map(s => s.points[i]?.count || 0)));
    const maxRaw = Math.max(1, ...totals);
    const max = niceMax(maxRaw);
    const y = v => m.t + ih - (v / max) * ih;
    const xs = i => m.l + (n === 1 ? iw / 2 : (i / (n - 1)) * iw);
    const band = iw / n;

    // grid + y axis
    const grid = svgEl('g', { class: 'grid' }); svg.appendChild(grid);
    const ticks = 4;
    for (let t = 0; t <= ticks; t++) {
      const v = (max / ticks) * t, yy = y(v);
      grid.appendChild(svgEl('line', { x1: m.l, x2: W - m.r, y1: yy, y2: yy }));
      const lbl = svgEl('text', { x: m.l - 6, y: yy + 4, 'text-anchor': 'end' }); lbl.textContent = fmtInt(Math.round(v)); svg.appendChild(lbl);
    }
    svg.appendChild(svgEl('line', { class: 'axis', x1: m.l, x2: W - m.r, y1: m.t + ih, y2: m.t + ih, stroke: 'var(--axis)' }));

    // x labels
    const step = Math.max(1, Math.ceil(n / (iw / 60)));
    for (let i = 0; i < n; i += step) {
      const t = svgEl('text', { x: opts.kind === 'bar' ? m.l + band * i + band / 2 : xs(i), y: H - 8, 'text-anchor': 'middle' });
      t.textContent = opts.xLabel(series[0].points[i], i); svg.appendChild(t);
    }

    const lastPos = [];
    if (opts.kind === 'bar') {
      const acc = new Array(n).fill(0);
      const gap = Math.min(4, band * 0.25), bw = opts.stacked || series.length === 1 ? band - gap : (band - gap) / series.length;
      series.forEach((s, si) => {
        s.points.forEach((p, i) => {
          if (!p.count) return;
          const x0 = m.l + band * i + gap / 2 + (opts.stacked || series.length === 1 ? 0 : bw * si);
          const y1 = y(acc[i] + p.count), y0 = y(acc[i]);
          const r = svgEl('rect', { class: 'bar', x: x0, y: y1, width: Math.max(1, bw), height: Math.max(0, y0 - y1), fill: s.color, rx: 3 });
          svg.appendChild(r);
          if (opts.stacked) acc[i] += p.count;
        });
      });
    } else {
      series.forEach(s => {
        const d = s.points.map((p, i) => (i ? 'L' : 'M') + xs(i).toFixed(1) + ' ' + y(p.count).toFixed(1)).join(' ');
        if (series.length === 1) svg.appendChild(svgEl('path', { class: 'area', d: d + ` L${xs(n - 1)} ${y(0)} L${xs(0)} ${y(0)} Z`, fill: s.color }));
        svg.appendChild(svgEl('path', { class: 'line', d, stroke: s.color }));
        const last = s.points[n - 1];
        svg.appendChild(svgEl('circle', { class: 'marker', cx: xs(n - 1), cy: y(last.count), r: 4, stroke: s.color }));
        lastPos.push({ x: xs(n - 1), y: y(last.count), v: last.count, color: s.color });
      });
      lastPos.forEach(p => { const t = svgEl('text', { class: 'dlabel', x: p.x - 8, y: p.y - 8, 'text-anchor': 'end' }); t.textContent = fmtInt(p.v); svg.appendChild(t); });
    }

    // hover layer: crosshair + tooltip for the nearest bucket
    const cross = svgEl('line', { class: 'crosshair', y1: m.t, y2: m.t + ih, x1: 0, x2: 0, visibility: 'hidden' }); svg.appendChild(cross);
    const hit = svgEl('rect', { class: 'hit', x: m.l, y: m.t, width: iw, height: ih }); svg.appendChild(hit);
    hit.addEventListener('mousemove', ev => {
      const rect = svg.getBoundingClientRect(); const scale = W / rect.width;
      const px = (ev.clientX - rect.left) * scale;
      const i = Math.max(0, Math.min(n - 1, opts.kind === 'bar' ? Math.floor((px - m.l) / band) : Math.round((px - m.l) / (iw / Math.max(1, n - 1)))));
      const cx = opts.kind === 'bar' ? m.l + band * i + band / 2 : xs(i);
      cross.setAttribute('x1', cx); cross.setAttribute('x2', cx); cross.setAttribute('visibility', 'visible');
      showTip(ev.clientX, ev.clientY, opts.tipTitle(series[0].points[i], i), series.map(s => ({ label: s.label, color: s.color, value: s.points[i]?.count || 0 })));
    });
    hit.addEventListener('mouseleave', () => { cross.setAttribute('visibility', 'hidden'); hideTip(); });

    if (series.length > 1) {
      const lg = el('div', { class: 'legend' }); series.forEach(s => lg.appendChild(el('span', { text: s.label, style: '--c:' + s.color }))); host.appendChild(lg);
    }
  }
  function niceMax(v) { if (v <= 4) return 4; const p = Math.pow(10, Math.floor(Math.log10(v))); const f = v / p; const nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10; return nf * p; }

  function sparkline(host, points, color) {
    host.innerHTML = '';
    if (!points || !points.length) return;
    const W = 200, H = 34, n = points.length, max = Math.max(1, ...points.map(p => p.count));
    const svg = svgEl('svg', { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', 'aria-hidden': 'true' });
    const xs = i => (i / (n - 1)) * W, ys = v => H - 2 - (v / max) * (H - 6);
    const d = points.map((p, i) => (i ? 'L' : 'M') + xs(i).toFixed(1) + ' ' + ys(p.count).toFixed(1)).join(' ');
    svg.appendChild(svgEl('path', { d: d + ` L${W} ${H} L0 ${H} Z`, fill: color, opacity: .12 }));
    svg.appendChild(svgEl('path', { d, fill: 'none', stroke: color, 'stroke-width': 1.5, 'vector-effect': 'non-scaling-stroke' }));
    host.appendChild(svg);
  }

  function tableView(host, columns, rows) {
    host.innerHTML = '';
    const wrap = el('div', { class: 'tablewrap' });
    const table = el('table'); const thead = el('thead'); const tr = el('tr');
    columns.forEach((c, i) => tr.appendChild(el('th', { class: i ? 'num' : '', text: c }))); thead.appendChild(tr); table.appendChild(thead);
    const tb = el('tbody');
    rows.forEach(r => { const t = el('tr'); r.forEach((v, i) => t.appendChild(el('td', { class: i ? 'num' : '', text: i ? fmtInt(v) : v }))); tb.appendChild(t); });
    table.appendChild(tb); wrap.appendChild(table); host.appendChild(wrap);
  }

  // ── chart cards ────────────────────────────────────────────────────────────
  // Each card knows how to build its series from the overview snapshot (live-patched in memory).
  function chartCard(def) {
    const card = el('section', { class: 'card', 'aria-live': 'off' });
    const tools = el('div', { class: 'tools' });
    const h = el('h2', {}, [def.title, tools]);
    card.appendChild(h); card.appendChild(el('p', { class: 'desc', text: def.desc }));
    const host = el('div', { class: 'chart' }); card.appendChild(host);
    const state = { view: 'chart', range: def.ranges ? def.ranges[0].key : null, snap: null };
    if (def.ranges) def.ranges.forEach(r => { const b = el('button', { class: 'ghost', text: r.label, 'aria-pressed': String(r.key === state.range) }); b.onclick = () => { state.range = r.key; tools.querySelectorAll('button[data-range]').forEach(x => x.setAttribute('aria-pressed', String(x.dataset.range === r.key))); draw(); }; b.dataset.range = r.key; tools.appendChild(b); });
    const tb = el('button', { class: 'ghost', text: 'Tabela', 'aria-pressed': 'false' });
    tb.onclick = () => { state.view = state.view === 'chart' ? 'table' : 'chart'; tb.setAttribute('aria-pressed', String(state.view === 'table')); draw(); };
    tools.appendChild(tb);
    function draw() {
      if (!state.snap) return;
      const built = def.build(state.snap, state.range);
      if (state.view === 'table') tableView(host, [built.xHeader].concat(built.series.map(s => s.label)), built.series[0].points.map((p, i) => [built.xLabel(p, i)].concat(built.series.map(s => s.points[i]?.count || 0))));
      else renderChart(host, Object.assign({ xLabel: built.xLabel, tipTitle: built.tipTitle || built.xLabel, aria: def.title }, built));
    }
    return { node: card, update(snap) { state.snap = snap; draw(); }, redraw: draw };
  }

  const minuteX = p => hhmm(new Date(p.at));
  const hourX = p => pad(new Date(p.at).getHours()) + ':00';
  const dayX = p => dmy(new Date(p.at));
  const seriesOf = (snap, key, range) => ({ key, label: SERIES[key].label, color: SERIES[key].color, points: (range === 'minute' ? snap.lastHourByMinute : range === 'hour' ? snap.last24hByHour : snap.last30dByDay)[key] || [] });
  const RANGES = [{ key: 'minute', label: '60 min' }, { key: 'hour', label: '24 h' }, { key: 'day', label: '30 dni' }];
  const xFor = r => r === 'minute' ? minuteX : r === 'hour' ? hourX : dayX;
  const kindFor = r => r === 'minute' ? 'line' : 'bar';

  const CHARTS = [
    { title: 'Rezerwacje na żywo', desc: 'Przyrost rezerwacji tworzonych przez użytkowników — punkt na minutę, godzinę lub dzień.', ranges: RANGES,
      build: (s, r) => ({ kind: kindFor(r), series: [seriesOf(s, 'RESERVATION_CREATED', r)], xLabel: xFor(r), xHeader: 'Czas' }) },
    { title: 'O której klienci rezerwują', desc: 'Rozkład godzinowy rezerwacji z ostatnich 7 dni (godzina lokalna studia).',
      build: s => ({ kind: 'bar', series: [{ key: 'p', label: 'Rezerwacje', color: SERIES.RESERVATION_CREATED.color, points: (s.hourOfDayProfile7d.RESERVATION_CREATED || []).map((c, h) => ({ at: h, count: c })) }], xLabel: p => pad(p.at) + ':00', xHeader: 'Godzina' }) },
    { title: 'Wizyty: bezpośrednie vs z rezerwacji', desc: 'Lejek konwersji na żywo — czy pracownicy zamieniają rezerwacje w wizyty, czy zakładają je z palca.', ranges: RANGES,
      build: (s, r) => ({ kind: 'bar', stacked: true, series: [seriesOf(s, 'VISIT_CREATED:FROM_RESERVATION', r), seriesOf(s, 'VISIT_CREATED:DIRECT', r)], xLabel: xFor(r), xHeader: 'Czas' }) },
    { title: 'Zdjęcia i multimedia', desc: 'Udane uploady zdjęć w podziale na miejsce przypięcia.', ranges: RANGES,
      build: (s, r) => ({ kind: 'bar', stacked: true, series: ['PHOTO_UPLOADED:VISIT', 'PHOTO_UPLOADED:VEHICLE', 'PHOTO_UPLOADED:CHECKIN', 'PHOTO_UPLOADED:BATCH_ORDER'].map(k => seriesOf(s, k, r)), xLabel: xFor(r), xHeader: 'Czas' }) },
    { title: 'Katalog usług — nowości', desc: 'Kiedy studio rozszerza ofertę: nowe usługi i pakiety w cenniku.', ranges: [RANGES[2], RANGES[1]],
      build: (s, r) => ({ kind: 'bar', stacked: true, series: [seriesOf(s, 'SERVICE_CREATED:SERVICE', r), seriesOf(s, 'SERVICE_CREATED:PACKAGE', r)], xLabel: xFor(r), xHeader: 'Czas' }) },
    { title: 'Log aktywności', desc: 'Przyrost rekordów w historii aktywności — sam fakt powstania wpisu systemowego.', ranges: RANGES,
      build: (s, r) => ({ kind: kindFor(r), series: [seriesOf(s, 'ACTIVITY_LOGGED', r)], xLabel: xFor(r), xHeader: 'Czas' }) }
  ];

  // ── KPI tiles ──────────────────────────────────────────────────────────────
  function tile(key) {
    const s = SERIES[key];
    const node = el('section', { class: 'card tile' });
    node.appendChild(el('div', { class: 'label' }, [el('span', { class: 'swatch', style: 'background:' + s.color }), s.label]));
    const value = el('div', { class: 'value', text: '0' }); node.appendChild(value);
    const meta = el('div', { class: 'meta' }); node.appendChild(meta);
    const spark = el('div'); node.appendChild(spark);
    return { node, update(snap) {
      const st = snap.stats.find(x => x.series === key) || {};
      value.textContent = fmtInt(st.today);
      meta.innerHTML = '';
      meta.appendChild(el('span', {}, ['dziś · ', el('b', { text: fmtInt(st.lastHour) }), ' w ostatniej godz.']));
      meta.appendChild(el('span', {}, [el('b', { text: fmtInt(st.total) }), ' łącznie']));
      meta.appendChild(el('span', { text: 'ostatnio ' + rel(st.lastEventAt) }));
      sparkline(spark, snap.lastHourByMinute[key], s.color);
    }, pulse() { node.classList.remove('pulse'); void node.offsetWidth; node.classList.add('pulse'); } };
  }

  // ── live feed ──────────────────────────────────────────────────────────────
  function feed(showTenant, tenantName) {
    const node = el('ul', { class: 'feed', 'aria-live': 'polite' });
    const item = (e, isNew) => {
      const s = SERIES[e.series[e.series.length - 1]] || SERIES[e.type] || { label: e.type, color: 'var(--text-3)' };
      const ctx = Object.entries(e.attributes || {}).filter(([k]) => k !== 'userId').slice(0, 3).map(([k, v]) => (ATTR_LABELS[k] || k) + ': ' + (v.length > 40 ? v.slice(0, 8) + '…' : v)).join(' · ');
      const tenant = showTenant ? (tenantName(e.tenantId) || e.tenantId.slice(0, 8)) + ' · ' : '';
      return el('li', { class: isNew ? 'new' : '' }, [el('time', { text: hhmm(new Date(e.occurredAt)) }), el('span', { class: 'sw', style: 'background:' + s.color }), el('span', {}, [el('span', { class: 'what', text: tenant + s.label }), ' ', el('span', { class: 'ctx', text: ctx })])]);
    };
    return { node, reset(events) { node.innerHTML = ''; if (!events.length) node.appendChild(el('li', { class: 'empty', text: 'Jeszcze nic się nie wydarzyło' })); events.forEach(e => node.appendChild(item(e, false))); },
      push(e) { const empty = node.querySelector('.empty'); if (empty) empty.remove(); node.insertBefore(item(e, true), node.firstChild); while (node.children.length > 100) node.removeChild(node.lastChild); } };
  }

  // ── in-memory live patching of the snapshot ───────────────────────────────
  function applyLive(snap, e) {
    const at = new Date(e.occurredAt);
    for (const key of e.series) {
      const st = snap.stats.find(x => x.series === key); if (st) { st.today++; st.lastHour++; st.last15Minutes++; st.total++; st.lastEventAt = e.occurredAt; }
      bump(snap.lastHourByMinute, key, at, 60 * 1000, 60);
      bump(snap.last24hByHour, key, at, 3600 * 1000, 24);
      bump(snap.last30dByDay, key, at, null, 30);
      if (snap.hourOfDayProfile7d[key]) snap.hourOfDayProfile7d[key][at.getHours()]++;
    }
    snap.recentEvents.unshift(e); snap.recentEvents.length = Math.min(snap.recentEvents.length, 50);
  }
  function bump(map, key, at, bucketMs, keep) {
    const arr = map[key] = map[key] || [];
    const bucketAt = bucketMs ? new Date(Math.floor(at.getTime() / bucketMs) * bucketMs) : new Date(at.getFullYear(), at.getMonth(), at.getDate());
    let last = arr[arr.length - 1];
    const lastAt = last ? new Date(last.at) : null;
    if (last && lastAt.getTime() === bucketAt.getTime()) { last.count++; return; }
    if (last && lastAt.getTime() > bucketAt.getTime()) { const p = arr.find(x => new Date(x.at).getTime() === bucketAt.getTime()); if (p) p.count++; return; }
    // fill gaps up to the new bucket, then append
    if (last && bucketMs) { let t = lastAt.getTime() + bucketMs; while (t < bucketAt.getTime()) { arr.push({ at: new Date(t).toISOString(), count: 0 }); t += bucketMs; } }
    arr.push({ at: bucketAt.toISOString(), count: 1 });
    while (arr.length > keep) arr.shift();
  }

  // ── transport ──────────────────────────────────────────────────────────────
  function api(cfg) {
    const headers = () => cfg.key ? { 'X-Platform-Key': cfg.key } : {};
    return {
      async get(path) { const r = await fetch(cfg.base + path, { headers: headers(), credentials: 'same-origin' }); if (!r.ok) { const e = new Error('HTTP ' + r.status); e.status = r.status; throw e; } return r.json(); },
      // SSE over fetch so the platform key can travel in a header (EventSource cannot set headers).
      stream(path, onFrame, onState) {
        let stop = false, ctrl = null;
        (async function loop() {
          let delay = 1000;
          while (!stop) {
            try {
              ctrl = new AbortController();
              const r = await fetch(cfg.base + path, { headers: Object.assign({ Accept: 'text/event-stream' }, headers()), credentials: 'same-origin', signal: ctrl.signal });
              if (!r.ok) { onState('error', r.status); if (r.status === 401 || r.status === 403 || r.status === 503) return; throw new Error('HTTP ' + r.status); }
              onState('live'); delay = 1000;
              const reader = r.body.getReader(), dec = new TextDecoder(); let buf = '';
              while (!stop) {
                const { value, done } = await reader.read(); if (done) break;
                buf += dec.decode(value, { stream: true });
                let idx; while ((idx = buf.indexOf('\n\n')) >= 0) {
                  const chunk = buf.slice(0, idx); buf = buf.slice(idx + 2);
                  const data = chunk.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trim()).join('\n');
                  if (data) { try { onFrame(JSON.parse(data)); } catch (_) { /* ignore malformed frame */ } }
                }
              }
            } catch (e) { if (stop) return; }
            onState('reconnecting'); await new Promise(res => setTimeout(res, delay)); delay = Math.min(delay * 2, 15000);
          }
        })();
        return () => { stop = true; if (ctrl) ctrl.abort(); };
      }
    };
  }

  // ── app ────────────────────────────────────────────────────────────────────
  function boot(opts) {
    const mode = opts.mode;
    const cfg = { base: '', key: null };
    const $ = sel => document.querySelector(sel);
    const status = $('#status'), statusText = $('#status-text');
    const setState = (s, code) => { status.dataset.state = s; statusText.textContent = s === 'live' ? 'na żywo' : s === 'reconnecting' ? 'łączenie ponownie…' : s === 'error' ? ('błąd ' + (code || '')) : 'rozłączono'; };

    const tiles = BASE.map(tile); const kpis = $('#kpis'); tiles.forEach(t => kpis.appendChild(t.node));
    const cards = CHARTS.map(chartCard); const charts = $('#charts'); cards.forEach(c => charts.appendChild(c.node));
    const liveFeed = feed(mode === 'platform', id => tenantNames[id]); $('#feed').appendChild(liveFeed.node);

    let snap = null, tenantFilter = null, closeStream = null, tenantNames = {};
    const a = api(cfg);
    const paths = mode === 'platform'
      ? { overview: () => tenantFilter ? `/api/internal/live-metrics/tenants/${tenantFilter}/overview` : '/api/internal/live-metrics/overview', stream: '/api/internal/live-metrics/stream' }
      : { overview: () => '/api/v1/live-metrics/overview', stream: '/api/v1/live-metrics/stream' };

    function render() {
      if (!snap) return;
      tiles.forEach(t => t.update(snap)); cards.forEach(c => c.update(snap)); liveFeed.reset(snap.recentEvents || []);
      $('#generated').textContent = 'stan z ' + hhmm(new Date(snap.generatedAt)) + ' · strefa ' + snap.zone;
    }
    async function refresh() {
      try {
        const data = await a.get(paths.overview());
        if (mode === 'platform' && !tenantFilter) { snap = data.platform; renderPlatform(data); } else { snap = data; }
        render();
      } catch (e) { setState('error', e.status); if (e.status === 401 && mode === 'platform') { showGate('Nieprawidłowy klucz platformy.'); } if (e.status === 401 && mode === 'studio') { $('#gate-studio').classList.remove('hidden'); } }
    }
    function renderPlatform(data) {
      tenantNames = {}; data.tenants.forEach(t => { tenantNames[t.tenantId] = t.name || t.tenantId.slice(0, 8); });
      const sel = $('#tenant-select'); const cur = sel.value; sel.innerHTML = '';
      sel.appendChild(el('option', { value: '', text: 'Cała platforma (' + data.tenantsSeen + ' tenantów)' }));
      data.tenants.forEach(t => sel.appendChild(el('option', { value: t.tenantId, text: t.name || t.tenantId })));
      sel.value = cur;
      const rows = data.tenants.map(t => [t.name || t.tenantId, t.today.RESERVATION_CREATED, t.today.VISIT_CREATED, t.today.SERVICE_CREATED, t.today.PHOTO_UPLOADED, t.today.ACTIVITY_LOGGED, t.total, rel(t.lastEventAt)]);
      const host = $('#tenants'); host.innerHTML = '';
      if (!rows.length) host.appendChild(el('div', { class: 'empty', text: 'Żaden tenant nie wygenerował jeszcze zdarzenia' }));
      else { const wrap = el('div', { class: 'tablewrap' }); const table = el('table'); const tr = el('tr'); ['Tenant', 'Rezerwacje dziś', 'Wizyty dziś', 'Usługi dziś', 'Zdjęcia dziś', 'Aktywność dziś', 'Łącznie', 'Ostatnie zdarzenie'].forEach((c, i) => tr.appendChild(el('th', { class: i && i < 7 ? 'num' : '', text: c }))); table.appendChild(el('thead', {}, [tr])); const tb = el('tbody'); rows.forEach(r => { const t = el('tr'); r.forEach((v, i) => t.appendChild(el('td', { class: i && i < 7 ? 'num' : '', text: i && i < 7 ? fmtInt(v) : v }))); tb.appendChild(t); }); table.appendChild(tb); wrap.appendChild(table); host.appendChild(wrap); }
      const p = data.pipeline; const ph = $('#pipeline'); ph.innerHTML = '';
      [['w kolejce', p.queued + ' / ' + p.queueCapacity], ['przyjęte', fmtInt(p.accepted)], ['zapisane', fmtInt(p.written)], ['odrzucone', fmtInt(p.dropped), p.dropped > 0], ['nieudane partie', fmtInt(p.failedBatches), p.failedBatches > 0], ['rozgłoszone', fmtInt(p.broadcast)], ['subskrybenci SSE', fmtInt(p.sseSubscribers)]]
        .forEach(([l, v, bad]) => ph.appendChild(el('div', { class: bad ? 'bad' : '' }, [el('b', { text: v }), l])));
    }
    function onFrame(f) {
      if (f.kind !== 'BUSINESS_EVENT' || !f.event || !snap) return;
      const e = f.event;
      if (tenantFilter && e.tenantId !== tenantFilter) return;
      applyLive(snap, e);
      liveFeed.push(Object.assign({}, e));
      tiles.forEach((t, i) => { if (e.series.includes(BASE[i])) { t.update(snap); t.pulse(); } });
      cards.forEach(c => c.update(snap));
    }
    function connect() { if (closeStream) closeStream(); closeStream = a.stream(paths.stream, onFrame, setState); }
    function start() { $('#gate').classList.add('hidden'); $('#app').classList.remove('hidden'); refresh(); connect(); setInterval(refresh, 60000); }
    function showGate(msg) { $('#app').classList.add('hidden'); $('#gate').classList.remove('hidden'); $('#gate-error').textContent = msg || ''; }

    if (mode === 'platform') {
      try { cfg.key = sessionStorage.getItem('lm.platformKey'); } catch (_) { }
      $('#gate-form').addEventListener('submit', ev => { ev.preventDefault(); cfg.key = $('#gate-key').value.trim(); try { sessionStorage.setItem('lm.platformKey', cfg.key); } catch (_) { } start(); });
      $('#tenant-select').addEventListener('change', ev => { tenantFilter = ev.target.value || null; refresh(); });
      $('#logout').addEventListener('click', () => { try { sessionStorage.removeItem('lm.platformKey'); } catch (_) { } location.reload(); });
      if (cfg.key) start(); else showGate('');
    } else {
      start();
    }
    window.addEventListener('resize', () => cards.forEach(c => c.redraw()));
  }

  window.LiveMetrics = { boot };
})();
