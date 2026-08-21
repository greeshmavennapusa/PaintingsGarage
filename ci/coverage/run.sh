#!/bin/bash
set -euo pipefail

ASM_VERSION=9.6
JACOCO_VERSION=0.8.11
COVERAGE_DIR=coverage-per-test
TOOLS_DIR=target/coverage-tools
LIB_DIR=target/jacoco-cli
HIT_PORT=6301
JACOCO_PORT=6300
APP_URL=http://localhost:8081
APP_JAR=target/PaintingsGarage-0.0.1-SNAPSHOT.jar

sed -i 's/\r$//' mvnw
sed -i 's/\r$//' ci/hit-agent/MANIFEST.MF
chmod +x mvnw

rm -rf "${COVERAGE_DIR}"
mkdir -p "${COVERAGE_DIR}"

cleanup() {
  set +e
  if [ -f target/app.pid ]; then
    kill "$(cat target/app.pid)" >/dev/null 2>&1
  fi
  docker rm -f paintings-sftp >/dev/null 2>&1
}
trap cleanup EXIT

pack_jar() {
  local out="$1"
  local classdir="$2"
  local manifest="${3:-}"
  python3 - "$out" "$classdir" "$manifest" <<'PY'
import os, sys, zipfile
out, classdir, manifest = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out, "w") as z:
    if manifest:
        with open(manifest, "r", encoding="utf-8") as f:
            data = f.read().replace("\r\n", "\n")
        if not data.endswith("\n"):
            data += "\n"
        z.writestr("META-INF/MANIFEST.MF", data)
    for root, _, files in os.walk(classdir):
        for name in files:
            path = os.path.join(root, name)
            arc = os.path.relpath(path, classdir).replace("\\", "/")
            z.write(path, arc)
PY
}

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

mkdir -p "${LIB_DIR}" "${TOOLS_DIR}/hit-runtime-classes" "${TOOLS_DIR}/hit-agent-classes" "${TOOLS_DIR}"

copy_dep "org.jacoco:org.jacoco.agent:${JACOCO_VERSION}:runtime"
copy_dep "org.jacoco:org.jacoco.core:${JACOCO_VERSION}"
copy_dep "org.ow2.asm:asm:${ASM_VERSION}"
copy_dep "org.ow2.asm:asm-tree:${ASM_VERSION}"
copy_dep "org.ow2.asm:asm-commons:${ASM_VERSION}"
copy_dep "org.eclipse.jdt:ecj:3.37.0"

java -jar "${LIB_DIR}/ecj-3.37.0.jar" \
  -source 17 -target 17 \
  -d "${TOOLS_DIR}/hit-runtime-classes" \
  ci/hit-agent/HitRuntime.java
pack_jar "${LIB_DIR}/hit-runtime.jar" "${TOOLS_DIR}/hit-runtime-classes"

java -jar "${LIB_DIR}/ecj-3.37.0.jar" \
  -source 17 -target 17 \
  -d "${TOOLS_DIR}/hit-agent-classes" \
  -cp "${TOOLS_DIR}/hit-runtime-classes:${LIB_DIR}/asm-${ASM_VERSION}.jar" \
  ci/hit-agent/HitAgent.java
pack_jar "${LIB_DIR}/hit-agent.jar" "${TOOLS_DIR}/hit-agent-classes" "ci/hit-agent/MANIFEST.MF"

CORE_JAR=$(ls "${LIB_DIR}"/org.jacoco.core-*.jar | head -n 1)
JACOCO_AGENT=$(ls "${LIB_DIR}"/org.jacoco.agent-*-runtime.jar | head -n 1)
CP="${TOOLS_DIR}:${CORE_JAR}:${LIB_DIR}/asm-${ASM_VERSION}.jar:${LIB_DIR}/asm-tree-${ASM_VERSION}.jar:${LIB_DIR}/asm-commons-${ASM_VERSION}.jar"

java -jar "${LIB_DIR}/ecj-3.37.0.jar" \
  -source 17 -target 17 \
  -d "${TOOLS_DIR}" \
  -cp "${CORE_JAR}" \
  ci/coverage/Args.java \
  ci/coverage/Json.java \
  ci/coverage/CoverageToJson.java

nohup java \
  "-javaagent:${JACOCO_AGENT}=output=tcpserver,address=*,port=${JACOCO_PORT},includes=eu.sanjin.kurelic.paintingsgarage.*" \
  "-javaagent:${LIB_DIR}/hit-agent.jar=port=${HIT_PORT}" \
  -jar "${APP_JAR}" \
  > target/app.log 2>&1 &
echo $! > target/app.pid

for i in $(seq 1 90); do
  curl -sf "${APP_URL}/actuator/health" && break
  sleep 2
done
curl -sf "${APP_URL}/actuator/health"

java -cp "${CP}" CoverageToJson \
  --test startup --classes target/classes \
  --host localhost --hit-port "${HIT_PORT}" --jacoco-port "${JACOCO_PORT}" --reset true \
  --out /tmp/startup-hits.json

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
    -Dskip.installnodenpm -Dskip.yarn
  local rc=$?
  set -e

  java -cp "${CP}" CoverageToJson \
    --test "$name" --classes target/classes \
    --host localhost --hit-port "${HIT_PORT}" --jacoco-port "${JACOCO_PORT}" --reset true \
    --out "$out/backend.json"
  return "$rc"
}

failed=0
run_one HomePageTest eu.sanjin.kurelic.react.HomePageTest "/" || failed=1
run_one LoginPageTest eu.sanjin.kurelic.react.login.LoginPageTest "/user,/login" || failed=1
run_one CartPageTest eu.sanjin.kurelic.react.cart.CartPageTest "/cart" || failed=1
run_one SearchPageTest eu.sanjin.kurelic.react.search.SearchPageTest "/" || failed=1
exit "$failed"
