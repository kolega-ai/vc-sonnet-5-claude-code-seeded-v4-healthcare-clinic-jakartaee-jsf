// Jenkins pipeline for the Clinic Patient Portal.
//
// Builds the WAR with the committed Maven wrapper, runs the unit and
// authorization tests, publishes the reports, and (on the main branch) builds
// the deployable container image.

pipeline {

    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '25'))
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        MAVEN_OPTS = '-Xmx1024m -Djava.awt.headless=true'
        IMAGE_NAME = 'clinic-portal'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                sh 'git --no-pager log -1 --pretty=format:"%h %an %s" || true'
            }
        }

        stage('Toolchain') {
            steps {
                sh './mvnw -version'
            }
        }

        stage('Compile') {
            steps {
                sh './mvnw -B -ntp clean compile'
            }
        }

        stage('Test') {
            steps {
                sh './mvnw -B -ntp test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('Package') {
            steps {
                sh './mvnw -B -ntp -DskipTests package'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.war', fingerprint: true
                }
            }
        }

        stage('Migration syntax check') {
            steps {
                // Fail early if a migration file is empty or unreadable.
                sh '''
                  set -eu
                  for f in src/main/resources/db/migration/*.sql; do
                    echo "Checking $f"
                    test -s "$f"
                  done
                '''
            }
        }

        stage('Container image') {
            when {
                anyOf {
                    branch 'main'
                    branch 'master'
                }
            }
            steps {
                sh "docker build -t ${IMAGE_NAME}:${env.BUILD_NUMBER} -t ${IMAGE_NAME}:latest ."
            }
        }
    }

    post {
        always {
            echo "Build ${currentBuild.currentResult} for ${env.JOB_NAME} #${env.BUILD_NUMBER}"
        }
        failure {
            echo 'Build failed — see the test report and console output above.'
        }
        cleanup {
            cleanWs(deleteDirs: true, notFailBuild: true)
        }
    }
}
