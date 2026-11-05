# Migrax container image: the CLI with the common JDBC drivers, for running migrations as their
# own deployment step (Kubernetes Job, init container, CI). Migrations go in /migrations.
#
# Build after 'mvn package':  docker build --build-arg VERSION=<version> -t migrax .
# Run:  docker run --rm -v "$PWD/db/migration:/migrations" \
#         -e MIGRAX_DATABASE_URL=jdbc:postgresql://db:5432/app ... migrax migrate

# Base images are pinned to a digest, so every build starts from the same image; Dependabot
# updates the digests. The one apt step below takes the current security update of two packages
# at build time.

# The drivers are plain jars: download them once, on the build machine's own platform.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS drivers
COPY docker/drivers/pom.xml /build/pom.xml
RUN mvn -q -B -f /build/pom.xml dependency:copy-dependencies \
      -DoutputDirectory=/build/drivers -DincludeScope=runtime

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
# apt in the base image has CVE-2026-109518 (fixed in 3.2.1ubuntu0.1) and the base image is not
# rebuilt yet. Remove this step once the pinned base image has the fix.
RUN apt-get -o Acquire::Retries=3 update -qq \
    && apt-get -o Acquire::Retries=3 install -y -qq --no-install-recommends --only-upgrade \
         apt libapt-pkg7.0 \
    && rm -rf /var/lib/apt/lists/*

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
