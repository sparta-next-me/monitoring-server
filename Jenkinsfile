pipeline {
    agent any

    environment {
        APP_NAME        = "monitoring-server"
        NAMESPACE       = "next-me"
        REGISTRY        = "ghcr.io"
        GH_OWNER        = "sparta-next-me"
        IMAGE_REPO      = "monitoring-server"
        FULL_IMAGE      = "${REGISTRY}/${GH_OWNER}/${IMAGE_REPO}:latest"
        TZ              = "Asia/Seoul"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build & Test') {
            steps {
                withCredentials([file(credentialsId: 'promotion-env', variable: 'ENV_FILE')]) {
                    sh '''
                      set -a
                      . "$ENV_FILE"
                      set +a
                      chmod +x ./gradlew
                      ./gradlew clean bootJar --no-daemon --refresh-dependencies
                    '''
                }
            }
        }

        stage('Docker Build & Push') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-credential', usernameVariable: 'USER', passwordVariable: 'TOKEN')]) {
                    sh """
                      docker build -t ${FULL_IMAGE} .
                      echo "${TOKEN}" | docker login ${REGISTRY} -u "${USER}" --password-stdin
                      docker push ${FULL_IMAGE}
                    """
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withCredentials([
                    file(credentialsId: 'k3s-kubeconfig', variable: 'KUBECONFIG_FILE'),
                    file(credentialsId: 'promotion-env', variable: 'ENV_FILE')
                ]) {
                    sh '''
                      export KUBECONFIG=${KUBECONFIG_FILE}

                      echo "Updating K8s Secret: promotion-env..."
                      kubectl delete secret promotion-env -n ${NAMESPACE} --ignore-not-found
                      kubectl create secret generic promotion-env --from-env-file=${ENV_FILE} -n ${NAMESPACE}

                      echo "Applying manifests..."
                      kubectl apply -f monitoring-server.yaml -n ${NAMESPACE}

                      echo "Monitoring rollout status..."
                      kubectl rollout status deployment/monitoring-server -n ${NAMESPACE}
                    '''
                }
            }
        }
    }

    post {
        always {
            echo "Cleaning up..."
            sh "docker rmi ${FULL_IMAGE} || true"
            sh "docker system prune -f"
        }
        success {
            echo "Successfully deployed ${APP_NAME}!"
        }
        failure {
            echo "Deployment failed."
        }
    }
}