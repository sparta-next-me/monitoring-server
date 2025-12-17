pipeline {
    agent any
    environment {
        APP_NAME        = "product-service"
        // GHCR 레지스트리 정보
        REGISTRY        = "ghcr.io"
        GH_OWNER        = "sparta-next-me"
        IMAGE_REPO      = "product-service"
        FULL_IMAGE      = "${REGISTRY}/${GH_OWNER}/${IMAGE_REPO}:latest"
        CONTAINER_NAME  = "product-service"
        HOST_PORT       = "11117"
        CONTAINER_PORT  = "11117"
    }
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Build & Test') {
            steps {
                withCredentials([
                    file(credentialsId: 'payment-env', variable: 'ENV_FILE')
                ]) {
                    sh '''
                      # 환경 파일 존재 확인
                      if [ ! -f "$ENV_FILE" ]; then
                        echo "Error: ENV_FILE not found at $ENV_FILE"
                        exit 1
                      fi
                      set -a
                      . "$ENV_FILE"       # DB_URL, DB_USERNAME, DB_PASSWORD, REDIS_HOST, OAUTH 키들 export
                      set +a
                      ./gradlew clean test --no-daemon
                      ./gradlew bootJar --no-daemon
                    '''
                }
            }
        }
        stage('Docker Build') {
            steps {
                sh """
                  docker build -t ${FULL_IMAGE} .
                """
            }
        }
        stage('Push Image') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'ghcr-credential',
                        usernameVariable: 'REGISTRY_USER',
                        passwordVariable: 'REGISTRY_TOKEN'
                    )
                ]) {
                    sh """
                      set -e  # 아래 명령 중 하나라도 실패하면 즉시 종료
                      echo "\$REGISTRY_TOKEN" | docker login ghcr.io -u "\$REGISTRY_USER" --password-stdin
                      docker push ${FULL_IMAGE}
                    """
                }
            }
        }
        stage('Deploy') {
            steps {
                withCredentials([
                    file(credentialsId: 'payment-env', variable: 'ENV_FILE')
                ]) {
                    sh """
                      # 기존 컨테이너 있으면 정지/삭제
                      if [ \$(docker ps -aq -f name=${CONTAINER_NAME}) ]; then
                        echo "Stopping existing container..."
                        docker stop ${CONTAINER_NAME} || true
                        docker rm ${CONTAINER_NAME} || true
                        docker rmi ${FULL_IMAGE} || true
                      fi
                      echo "Starting new product-service container..."
                      docker run -d --name ${CONTAINER_NAME} \\
                        --env-file \${ENV_FILE} \\
                        -p ${HOST_PORT}:${CONTAINER_PORT} \\
                        ${FULL_IMAGE}
                    """
                }
            }
        }
    }
}