# ---- build stage -------------------------------------------------------------------------
FROM gradle:8-jdk21 AS build
WORKDIR /src

# Copy the build definition first so dependency resolution is cached independently of sources.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies --quiet || true

COPY src ./src
RUN gradle --no-daemon installDist --quiet

# ---- runtime stage -----------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S ptt && adduser -S -G ptt ptt
WORKDIR /app
COPY --from=build /src/build/install/ /app/

USER ptt

ENV PTT_HOST=0.0.0.0 \
    PTT_PORT=8000 \
    PTT_MAX_AUDIO_FRAME_BYTES=8192 \
    PTT_PING_SECONDS=15 \
    PTT_MAX_CHANNEL=99

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PTT_PORT}/health" || exit 1

ENTRYPOINT ["/bin/sh", "-c", "exec /app/PTTdroidServer/bin/PTTdroidServer"]
