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
                    // KLUCZ: Przekazujemy zmienne z hosta Jenkinsa do wnętrza kontenera Gradle
                    args "-e GITHUB_ACTOR=${env.ENV_GITHUB_ACTOR} -e GITHUB_TOKEN=${env.ENV_GITHUB_TOKEN}"
                }
            }
            steps {
                // Wczytujemy Twój plik z Managed Files
                configFileProvider([configFile(fileId: 'PROD_ENV_FILE', variable: 'PROD_ENV_PATH')]) {
                    script {
                        // Odczytujemy plik i eksportujemy zmienne do środowiska Jenkinsa (env)
                        def props = readProperties file: PROD_ENV_PATH
                        env.ENV_GITHUB_ACTOR = props['ENV_GITHUB_ACTOR']
                        env.ENV_GITHUB_TOKEN = props['ENV_GITHUB_TOKEN']

                        sh 'mkdir -p "$GRADLE_USER_HOME"'
                        sh 'chmod +x gradlew || true'

                        // Uruchamiamy Gradle - on teraz "zobaczy" te zmienne w System.getenv()
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