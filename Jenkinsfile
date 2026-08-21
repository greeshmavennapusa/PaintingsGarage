pipeline {
  agent any

  tools {
    jdk 'jdk-17'
  }

  options {
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 60, unit: 'MINUTES')
  }

  environment {
    ASM_VERSION    = '9.6'
    APP_JAR        = 'target/PaintingsGarage-0.0.1-SNAPSHOT.jar'
    COVERAGE_DIR   = 'coverage-per-test'
    TOOLS_DIR      = 'target/coverage-tools'
    LIB_DIR        = 'target/jacoco-cli'
    HIT_PORT       = '6301'
    TESTCONTAINERS_RYUK_DISABLED = 'true'
    DOCKER_API_VERSION = '1.44'
    TESTCONTAINERS_DOCKER_CLIENT_STRATEGY = 'org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Build & Run') {
      steps {
        sh '''#!/bin/bash
          set -euo pipefail
          sed -i 's/\\r$//' mvnw
          chmod +x mvnw

          java -version

          docker rm -f paintings-sftp >/dev/null 2>&1 || true
          docker run -d --name paintings-sftp \
            -p 2222:22 \
            -v "$PWD/docker-files/images:/home/user/images" \
            atmoz/sftp \
            user:password:1001

          ./mvnw -B package -DskipTests

          ./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
            -Dartifact=org.ow2.asm:asm:${ASM_VERSION} \
            -DoutputDirectory=${LIB_DIR}
          ./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
            -Dartifact=org.ow2.asm:asm-tree:${ASM_VERSION} \
            -DoutputDirectory=${LIB_DIR}
          ./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
            -Dartifact=org.ow2.asm:asm-commons:${ASM_VERSION} \
            -DoutputDirectory=${LIB_DIR}
          ./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
            -Dartifact=org.eclipse.jdt:ecj:3.37.0 \
            -DoutputDirectory=${LIB_DIR}

          mkdir -p ${TOOLS_DIR}/hit-runtime-classes ${TOOLS_DIR}/hit-agent-classes ${TOOLS_DIR}

          java -jar ${LIB_DIR}/ecj-3.37.0.jar \
            -source 17 -target 17 \
            -d ${TOOLS_DIR}/hit-runtime-classes \
            ci/hit-agent/HitRuntime.java

          java -jar ${LIB_DIR}/ecj-3.37.0.jar \
            -source 17 -target 17 \
            -d ${TOOLS_DIR}/hit-agent-classes \
            -cp ${TOOLS_DIR}/hit-runtime-classes:${LIB_DIR}/asm-${ASM_VERSION}.jar \
            ci/hit-agent/HitAgent.java

          java -jar ${LIB_DIR}/ecj-3.37.0.jar \
            -source 17 -target 17 \
            -d ${TOOLS_DIR} \
            ci/coverage/Args.java \
            ci/coverage/Json.java \
            ci/hit-agent/HitToJson.java

          python3 - <<'PY'
          import zipfile
          from pathlib import Path

          lib = Path("target/jacoco-cli")

          def pack(src, dest, manifest=None):
              dest.parent.mkdir(parents=True, exist_ok=True)
              with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as zf:
                  if manifest is not None:
                      text = Path(manifest).read_text(encoding="utf-8").replace("\\r\\n", "\\n").replace("\\r", "\\n")
                      if not text.endswith("\\n"):
                          text += "\\n"
                      zf.writestr("META-INF/MANIFEST.MF", text.encode("utf-8"))
                  root = Path(src)
                  for path in root.rglob("*"):
                      if path.is_file():
                          zf.write(path, path.relative_to(root).as_posix())

          pack("target/coverage-tools/hit-runtime-classes", lib / "hit-runtime.jar")
          pack("target/coverage-tools/hit-agent-classes", lib / "hit-agent.jar", "ci/hit-agent/MANIFEST.MF")
          PY

          nohup java \
            "-javaagent:${LIB_DIR}/hit-agent.jar=port=${HIT_PORT}" \
            -jar "${APP_JAR}" \
            > target/app.log 2>&1 &
          echo $! > target/app.pid

          for i in $(seq 1 90); do
            curl -sf http://localhost:8081/actuator/health && break
            sleep 2
          done
          curl -sf http://localhost:8081/actuator/health

          java -cp "${TOOLS_DIR}" HitToJson \
            --test startup --host localhost --port "${HIT_PORT}" --reset true \
            --out /tmp/startup-coverage.json

          rm -rf "${COVERAGE_DIR}"
          mkdir -p "${COVERAGE_DIR}"
        '''

        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
          sh '''#!/bin/bash
            set -euo pipefail
            export TESTCONTAINERS_RYUK_DISABLED=true
            export DOCKER_HOST="${DOCKER_HOST:-unix:///var/run/docker.sock}"
            export DOCKER_API_VERSION=1.44
            unset TESTCONTAINERS_HOST_OVERRIDE || true

            hit_pages() {
              local pages="$1"
              IFS=',' read -ra PARTS <<< "$pages"
              for p in "${PARTS[@]}"; do
                curl -sf "http://localhost:8081${p}" >/dev/null || true
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

              java -cp "${TOOLS_DIR}" HitToJson \
                --test "$name" --host localhost --port "${HIT_PORT}" --reset true \
                --out "$out/backend.json"
              return "$rc"
            }

            failed=0
            run_one HomePageTest eu.sanjin.kurelic.react.HomePageTest "/" || failed=1
            run_one LoginPageTest eu.sanjin.kurelic.react.login.LoginPageTest "/user,/login" || failed=1
            run_one CartPageTest eu.sanjin.kurelic.react.cart.CartPageTest "/cart" || failed=1
            run_one SearchPageTest eu.sanjin.kurelic.react.search.SearchPageTest "/" || failed=1
            exit "$failed"
          '''
        }
      }
    }

    stage('Archive Coverage Artifacts') {
      steps {
        archiveArtifacts artifacts: 'coverage-per-test/**/backend.json', allowEmptyArchive: true, fingerprint: true
      }
    }
  }

  post {
    always {
      junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
      sh '''#!/bin/bash
        set +e
        if [ -f target/app.pid ]; then kill "$(cat target/app.pid)"; fi
        docker rm -f paintings-sftp >/dev/null 2>&1
        exit 0
      '''
    }
  }
}
