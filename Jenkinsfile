pipeline {
    agent { label 'docker' }

    options {
        // Nowy push na ten sam branch unieważnia build w toku — nie ma sensu kończyć
        // kompilacji commita, którego obraz i tak zostanie zaraz nadpisany.
        disableConcurrentBuilds(abortPrevious: true)
        timeout(time: 20, unit: 'MINUTES')
    }

    parameters {
        // Wyjście awaryjne dla kompilacji przyrostowej (patrz komentarz przy 'Test & Build').
        booleanParam(name: 'CLEAN_BUILD', defaultValue: false,
            description: 'Pełna kompilacja od zera: `clean` + bez build cache. Użyj, gdy build zachowuje się niewytłumaczalnie.')
    }

    environment {
        GRADLE_USER_HOME = '/home/gradle/.gradle'
        IMAGE_NAME       = '127.0.0.1:5000/detailing-crm-backend'
        DOCKER_BUILDKIT  = '1'
    }

    stages {

        // Decyzja „czy ten branch w ogóle produkuje obraz" zapada NA POCZĄTKU. Wcześniej
        // zapadała w ostatnim etapie: branch spoza main/develop przechodził pełne testy
        // i bootJar tylko po to, żeby wywrócić się na `error(...)` tuż przed `docker build`.
        stage('Resolve target') {
            steps {
                script {
                    def branch = env.GIT_BRANCH ?: 'unknown'
                    if (branch == 'origin/main') {
                        env.IMAGE_TAG = 'latest'
                    } else if (branch == 'origin/develop') {
                        env.IMAGE_TAG = 'develop'
                    } else {
                        env.IMAGE_TAG = ''
                        echo "Branch '${branch}' nie jest wdrażany — tylko testy, bez bootJar i bez obrazu."
                    }
                }
            }
        }

        // JEDNO wywołanie Gradle'a zamiast dwóch etapów w dwóch kontenerach. Każdy z nich
        // płacił osobno za start JVM, konfigurację buildu i — przede wszystkim — za pusty
        // GRADLE_USER_HOME.
        //
        // KOMPILACJA PRZYROSTOWA — największa pojedyncza dźwignia w tym pliku, a nie ma jej
        // w kodzie, tylko w konfiguracji joba. Zmierzone: pełna kompilacja 157 tys. linii to
        // 50–80 s, zmiana w jednym handlerze — 10 s, zmiana ABI w shared/ValueClasses.kt — 14 s.
        // Kotlin trzyma stan przyrostowy w build/kotlin, czyli w WORKSPACE. W konfiguracji joba
        // NIE włączaj „Wipe out repository & force clone", „Clean before/after checkout" ani
        // cleanWs() — każda z tych opcji kasuje build/ i przywraca pełną kompilację co build.
        stage('Test & Build') {
            agent {
                docker {
                    image 'gradle:8.14-jdk17'
                    reuseNode true
                    // Obraz `gradle` deklaruje /home/gradle/.gradle jako VOLUME, więc bez
                    // nazwanego wolumenu każdy kontener dostawał świeży, anonimowy katalog:
                    // dystrybucja wrappera (~130 MB) i wszystkie zależności (Spring, AWS SDK,
                    // Spring AI, Batik, BouncyCastle…) pobierały się od zera W KAŻDYM etapie
                    // KAŻDEGO builda. Nazwany wolumen przeżywa kontener — niesie też lokalny
                    // build cache (org.gradle.caching=true w gradle.properties).
                    args '-v gradle-cache:/home/gradle/.gradle'
                }
            }
            steps {
                configFileProvider([configFile(fileId: 'PROD_ENV_FILE', variable: 'PROD_ENV_PATH')]) {
                    withEnv(["GRADLE_TASKS=${params.CLEAN_BUILD ? 'clean --no-build-cache ' : ''}${env.IMAGE_TAG ? 'test bootJar' : 'test'}"]) {
                        // Pojedyncze cudzysłowy: Groovy niczego tu nie interpoluje. Poprzednio
                        // token szedł jako `-Pgpr.key=<PAT>` w linii poleceń, którą `sh` wypisuje
                        // do logu builda otwartym tekstem. build.gradle.kts czyta te same dane
                        // z GITHUB_ACTOR/GITHUB_TOKEN, więc wystarczy środowisko procesu.
                        //
                        // Testy jadą na PRAWDZIWYM SDK KSeF (CI ma credentiale), więc dryf
                        // stubów z ksef-stub/ nigdy nie ukryje błędu kompilacji.
                        // Twarda bramka: czerwony test wywraca ten etap, więc obraz nie powstaje.
                        sh '''
                            set +x
                            read_env() { grep -m1 "$1" "$PROD_ENV_PATH" | cut -d= -f2- | tr -d '[:space:]' | tr -d "'" | tr -d '"'; }
                            export GITHUB_ACTOR="$(read_env ENV_GITHUB_ACTOR)"
                            export GITHUB_TOKEN="$(read_env ENV_GITHUB_TOKEN)"

                            chmod +x gradlew || true
                            ./gradlew -g "$GRADLE_USER_HOME" $GRADLE_TASKS --no-daemon
                        '''
                    }
                }
            }
            post {
                always {
                    // Gdy `test` wraca z build cache, XML-e mają stare znaczniki czasu, a krok
                    // `junit` uznaje takie raporty za nieświeże i wywraca build.
                    sh 'touch build/test-results/test/*.xml 2>/dev/null || true'
                    junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'
                }
            }
        }

        stage('Docker Build & Push') {
            when { expression { env.IMAGE_TAG } }
            steps {
                sh '''
                    docker build -f ./deploy/Dockerfile -t "$IMAGE_NAME:$IMAGE_TAG" .
                    docker push "$IMAGE_NAME:$IMAGE_TAG"
                '''
            }
        }
    }
}
