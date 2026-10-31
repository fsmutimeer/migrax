# Migrax container image: the CLI with the common JDBC drivers, for running migrations as their
# own deployment step (Kubernetes Job, init container, CI). Migrations go in /migrations.
#
# Build after 'mvn package':  docker build --build-arg VERSION=<version> -t migrax .
# Run:  docker run --rm -v "$PWD/db/migration:/migrations" \
#         -e MIGRAX_DATABASE_URL=jdbc:postgresql://db:5432/app ... migrax migrate

# Base images are pinned to a digest, so every build ships the same thing; Dependabot updates
# the digests.

# The drivers are plain jars: download them once, on the build machine's own platform.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS drivers
COPY docker/drivers/pom.xml /build/pom.xml
RUN mvn -q -B -f /build/pom.xml dependency:copy-dependencies \
      -DoutputDirectory=/build/drivers -DincludeScope=runtime

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
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
