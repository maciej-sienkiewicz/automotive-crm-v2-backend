FROM openjdk:22-jdk-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates wget gnupg locales \
    libglib2.0-0 libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 \
    libcups2 libdrm2 libdbus-1-3 libexpat1 libxcb1 libxkbcommon0 \
    libatspi2.0-0 libx11-6 libxcomposite1 libxdamage1 libxext6 \
    libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 \
    libasound2 libgdk-pixbuf-2.0-0 libgtk-3-0 fonts-liberation \
    fonts-dejavu fontconfig \
  && rm -rf /var/lib/apt/lists/*

COPY /build/libs/*SNAPSHOT.jar /detailing-crm-backed.jar

ENTRYPOINT ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005","-Dspring.profiles.active=docker-props", "-jar", "detailing-crm-backed.jar"]