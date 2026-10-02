# Multi-stage build: Leiningen build -> minimal JRE runtime

FROM clojure:temurin-21-lein-2.11.2-alpine AS builder

WORKDIR /build

# Cache app deps. gm-lib comes from Clojars as ai.intergraph/game-master —
# this repo used to carry a forked copy and `lein install` it here, which
# shadowed the published artifact and let the two drift apart. The canonical
# source now lives in the-js-game and is published to Clojars.
COPY project.clj ./
# gm-lib is a SNAPSHOT, so this layer must not be reused across builds. Docker
# caches on project.clj's content, which does not change when a new snapshot is
# published — so without busting it the build silently keeps an old jar, and
# lein's own snapshot policy is :daily even when the layer does re-run. -U
# forces lein to check Clojars rather than trust its local cache.
#
# CD passes the commit sha for pushes, and github.run_id for manual runs. The
# run_id part is load-bearing: gm-lib is published from ANOTHER repo, so a new
# snapshot often arrives with no commit here, and a sha-keyed layer would be
# reused and ship the old jar.
ARG DEPS_REFRESH=0
RUN lein -U deps

COPY src/ src/
# resources/ carries auth.json, which the identity routes read off the
# classpath. Without it the image builds fine and /auth.json 404s at runtime.
COPY resources/ resources/

RUN lein uberjar

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S gm && adduser -S gm -G gm

WORKDIR /app

COPY --from=builder /build/target/uberjar/coselling-ai-standalone.jar app.jar

RUN chown -R gm:gm /app

USER gm

EXPOSE 8888

ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:${GM_PORT:-8888}/v1/gm/handle >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
