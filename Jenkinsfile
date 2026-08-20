pipeline {
  agent any

  options {
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 60, unit: 'MINUTES')
  }

  environment {
    JACOCO_VERSION = '0.8.11'
    APP_JAR        = 'target/PaintingsGarage-0.0.1-SNAPSHOT.jar'
    COVERAGE_DIR   = 'coverage-per-test'
    TOOLS_DIR      = 'target/coverage-tools'
    JACOCO_DIR     = 'target/jacoco-cli'
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

          docker rm -f paintings-sftp >/dev/null 2>&1 || true
          docker run -d --name paintings-sftp \
            -p 2222:22 \
            -v "$PWD/docker-files/images:/home/user/images" \
            atmoz/sftp \
            user:password:1001

          ./mvnw -B package -DskipTests
          ./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
            -Dartifact=org.jacoco:org.jacoco.core:${JACOCO_VERSION} \
            -DoutputDirectory=${JACOCO_DIR}
          ./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
            -Dartifact=org.jacoco:org.jacoco.agent:${JACOCO_VERSION}:jar:runtime \
            -DoutputDirectory=${JACOCO_DIR}

          mkdir -p ${TOOLS_DIR}
          javac --release 17 -cp "${JACOCO_DIR}/org.jacoco.core-${JACOCO_VERSION}.jar" \
            ci/coverage/*.java -d ${TOOLS_DIR}

          AGENT="${JACOCO_DIR}/org.jacoco.agent-${JACOCO_VERSION}-runtime.jar"
          nohup java \
            "-javaagent:${AGENT}=output=tcpserver,address=*,port=6300,append=false" \
            -jar "${APP_JAR}" \
            > target/app.log 2>&1 &
          echo $! > target/app.pid

          for i in $(seq 1 90); do
            curl -sf http://localhost:8081/actuator/health && break
            sleep 2
          done
          curl -sf http://localhost:8081/actuator/health

          CP="${TOOLS_DIR}:${JACOCO_DIR}/org.jacoco.core-${JACOCO_VERSION}.jar"
          java -cp "$CP" JacocoToJson \
            --test startup --classes target/classes --reset true \
            --out /tmp/startup-coverage.json

          rm -rf "${COVERAGE_DIR}"
          mkdir -p "${COVERAGE_DIR}"
        '''

        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
          sh '''#!/bin/bash
            set -euo pipefail
            CP="${TOOLS_DIR}:${JACOCO_DIR}/org.jacoco.core-${JACOCO_VERSION}.jar"

            run_one() {
              local name="$1"
              local fqn="$2"
              local out="${COVERAGE_DIR}/${name}"
              mkdir -p "$out"

              java -cp "$CP" WriteFrontendJson \
                --test "$name" \
                --source src/test/java \
                --out "$out/frontend.json"

              set +e
              ./mvnw -B test -Dtest="$fqn" -Dskip.installnodenpm -Dskip.yarn
              local rc=$?
              set -e

              java -cp "$CP" JacocoToJson \
                --test "$name" --classes target/classes --reset true \
                --out "$out/backend.json"
              return "$rc"
            }

            failed=0
            run_one HomePageTest eu.sanjin.kurelic.react.HomePageTest || failed=1
            run_one LoginPageTest eu.sanjin.kurelic.react.login.LoginPageTest || failed=1
            run_one CartPageTest eu.sanjin.kurelic.react.cart.CartPageTest || failed=1
            run_one SearchPageTest eu.sanjin.kurelic.react.search.SearchPageTest || failed=1
            exit "$failed"
          '''
        }
      }
    }

    stage('Archive Coverage Artifacts') {
      steps {
        archiveArtifacts artifacts: 'coverage-per-test/**/*.json', allowEmptyArchive: true, fingerprint: true
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
