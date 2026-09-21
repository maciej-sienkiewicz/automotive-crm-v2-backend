# ── Etap 1: rozpakowanie bootJara na warstwy ────────────────────────────────
#
# Fat jar to ~66 MB, z czego >95% to zależności, które między commitami się nie
# zmieniają. Skopiowany jako jeden plik unieważnia całą warstwę przy każdej
# zmianie w kodzie: 66 MB do registry, 66 MB przy `compose pull`, 66 MB
# osieroconej warstwy na dysku hosta — za każdym buildem.
#
# Spring Boot zapisuje w jarze layers.idx; `layertools extract` dzieli go na
# katalogi ułożone od najrzadziej do najczęściej zmienianych. Po zmianie w kodzie
# przebudowuje się i wędruje przez sieć tylko ostatnia warstwa (kilka MB).
FROM eclipse-temurin:21-jre-jammy AS layers
WORKDIR /layers
COPY build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ── Etap 2: obraz uruchomieniowy ────────────────────────────────────────────
#
# JRE zamiast pełnego JDK, LTS zamiast porzuconego `openjdk:22` (obrazy `openjdk`
# na Docker Hubie są wygaszone i nie dostają poprawek bezpieczeństwa).
FROM eclipse-temurin:21-jre-jammy

# Fonty + fontconfig są potrzebne naprawdę: PDFBox, Batik i AWT (protokoły,
# faktury, logo) renderują tekst po stronie serwera. Bibliotek Chromium
# (libnss3, libgtk-3, libgbm…) już tu nie ma — przeglądarka żyje w sidecarze
# meta-ig-resolver, backend niczego z nich nie ładował.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      fontconfig fonts-dejavu fonts-liberation \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --shell /usr/sbin/nologin spring

USER spring
WORKDIR /app

# Kolejność = częstotliwość zmian. Trzy pierwsze warstwy trafiają z cache
# praktycznie zawsze; `application` to jedyna, która zmienia się co commit.
COPY --from=layers /layers/dependencies/ ./
COPY --from=layers /layers/spring-boot-loader/ ./
COPY --from=layers /layers/snapshot-dependencies/ ./
COPY --from=layers /layers/application/ ./

EXPOSE 8080

ENTRYPOINT ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-Dspring.profiles.active=docker-props", "org.springframework.boot.loader.launch.JarLauncher"]
