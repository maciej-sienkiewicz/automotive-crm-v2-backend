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
                }
            }
            steps {
                // 1. Pobieramy plik środowiskowy z Managed Files
                configFileProvider([configFile(fileId: 'PROD_ENV_FILE', variable: 'PROD_ENV_PATH')]) {
                    script {
                        // 2. Wyciągamy poświadczenia GitHub za pomocą shella (zabezpieczenie przed brakiem pluginu Utility Steps)
                        def gActor = sh(script: "grep 'ENV_GITHUB_ACTOR' ${PROD_ENV_PATH} | cut -d'=' -f2", returnStdout: true).trim()
                        def gToken = sh(script: "grep 'ENV_GITHUB_TOKEN' ${PROD_ENV_PATH} | cut -d'=' -f2", returnStdout: true).trim()

                        // Czyszczenie ewentualnych cudzysłowów, jeśli występują w pliku (opcjonalne)
                        gActor = gActor.replace('"', '').replace("'", "")
                        gToken = gToken.replace('"', '').replace("'", "")

                        sh 'mkdir -p "$GRADLE_USER_HOME"'
                        sh 'chmod +x gradlew || true'

                        // 3. Budujemy JARa, przekazując poświadczenia jako Project Properties (-P)
                        // To omija problem pustych zmiennych środowiskowych wewnątrz kontenera
                        sh "./gradlew -g \"\$GRADLE_USER_HOME\" bootJar -Pgpr.user=${gActor} -Pgpr.key=${gToken} --no-daemon"
                    }
                }
            }
        }

        stage('Docker Build & Push') {
            agent {
                label 'docker'
            }
            steps {
                script {
                    def branch = env.GIT_BRANCH ?: 'unknown'
                    def tag

                    if (branch == 'origin/main') {
                        tag = 'latest'
                    } else if (branch == 'origin/develop') {
                        tag = 'develop'
                    } else {
                        error("Build przerwany: branch '${branch}' nie jest obsługiwany (tylko 'main' lub 'develop').")
                    }

                    // Budowanie obrazu Docker z wykorzystaniem wygenerowanego wcześniej pliku JAR
                    sh """
                      docker build -f ./deploy/Dockerfile -t ${IMAGE_NAME}:${tag} .
                      docker push ${IMAGE_NAME}:${tag}
                    """
                }
            }
        }
    }
}