#!/bin/bash
set -euo pipefail

ASM_VERSION=9.6
JACOCO_VERSION=0.8.11
APP_JAR=target/PaintingsGarage-0.0.1-SNAPSHOT.jar
COVERAGE_DIR=coverage-per-test
TOOLS_DIR=target/coverage-tools
LIB_DIR=target/jacoco-cli
JACOCO_PORT=6300
APP_URL=http://localhost:8081

sed -i 's/\r$//' mvnw
chmod +x mvnw

cleanup() {
  set +e
  if [ -f target/app.pid ]; then
    kill "$(cat target/app.pid)" >/dev/null 2>&1
  fi
  docker rm -f paintings-sftp >/dev/null 2>&1
}
trap cleanup EXIT

docker rm -f paintings-sftp >/dev/null 2>&1 || true
docker run -d --name paintings-sftp \
  -p 2222:22 \
  -v "$PWD/docker-files/images:/home/user/images" \
  atmoz/sftp \
  user:password:1001

./mvnw -B package -DskipTests

copy_dep() {
  ./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
    -Dartifact="$1" \
    -DoutputDirectory="${LIB_DIR}"
}

copy_dep "org.jacoco:org.jacoco.agent:${JACOCO_VERSION}:runtime"
copy_dep "org.jacoco:org.jacoco.core:${JACOCO_VERSION}"
copy_dep "org.ow2.asm:asm:${ASM_VERSION}"
copy_dep "org.ow2.asm:asm-tree:${ASM_VERSION}"
copy_dep "org.ow2.asm:asm-commons:${ASM_VERSION}"
copy_dep "org.eclipse.jdt:ecj:3.37.0"

AGENT_JAR=$(ls "${LIB_DIR}"/org.jacoco.agent-*-runtime.jar | head -n 1)
CORE_JAR=$(ls "${LIB_DIR}"/org.jacoco.core-*.jar | head -n 1)
CP="${TOOLS_DIR}:${CORE_JAR}:${LIB_DIR}/asm-${ASM_VERSION}.jar:${LIB_DIR}/asm-tree-${ASM_VERSION}.jar:${LIB_DIR}/asm-commons-${ASM_VERSION}.jar"

mkdir -p "${TOOLS_DIR}"
java -jar "${LIB_DIR}/ecj-3.37.0.jar" \
  -source 17 -target 17 \
  -d "${TOOLS_DIR}" \
  -cp "${CORE_JAR}" \
  ci/coverage/Args.java \
  ci/coverage/Json.java \
  ci/coverage/JacocoToJson.java

nohup java \
  "-javaagent:${AGENT_JAR}=output=tcpserver,address=*,port=${JACOCO_PORT},includes=eu.sanjin.kurelic.paintingsgarage.*" \
  -jar "${APP_JAR}" \
  > target/app.log 2>&1 &
echo $! > target/app.pid

for i in $(seq 1 90); do
  curl -sf "${APP_URL}/actuator/health" && break
  sleep 2
done
curl -sf "${APP_URL}/actuator/health"

java -cp "${CP}" JacocoToJson \
  --test startup --classes target/classes \
  --host localhost --port "${JACOCO_PORT}" --reset true \
  --out /tmp/startup-coverage.json

rm -rf "${COVERAGE_DIR}"
mkdir -p "${COVERAGE_DIR}"

hit_pages() {
  local pages="$1"
  IFS=',' read -ra PARTS <<< "$pages"
  for p in "${PARTS[@]}"; do
    curl -sf "${APP_URL}${p}" >/dev/null || true
  done
}

run_one() {
  local name="$1"
  local fqn="$2"
  local pages="$3"
  local out="${COVERAGE_DIR}/${name}"
  mkdir -p "$out"

  hit_pages "$pages"

  set +e
  ./mvnw -B test -Dtest="$fqn" \
    -Dskip.installnodenpm -Dskip.yarn \
    -Dtestcontainers.version=1.21.3
  local rc=$?
  set -e

  java -cp "${CP}" JacocoToJson \
    --test "$name" --classes target/classes \
    --host localhost --port "${JACOCO_PORT}" --reset true \
    --out "$out/backend.json"
  return "$rc"
}

failed=0
run_one HomePageTest eu.sanjin.kurelic.react.HomePageTest "/" || failed=1
run_one LoginPageTest eu.sanjin.kurelic.react.login.LoginPageTest "/user,/login" || failed=1
run_one CartPageTest eu.sanjin.kurelic.react.cart.CartPageTest "/cart" || failed=1
run_one SearchPageTest eu.sanjin.kurelic.react.search.SearchPageTest "/" || failed=1
exit "$failed"