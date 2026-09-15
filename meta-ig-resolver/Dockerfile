# Własny obraz zamiast mcr.microsoft.com/playwright (~2 GB).
#
# Tamten niesie trzy przeglądarki, kompilatory i narzędzia testowe, których
# tu nie używamy. Bierzemy `playwright-core` (sam sterownik, bez pobierania
# przeglądarek) i systemowe Chromium z Debiana — wychodzi ~700 MB.
#
# Ma to znaczenie nie tylko estetyczne: ten kontener ląduje na maszynie, która
# hostuje także produkcyjną bazę, a nadmiarowe gigabajty na współdzielonym
# dysku to nie jest abstrakcyjny koszt.
FROM node:22-slim

ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    CHROMIUM_PATH=/usr/bin/chromium \
    NODE_ENV=production

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      chromium \
      fonts-liberation \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY server.js resolver.js ./

# Chromium nie działa jako root bez --no-sandbox; wolimy zwykłego użytkownika
# niż rozbrajanie piaskownicy.
RUN useradd --create-home --shell /bin/false resolver \
 && chown -R resolver:resolver /app
USER resolver

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
