pipeline {
    agent none

    environment {
        GRADLE_USER_HOME = '/home/gradle/.gradle'
        IMAGE_NAME = '127.0.0.1:5000/detailing-crm-backend'
    }

    stages {
        stage('Build') {
            agent {
                docker {
                    image 'gradle:8.14-jdk17'
                    label 'docker'
                    reuseNode true
                    // Przekazujemy zmienne środowiskowe pobrane wewnątrz kroku script
                    args "-e GITHUB_ACTOR=${env.G_ACTOR} -e GITHUB_TOKEN=${env.G_TOKEN}"
                }
            }
            steps {
                configFileProvider([configFile(fileId: 'PROD_ENV_FILE', variable: 'PROD_ENV_PATH')]) {
                    script {
                        // Wyciągamy wartości bezpośrednio z pliku za pomocą shella
                        // Zakładamy format w pliku: ENV_GITHUB_ACTOR=wartosc
                        env.G_ACTOR = sh(script: "grep 'ENV_GITHUB_ACTOR' ${PROD_ENV_PATH} | cut -d'=' -f2", returnStdout: true).trim()
                        env.G_TOKEN = sh(script: "grep 'ENV_GITHUB_TOKEN' ${PROD_ENV_PATH} | cut -d'=' -f2", returnStdout: true).trim()

                        sh 'mkdir -p "$GRADLE_USER_HOME"'
                        sh 'chmod +x gradlew || true'

                        // Budujemy projekt
                        sh './gradlew -g "$GRADLE_USER_HOME" bootJar'
                    }
                }
            }
        }

        stage('Docker Build & Push') {
            agent { label 'docker' }
            steps {
                script {
                    def branch = env.GIT_BRANCH ?: 'unknown'
                    def tag = (branch == 'origin/main') ? 'latest' : 'develop'

                    if (branch != 'origin/main' && branch != 'origin/develop') {
                        error("Build przerwany: branch '${branch}' nie jest obsługiwany.")
                    }

                    sh """
                      docker build -f ./deploy/Dockerfile -t ${IMAGE_NAME}:${tag} .
                      docker push ${IMAGE_NAME}:${tag}
                    """
                }
            }
        }
    }
}