FROM eclipse-temurin:21-jdk-jammy

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_HOME=/opt/gradle/gradle-9.3.1
ENV PATH="${PATH}:${GRADLE_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/build-tools/36.0.0"

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl unzip git ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && node -v

# Install Gradle 9.3.1 & Android Command Line Tools + SDK 36
RUN curl -fsSL https://services.gradle.org/distributions/gradle-9.3.1-bin.zip -o /tmp/gradle.zip \
    && mkdir -p /opt/gradle \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm /tmp/gradle.zip \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline.zip \
    && mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && unzip -q /tmp/cmdline.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm /tmp/cmdline.zip \
    && yes | sdkmanager --licenses \
    && sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

WORKDIR /app
COPY package.json ./
COPY src ./src
COPY .env.example ./.env.example

ENV PORT=8080
EXPOSE 8080
CMD ["node", "src/server.mjs"]
