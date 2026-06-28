# Adds the NDK + CMake + Gradle that tinydj's native C++/Oboe engine needs, on top
# of an existing Android SDK base image (JDK 17+, cmdline-tools, platform 35,
# build-tools 35 at $ANDROID_HOME).
#
# BASE defaults to `tinydj-base:latest`. Point it at whatever Android builder image
# you have, e.g.:
#   docker tag <your-android-sdk-image> tinydj-base:latest
#   docker build -t tinydj-builder docker/
# or directly:
#   docker build --build-arg BASE=<your-android-sdk-image> -t tinydj-builder docker/
ARG BASE=tinydj-base:latest
FROM ${BASE}

# Accept SDK licenses, then install the exact NDK + CMake the build pins.
RUN yes | sdkmanager --licenses >/dev/null 2>&1 || true
RUN sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"

# Gradle (the repo has no wrapper jar). Extract with `jar` (always present) and
# restore the launcher's exec bit, which jar extraction drops.
RUN cd /opt \
    && curl -sSL -o gradle.zip https://services.gradle.org/distributions/gradle-8.10.2-bin.zip \
    && jar xf gradle.zip \
    && rm gradle.zip \
    && chmod +x /opt/gradle-8.10.2/bin/gradle

ENV PATH="/opt/gradle-8.10.2/bin:${PATH}"
ENV GRADLE_USER_HOME=/root/.gradle
WORKDIR /app
