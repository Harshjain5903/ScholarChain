# syntax=docker/dockerfile:1
#
# ScholarChain batch-runner image (owned by build-engineer).
#
# Runtime for the Hadoop and Spark job jars OUTSIDE EMR (for example ECS
# Fargate). Contains: bash, JRE 17, AWS CLI v2, Hadoop 3.3.6 (with hadoop-aws
# and the matching aws-java-sdk-bundle in share/hadoop/tools/lib) and Spark
# 3.5.3 for Scala 2.13 (with the hadoop-aws/aws-java-sdk-bundle pair that
# matches Spark's bundled Hadoop client 3.3.4, in $SPARK_HOME/jars).
#
# Versions mirror project/Dependencies.scala (V.hadoop, V.spark, V.sparkHadoop);
# change them together. Every download is verified against a pinned checksum:
#   - Hadoop / Spark: SHA-512 published by Apache (archive.apache.org *.sha512).
#   - hadoop-aws / aws-java-sdk-bundle: SHA-256 of the Maven Central artifacts
#     (cross-checked against Central's published *.sha1).
#   - AWS CLI: SHA-256 of a zip whose PGP signature was verified against the
#     AWS CLI Team key (fingerprint FB5D B77F D5C1 18B8 0511 ADA8 A631 0ACC 4672 475C).
#
# linux/amd64 only (Fargate X86_64; the AWS CLI zip is architecture specific).
#
# The image carries no ScholarChain jars: the task fetches them from the
# artifacts bucket (aws s3 cp) or a derived image adds them. Example commands:
#   hadoop jar scholarchain-hadoop.jar <job> -D ... s3a://in s3a://out
#   spark-submit --master 'local[*]' --class edu.scholarchain.spark.ScholarChainPipeline \
#     scholarchain-spark.jar --hadoop-output s3a://... --match-decisions s3a://... --output s3a://...
#
# Build (not run by the build-engineer; see `docker build --check`):
#   docker build --platform linux/amd64 -t scholarchain-batch-runner docker/batch-runner

ARG BASE_IMAGE=eclipse-temurin:17.0.13_11-jre-jammy@sha256:7b8b90b35214757840f79fc78794bf4b06cb6ac92a8853429de16366e837c302

# ---------------------------------------------------------------------------
# fetch: download and verify everything; nothing unverified reaches the runtime
# ---------------------------------------------------------------------------
# BASE_IMAGE is pinned by tag and digest above (DL3006 cannot see through ARG);
# the platform is pinned on purpose (amd64-only AWS CLI zip).
# hadolint ignore=DL3006,DL3029
FROM --platform=linux/amd64 ${BASE_IMAGE} AS fetch

ARG HADOOP_VERSION=3.3.6
ARG HADOOP_SHA512=de3eaca2e0517e4b569a88b63c89fae19cb8ac6c01ff990f1ff8f0cc0f3128c8e8a23db01577ca562a0e0bb1b4a3889f8c74384e609cd55e537aada3dcaa9f8a
# Shipped inside the Hadoop 3.3.6 binary tarball (share/hadoop/tools/lib); verified after unpacking.
ARG HADOOP_AWS_SHA256=fba9eb73e6f0f5458355627fe095f5124705d4048551f4d6aa4084777b824c13
ARG HADOOP_SDK_BUNDLE_VERSION=1.12.367
ARG HADOOP_SDK_BUNDLE_SHA256=5e51f896c86c922c254c0184d91a1957929d12c3f1d6fe17aae65909230bc125

ARG SPARK_VERSION=3.5.3
ARG SPARK_SHA512=e56fa7eaced09c2fcb7fa710828fc8e2366a4559fa0a79ecbcbed253806c69aa6fb53d8a4bfcbceecefb2c5fd4da29d82f8f17fb85a4d249aa6342c3fcee9075
# Hadoop client version bundled with Spark 3.5.3; hadoop-aws must match it.
ARG SPARK_HADOOP_VERSION=3.3.4
ARG SPARK_HADOOP_AWS_SHA256=53f9ae03c681a30a50aa17524bd9790ab596b28481858e54efd989a826ed3a4a
ARG SPARK_SDK_BUNDLE_VERSION=1.12.262
ARG SPARK_SDK_BUNDLE_SHA256=873fe7cf495126619997bec21c44de5d992544aea7e632fdc77adb1a0915bae5

ARG AWSCLI_VERSION=2.22.35
ARG AWSCLI_SHA256=8119ccf67de875f39d386abea986738fa710be57e20d4df66fa99c7f7fd09997

ARG APACHE_ARCHIVE=https://archive.apache.org/dist
ARG MAVEN_CENTRAL=https://repo1.maven.org/maven2

SHELL ["/bin/bash", "-euo", "pipefail", "-c"]

# Throwaway stage: download tools come from the digest-pinned base image's apt
# sources; exact apt versions are not pinned because Ubuntu removes superseded
# versions. Nothing from this stage except the verified files is kept.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /fetch

# Hadoop 3.3.6 (binary tarball already contains hadoop-aws and the SDK bundle).
RUN curl -fsSL -o hadoop.tgz "${APACHE_ARCHIVE}/hadoop/common/hadoop-${HADOOP_VERSION}/hadoop-${HADOOP_VERSION}.tar.gz" \
 && echo "${HADOOP_SHA512}  hadoop.tgz" | sha512sum -c - \
 && mkdir -p /opt/hadoop \
 && tar -xzf hadoop.tgz -C /opt/hadoop --strip-components=1 \
 && rm hadoop.tgz \
 && rm -rf /opt/hadoop/share/doc /opt/hadoop/share/hadoop/*/sources /opt/hadoop/share/hadoop/*/jdiff

WORKDIR /opt/hadoop/share/hadoop/tools/lib
RUN echo "${HADOOP_AWS_SHA256}  hadoop-aws-${HADOOP_VERSION}.jar" | sha256sum -c - \
 && echo "${HADOOP_SDK_BUNDLE_SHA256}  aws-java-sdk-bundle-${HADOOP_SDK_BUNDLE_VERSION}.jar" | sha256sum -c -

WORKDIR /fetch

# Spark 3.5.3 for Scala 2.13 (the job jar is Scala 3 and uses the _2.13 Spark artifacts).
RUN curl -fsSL -o spark.tgz "${APACHE_ARCHIVE}/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop3-scala2.13.tgz" \
 && echo "${SPARK_SHA512}  spark.tgz" | sha512sum -c - \
 && mkdir -p /opt/spark \
 && tar -xzf spark.tgz -C /opt/spark --strip-components=1 \
 && rm spark.tgz \
 && rm -rf /opt/spark/examples /opt/spark/data /opt/spark/R /opt/spark/python \
 && test -f "/opt/spark/jars/hadoop-client-api-${SPARK_HADOOP_VERSION}.jar"

# hadoop-aws + SDK bundle for Spark's Hadoop client (s3a:// from spark-submit).
WORKDIR /opt/spark/jars
RUN curl -fsSL -o "hadoop-aws-${SPARK_HADOOP_VERSION}.jar" \
      "${MAVEN_CENTRAL}/org/apache/hadoop/hadoop-aws/${SPARK_HADOOP_VERSION}/hadoop-aws-${SPARK_HADOOP_VERSION}.jar" \
 && echo "${SPARK_HADOOP_AWS_SHA256}  hadoop-aws-${SPARK_HADOOP_VERSION}.jar" | sha256sum -c - \
 && curl -fsSL -o "aws-java-sdk-bundle-${SPARK_SDK_BUNDLE_VERSION}.jar" \
      "${MAVEN_CENTRAL}/com/amazonaws/aws-java-sdk-bundle/${SPARK_SDK_BUNDLE_VERSION}/aws-java-sdk-bundle-${SPARK_SDK_BUNDLE_VERSION}.jar" \
 && echo "${SPARK_SDK_BUNDLE_SHA256}  aws-java-sdk-bundle-${SPARK_SDK_BUNDLE_VERSION}.jar" | sha256sum -c -

WORKDIR /fetch
# AWS CLI v2 (installed into /opt/aws-cli, symlinked into /opt/aws-cli-bin).
RUN curl -fsSL -o awscli.zip "https://awscli.amazonaws.com/awscli-exe-linux-x86_64-${AWSCLI_VERSION}.zip" \
 && echo "${AWSCLI_SHA256}  awscli.zip" | sha256sum -c - \
 && unzip -q awscli.zip \
 && ./aws/install --install-dir /opt/aws-cli --bin-dir /opt/aws-cli-bin \
 && rm -rf aws awscli.zip

# ---------------------------------------------------------------------------
# runtime
# ---------------------------------------------------------------------------
# hadolint ignore=DL3006,DL3029
FROM --platform=linux/amd64 ${BASE_IMAGE} AS runtime

SHELL ["/bin/bash", "-euo", "pipefail", "-c"]

# bash and ca-certificates come from the digest-pinned base image; no apt installs.
RUN bash --version | head -1 \
 && groupadd --system --gid 10001 batch \
 && useradd --system --uid 10001 --gid batch --create-home --home-dir /home/batch batch \
 && mkdir -p /work \
 && chown batch:batch /work

COPY --from=fetch /opt/hadoop /opt/hadoop
COPY --from=fetch /opt/spark /opt/spark
COPY --from=fetch /opt/aws-cli /opt/aws-cli
COPY --from=fetch /opt/aws-cli-bin /opt/aws-cli-bin

ENV HADOOP_HOME=/opt/hadoop \
    HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop \
    HADOOP_OPTIONAL_TOOLS=hadoop-aws \
    SPARK_HOME=/opt/spark \
    PATH=/opt/aws-cli-bin:/opt/spark/bin:/opt/hadoop/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Build-time smoke checks: tools start and s3a resolves on both classpaths.
RUN bash --version | head -1 \
 && java -version \
 && aws --version \
 && hadoop version | head -1 \
 && hadoop classpath | tr ':' '\n' | grep -q 'share/hadoop/tools/lib' \
 && ls /opt/spark/jars/hadoop-aws-*.jar /opt/spark/jars/aws-java-sdk-bundle-*.jar \
 && spark-submit --version 2>&1 | grep -q 'version 3.5.3'

USER 10001:10001
WORKDIR /work
ENTRYPOINT ["/bin/bash", "-c"]
CMD ["java -version && aws --version && hadoop version && spark-submit --version"]
