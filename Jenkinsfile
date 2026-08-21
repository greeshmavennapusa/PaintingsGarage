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