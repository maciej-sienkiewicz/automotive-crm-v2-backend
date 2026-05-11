pipeline {
    agent none

    environment {
        GRADLE_USER_HOME = '/home/gradle/.gradle'
        IMAGE_NAME       = '127.0.0.1:5000/detailing-crm-backend'
        DEPLOY_USER      = 'deployer'
        DEPLOY_HOST      = '172.17.0.1'
        DEPLOY_DIR       = '/opt/apps/prod/app-backend'
        ENV_FILE_DIR     = '/opt/apps/prod'
    }

    stages {

        // ── 1. Build JAR ──────────────────────────────────────────────────────
        stage('Build') {
            agent {
                docker {
                    image 'gradle:8.14-jdk17'
                    label 'docker'
                    reuseNode true
                }
            }
            steps {
                configFileProvider([configFile(fileId: 'PROD_ENV_FILE', variable: 'PROD_ENV_PATH')]) {
                    script {
                        def gActor = sh(script: "grep 'ENV_GITHUB_ACTOR' ${PROD_ENV_PATH} | cut -d'=' -f2", returnStdout: true).trim().replaceAll(/['"]/, '')
                        def gToken = sh(script: "grep 'ENV_GITHUB_TOKEN' ${PROD_ENV_PATH} | cut -d'=' -f2", returnStdout: true).trim().replaceAll(/['"]/, '')

                        sh 'mkdir -p "$GRADLE_USER_HOME"'
                        sh 'chmod +x gradlew || true'
                        sh "./gradlew -g \"\$GRADLE_USER_HOME\" bootJar -Pgpr.user=${gActor} -Pgpr.key=${gToken} --no-daemon"
                    }
                }
            }
        }

        // ── 2. Docker Build & Push ────────────────────────────────────────────
        stage('Docker Build & Push') {
            agent { label 'docker' }
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

        // ── 3. Deploy (tylko main) ────────────────────────────────────────────
        stage('Deploy') {
            when {
                expression { (env.GIT_BRANCH ?: '') == 'origin/main' }
            }
            agent { label 'docker' }
            steps {

                // 3a. Skopiuj pliki deploymentu na serwer
                sshagent(credentials: ['deployer']) {
                    sh """
                        # Utwórz katalogi docelowe
                        ssh -o StrictHostKeyChecking=no ${DEPLOY_USER}@${DEPLOY_HOST} \
                            'mkdir -p ${DEPLOY_DIR}/monitoring/prometheus \
                                      ${DEPLOY_DIR}/monitoring/grafana/provisioning/datasources \
                                      ${DEPLOY_DIR}/monitoring/grafana/provisioning/dashboards'

                        # Skopiuj docker-compose.yaml
                        scp deploy/docker-compose.yaml \
                            ${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_DIR}/docker-compose.yaml

                        # Skopiuj całą konfigurację monitoringu (prometheus + grafana provisioning)
                        scp -r deploy/monitoring/. \
                            ${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_DIR}/monitoring/
                    """
                }

                // 3b. Wgraj plik .env (z Jenkins Managed Files)
                configFileProvider([configFile(fileId: 'PROD_ENV_FILE', variable: 'ENV_FILE_PATH')]) {
                    sshagent(credentials: ['deployer']) {
                        sh "scp \$ENV_FILE_PATH ${DEPLOY_USER}@${DEPLOY_HOST}:${ENV_FILE_DIR}/.env"
                    }
                }

                // 3c. Uruchom stack przez docker compose
                sshagent(credentials: ['deployer']) {
                    sh """
                        ssh ${DEPLOY_USER}@${DEPLOY_HOST} '
                            set -e
                            cd ${DEPLOY_DIR}

                            # Pobierz nowe obrazy
                            docker compose --env-file ${ENV_FILE_DIR}/.env pull

                            # Uruchom / zrestartuj kontenery
                            # --remove-orphans usuwa kontenery nieobecne już w compose file
                            docker compose --env-file ${ENV_FILE_DIR}/.env up -d --remove-orphans
                        '
                    """
                }
            }
            post {
                success { echo "✅ Deploy na produkcję zakończony sukcesem." }
                failure { echo "❌ Deploy nie powiódł się — sprawdź logi powyżej." }
            }
        }
    }
}
