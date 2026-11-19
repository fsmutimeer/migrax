# Migrax container image: the CLI with the common JDBC drivers, for running migrations as their
# own deployment step (Kubernetes Job, init container, CI). Migrations go in /migrations.
#
# Build after 'mvn package':  docker build --build-arg VERSION=<version> -t migrax .
# Run:  docker run --rm -v "$PWD/db/migration:/migrations" \
#         -e MIGRAX_DATABASE_URL=jdbc:postgresql://db:5432/app ... migrax migrate

# Base images are pinned to a digest, so every build starts from the same image; Dependabot
# updates the digests.

# The drivers are plain jars: download them once, on the build machine's own platform.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS drivers
COPY docker/drivers/pom.xml /build/pom.xml
RUN mvn -q -B -f /build/pom.xml dependency:copy-dependencies \
      -DoutputDirectory=/build/drivers -DincludeScope=runtime

FROM eclipse-temurin:21-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6 AS base

# Java 21.0.13 in the base image has CVE-2026-114938 on x86-64 (fixed in 21.0.13.1) and the
# base image is not rebuilt yet, so amd64 gets the fixed JRE from Adoptium, pinned by checksum.
# The version check in the RUN below fails the build when the base image has another Java
# version: then remove the jre-amd64 and runtime-* stages and the TARGETARCH selection.
FROM scratch AS jre-amd64
ADD --checksum=sha256:18f44fab9065217f3ba61d0eef8a7b5f3c3713aa7293001b0d4ac10ede3850f0 \
    https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jre_x64_linux_hotspot_21.0.13.1_2.tar.gz \
    /jre.tar.gz

FROM base AS runtime-amd64
RUN --mount=type=bind,from=jre-amd64,source=/jre.tar.gz,target=/tmp/jre.tar.gz \
    { grep -qx 'JAVA_RUNTIME_VERSION="21.0.13+9-LTS"' /opt/java/openjdk/release \
      || { echo "The base image has another Java version: remove the jre-amd64 and runtime-* stages." >&2; exit 1; }; } \
    && rm -rf /opt/java/openjdk \
    && mkdir -p /opt/java/openjdk \
    && tar -xzf /tmp/jre.tar.gz -C /opt/java/openjdk --strip-components=1 --no-same-owner \
    && grep -qx 'JAVA_RUNTIME_VERSION="21.0.13.1+2-LTS"' /opt/java/openjdk/release \
    && java -version
ENV JAVA_VERSION=jdk-21.0.13.1+2

# arm64 is not affected and keeps the base image's Java.
FROM base AS runtime-arm64

FROM runtime-${TARGETARCH}
ARG VERSION
LABEL org.opencontainers.image.title="Migrax" \
      org.opencontainers.image.description="Migrations for JPA and Hibernate projects" \
      org.opencontainers.image.source="https://github.com/fsmutimeer/migrax" \
      org.opencontainers.image.url="https://migrax.org/" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

COPY bin/migrax /opt/migrax/bin/migrax
COPY target/migrax-${VERSION}.jar /opt/migrax/lib/migrax.jar
COPY --from=drivers /build/drivers/ /opt/migrax/drivers/
# pebble: a service manager in the base image that Migrax never runs (scanners report it).
RUN chmod 755 /opt/migrax/bin/migrax \
    && rm -f /usr/bin/pebble \
    && groupadd --system migrax \
    && useradd --system --gid migrax --home-dir /workspace --create-home migrax \
    && mkdir -p /migrations

# No project build in the container; migrations come from /migrations unless --locations says
# otherwise. Drivers in /opt/migrax/drivers are found next to the installation.
ENV PATH="/opt/migrax/bin:${PATH}" \
    MIGRAX_NO_BUILD=true \
    MIGRAX_LOCATIONS=filesystem:/migrations
WORKDIR /workspace
USER migrax
ENTRYPOINT ["migrax"]
CMD ["help"]
