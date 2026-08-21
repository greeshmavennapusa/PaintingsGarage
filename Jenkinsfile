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

    stage('UI coverage') {
      steps {
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
          sh 'bash ci/coverage/run.sh'
        }
      }
    }

    stage('Archive') {
      steps {
        archiveArtifacts artifacts: 'coverage-per-test/**/backend.json',
                         allowEmptyArchive: true,
                         fingerprint: true
      }
    }
  }

  post {
    always {
      junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
    }
  }
}