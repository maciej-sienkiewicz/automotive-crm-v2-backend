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
                    image 'gradle:9.3.0-jdk25'
                    label 'docker'
                    reuseNode true
                }
            }
         steps {
                    sh 'mkdir -p "$GRADLE_USER_HOME"'
                    sh 'chmod +x gradlew || true'
                    sh './gradlew -g "$GRADLE_USER_HOME" bootJar'
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

                    sh """
                      docker build -f ./deploy/Dockerfile -t ${IMAGE_NAME}:${tag} .
                      docker push ${IMAGE_NAME}:${tag}
                    """
                }
            }
        }
    }


}

