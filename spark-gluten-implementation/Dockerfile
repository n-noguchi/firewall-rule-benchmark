# syntax=docker/dockerfile:1.6

# Spark 3.5 + Apache Gluten 1.2.0 (Velox backend, prebuilt static bundle).
#
# The build stage downloads the official Spark distribution and the official
# Gluten+Velox binary tarball, drops every Gluten/third-party JAR into
# $SPARK_HOME/jars alongside the PostgreSQL JDBC driver and our application
# jar. The runtime image is a slim JRE that boots the local-mode Spark driver
# in a single JVM with the Gluten plugin active.
FROM eclipse-temurin:17-jdk-jammy AS build

ARG SPARK_VERSION=3.5.5
ARG GLUTEN_VERSION=1.5.0
ARG POSTGRES_JDBC_VERSION=42.7.4
ARG BOOST_VERSION=1.84.0
ARG BOOST_TARBALL=boost_1_84_0.tar.bz2
ARG BOOST_DIR=boost_1_84_0
ARG GLUTEN_DIST_BASE=https://downloads.apache.org/gluten/gluten

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl ca-certificates maven tar gzip bzip2 xz-utils unzip \
        build-essential g++ \
        libdouble-conversion3 libicu70 libre2-9 \
        libsnappy1v5 liblz4-1 libzstd1 libbz2-1.0 libxml2 \
        libevent-2.1-7 libnghttp2-14 librtmp1 libssh-4 libsasl2-2 \
        libldap-2.5-0 libcurl4 libgflags2.2 libunwind8 \
        libidn12 libntlm0 libgsasl7 libthrift-0.16.0 \
    && rm -rf /var/lib/apt/lists/*

# --- Build Boost 1.84.0 from source -------------------------------------------
# Gluten's prebuilt Velox binary expects SONAME libboost_*.so.1.84.0 which
# Ubuntu 22.04 doesn't ship (its repos max out at 1.74). Build the subset
# Gluten/Velox needs from the Boost 1.84 source tarball.
WORKDIR /tmp/boost
RUN curl -fsSL "https://archives.boost.org/release/${BOOST_VERSION}/source/${BOOST_TARBALL}" \
        -o /tmp/boost/boost.tar.bz2 \
    && tar -xjf /tmp/boost/boost.tar.bz2 -C /tmp/boost \
    && cd "${BOOST_DIR}" \
    && ./bootstrap.sh --with-libraries=context,filesystem,system,thread,regex,date_time,chrono,atomic,serialization \
    && ./b2 -j"$(nproc)" --layout=system install \
    && ldconfig \
    && rm -rf /tmp/boost

# --- Fetch Spark distribution -------------------------------------------------
WORKDIR /opt
RUN curl -fsSL "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop3.tgz" -o /tmp/spark.tgz \
    && tar -xzf /tmp/spark.tgz -C /opt \
    && mv "/opt/spark-${SPARK_VERSION}-bin-hadoop3" /opt/spark \
    && rm /tmp/spark.tgz
ENV SPARK_HOME=/opt/spark

# --- Fetch Gluten+Velox binary distribution ----------------------------------
# The prebuilt tarball bundles statically-linked libvelox.so / libgluten.so.
# Extract the native libs to /usr/local/lib so we can run with
# spark.gluten.loadLibFromJar=false (Gluten's in-JAR loader expects every
# transitive .so to also live in a JAR, which is awkward on Ubuntu 22.04).
WORKDIR /tmp/gluten
RUN curl -fsSL "${GLUTEN_DIST_BASE}/${GLUTEN_VERSION}-incubating/apache-gluten-${GLUTEN_VERSION}-incubating-bin-spark-3.5.tar.gz" \
        -o /tmp/gluten/gluten.tgz \
    && tar -xzf /tmp/gluten/gluten.tgz -C /tmp/gluten \
    && find /tmp/gluten -name '*.jar' -exec cp {} "${SPARK_HOME}/jars/" \; \
    && mkdir -p /usr/local/lib/gluten \
    && cd "${SPARK_HOME}/jars" \
    && for j in gluten-velox-bundle-*.jar; do \
        unzip -o "$j" 'linux/*' -d /tmp/gluten-unzip > /dev/null 2>&1 || true; \
        unzip -o "$j" '*.so' -d /tmp/gluten-unzip > /dev/null 2>&1 || true; \
       done \
    && find /tmp/gluten-unzip -name '*.so*' -exec cp -L {} /usr/local/lib/gluten/ \; \
    && rm -rf /tmp/gluten /tmp/gluten-unzip

# --- PostgreSQL JDBC driver ---------------------------------------------------
RUN curl -fsSL "https://jdbc.postgresql.org/download/postgresql-${POSTGRES_JDBC_VERSION}.jar" \
        -o "${SPARK_HOME}/jars/postgresql-${POSTGRES_JDBC_VERSION}.jar"

# --- Build the application jar ------------------------------------------------
WORKDIR /src
COPY spark-gluten-implementation/pom.xml ./
RUN mvn -B -q -DskipTests package && rm -rf target
COPY spark-gluten-implementation/src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/firewall-spark-gluten.jar "${SPARK_HOME}/jars/firewall-spark-gluten.jar"

# --- Runtime image -----------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy
LABEL org.opencontainers.image.title="firewall-spark-gluten"

# Boost 1.84 .so files (built in the build stage) needed by Velox at runtime.
COPY --from=build /usr/local/lib/libboost_*.so* /usr/local/lib/
# Extracted libvelox.so / libgluten.so from the Gluten bundle JAR.
COPY --from=build /usr/local/lib/gluten /usr/local/lib/gluten
RUN ldconfig

COPY --from=build /opt/spark /opt/spark

ENV SPARK_HOME=/opt/spark
ENV PATH="${SPARK_HOME}/bin:${PATH}"
ENV MASTER_DATABASE_URL="jdbc:postgresql://db:5432/firewall?user=firewall&password=firewall&sslmode=disable"
# JAVA_TOOL_OPTIONS carries heap/GC (these are accepted via env var).
# `--add-opens` is a launcher option, so it goes through JAVA_OPTS below and is
# passed on the java command line by the entrypoint (env-var forwarding would
# make the JVM reject it with "Unrecognized option: --add-opens").
ENV JAVA_TOOL_OPTIONS="-XX:+UseParallelGC -Xms2g -Xmx10g"
ENV JAVA_OPTS="\
-Djava.library.path=/usr/local/lib:/usr/local/lib/gluten:/usr/lib/x86_64-linux-gnu:/usr/lib \
--add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.lang.invoke=ALL-UNNAMED \
--add-opens java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens java.base/java.io=ALL-UNNAMED \
--add-opens java.base/java.net=ALL-UNNAMED \
--add-opens java.base/java.nio=ALL-UNNAMED \
--add-opens java.base/java.util=ALL-UNNAMED \
--add-opens java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens java.base/sun.nio.cs=ALL-UNNAMED \
--add-opens java.base/sun.security.action=ALL-UNNAMED \
--add-opens java.base/sun.util.calendar=ALL-UNNAMED \
--add-opens java.base/sun.misc=ALL-UNNAMED \
--add-opens java.base/sun.reflect=ALL-UNNAMED \
--add-opens java.security.jgss/sun.security.krb5=ALL-UNNAMED"

WORKDIR /benchmark
COPY spark-gluten-implementation/run-batch /benchmark/run-batch
RUN chmod 0755 /benchmark/run-batch

EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=180s --retries=120 \
    CMD java -cp "$SPARK_HOME/jars/*" firewall.spark.App health --server http://127.0.0.1:8080 || exit 1

# Classpath wildcard pulls Spark + Gluten + Velox + PostgreSQL JDBC + the app
# jar from $SPARK_HOME/jars in one process (Spark local mode).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS $JAVA_OPTS -cp \"$SPARK_HOME/jars/*\" firewall.spark.App \"$@\"", "--"]
CMD ["serve", "--data", "/benchmark/data", "--listen", ":8080"]
