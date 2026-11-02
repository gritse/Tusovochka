# The native media libraries are built from source (see docker/native) on the same Ubuntu release as the runtime
# image, so that they link against the same glibc. The three build stages run in parallel.

FROM ubuntu:26.04@sha256:6a06111c3b0a8600feadb270aec771dc239bf407b25625f93f44ab00e11f4d3c AS toolchain

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
	build-essential autoconf automake bzip2 cmake gettext gperf meson nasm ninja-build patch pkgconf python3 python3-packaging \
	curl ca-certificates libarchive-tools gnupg gpgv zlib1g-dev \
	&& rm -rf /var/lib/apt/lists/*
COPY docker/native/common.sh /build/

# Rebuilt only when something in docker/native/libvips changes: the layer cache is keyed on this COPY
FROM toolchain AS libvips
COPY docker/native/libvips /build/libvips
RUN /build/libvips/build.sh /opt/libvips

FROM toolchain AS ffmpeg
COPY docker/native/ffmpeg /build/ffmpeg
RUN /build/ffmpeg/build.sh /opt/ffmpeg

FROM maven:3.10.0-eclipse-temurin-21@sha256:d49bca545509b98965a808175862337dfdd24e1a8a4b11005cc8ce0578e52e8c AS java

WORKDIR /usr/src/app
COPY . .
ARG MAVEN_OPTS
RUN --mount=type=cache,target=/root/.m2 mvn package -Dmaven.test.skip=true -DnodeInstallLocation=/root/.m2

FROM eclipse-temurin:21-jre-resolute@sha256:f8622d0220c9e218d7a33f24a7b9e1ad6bc726085f8b0ae74e1adf85f67f05c4

SHELL ["bash", "-c"]
WORKDIR /opt/smithereen
COPY --from=java /usr/src/app/target/smithereen.jar /opt/smithereen/smithereen.jar
COPY --from=java /usr/src/app/target/lib /opt/smithereen/lib
COPY --from=libvips /opt/libvips/libvips.so.42 /opt/smithereen/
RUN ln -s libvips.so.42 /opt/smithereen/libvips.so
# Without ffmpeg the server still runs, but refuses video and audio uploads
COPY --from=ffmpeg /opt/ffmpeg/bin/ffmpeg /opt/ffmpeg/bin/ffprobe /usr/local/bin/
RUN echo -e '#!/bin/bash\njava -jar /opt/smithereen/smithereen.jar /usr/local/etc/config.properties init_admin' > smithereen-init-admin && chmod +x smithereen-init-admin

EXPOSE 4567
ENTRYPOINT java -Djna.library.path=/opt/smithereen --module-path /opt/smithereen/smithereen.jar:/opt/smithereen/lib -m smithereen.server/smithereen.SmithereenApplication /usr/local/etc/config.properties
