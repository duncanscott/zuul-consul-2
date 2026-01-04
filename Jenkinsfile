pipeline {
    agent none
    options {
        skipDefaultCheckout true
        buildDiscarder(logRotator(
            numToKeepStr: '10',
            artifactNumToKeepStr: '10',
            artifactDaysToKeepStr: '180'
        ))
    }

    environment {
        JAVA_HOME = '/home/jenkins/java/java21'
    }

    stages {
        stage('Init') {
            agent {
                node {
                    label 'java-21'
                    customWorkspace "/home/jenkins/workspace/${env.JOB_NAME}_${env.BUILD_NUMBER}/init"
                }
            }
            steps {
                script {
                    if (env.gitlabActionType != 'TAG_PUSH') {
                        error("This pipeline can only be triggered by a TAG_PUSH event. Current action: ${env.gitlabActionType}")
                    }

                    checkout scm
                    checkoutGitRepo()

                    sh 'printenv'
                    sh 'ls -l'
                    sh 'cat gradle.properties'
                    sh 'chmod +x gradlew'
                    sh './gradlew -v'

                    def gradleProps = readProperties file: 'gradle.properties'
                    def projectVersion = gradleProps['version']
                    def gitTag = env.gitlabBranch.replace('refs/tags/', '')

                    if (gitTag != projectVersion) {
                        error("Git tag (${gitTag}) does not match the version in gradle.properties (${projectVersion})")
                    }

                    echo "Triggered by tag push: ${gitTag}"
                    echo "Version validation passed"

                    // Build and run tests
                    sh './gradlew --no-daemon clean build :app:distTar'

                    archiveArtifacts artifacts: "app/build/distributions/*.tar",
                                     fingerprint: true,
                                     onlyIfSuccessful: true

                    echo "Distribution archived"

                    // Stash for deployment stages
                    // Must include filters/build.gradle because settings.gradle references :filters subproject
                    stash name: 'gradle-scripts', includes: 'gradlew,settings.gradle,gradle.properties,build.gradle,gradle/**,app/build.gradle,app/build/distributions/*,filters/build.gradle'
                    stash name: 'workspace-full', includes: '**/*'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
                    cleanWs()
                }
                success {
                    echo "Init stage successful - distribution built and tests passed"
                }
                failure {
                    echo "Init stage failed - build or tests failed"
                }
            }
        }

        stage('Deploy and Test dev') {
            parallel {
                stage('Deploy dev') {
                    agent {
                        node {
                            label 'java-21'
                            customWorkspace "/home/jenkins/workspace/${env.JOB_NAME}_${env.BUILD_NUMBER}/dev-deploy"
                        }
                    }
                    steps {
                        unstash 'gradle-scripts'
                        configFileProvider([configFile(fileId: 'zuul-consul-remotes.gradle', targetLocation: 'gradle/scripts/remotes.gradle')]) {
                            echo "Deploying to dev environment..."
                            sh './gradlew --no-daemon deployOnly -Penv=dev'
                            echo "Dev deployment completed"
                        }
                    }
                    post {
                        success {
                            echo "Dev deployment successful"
                        }
                        failure {
                            echo "Dev deployment failed"
                        }
                        always { cleanWs() }
                    }
                }

                stage('Test dev') {
                    agent {
                        node {
                            label 'java-21'
                            customWorkspace "/home/jenkins/workspace/${env.JOB_NAME}_${env.BUILD_NUMBER}/dev-test"
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            unstash 'workspace-full'
                            configFileProvider([configFile(fileId: 'zuul-consul-dev.env', variable: 'ENV_FILE')]) {
                                sh """
                                    set -a
                                    source ${ENV_FILE}
                                    set +a
                                    echo "Running tests against dev environment..."
                                    ./gradlew --no-daemon check
                                """
                            }
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
                            cleanWs()
                        }
                        success {
                            echo "Dev tests passed"
                        }
                        failure {
                            echo "Dev tests failed"
                        }
                    }
                }
            }
        }

        stage('Approve int') {
            steps {
                script {
                    def deploymentInfo = """
                    Integration Deployment Request

                    Tag: ${env.gitlabBranch.replace('refs/tags/', '')}
                    Build: #${env.BUILD_NUMBER}
                    Requested by: ${env.gitlabUserName}

                    Ready to deploy to integration?
                    """

                    def approvalResult = input message: deploymentInfo,
                          ok: 'Deploy to Integration',
                          submitterParameter: 'INT_APPROVER'

                    env.INT_APPROVER = approvalResult
                    echo "Integration deployment approved by: ${env.INT_APPROVER}"
                }
            }
        }

        stage('Deploy and Test int') {
            parallel {
                stage('Deploy int') {
                    agent {
                        node {
                            label 'java-21'
                            customWorkspace "/home/jenkins/workspace/${env.JOB_NAME}_${env.BUILD_NUMBER}/int-deploy"
                        }
                    }
                    steps {
                        unstash 'gradle-scripts'
                        configFileProvider([configFile(fileId: 'zuul-consul-remotes.gradle', targetLocation: 'gradle/scripts/remotes.gradle')]) {
                            echo "Deploying to int environment..."
                            sh './gradlew --no-daemon deployOnly -Penv=int'
                            echo "Int deployment completed"
                        }
                    }
                    post {
                        success {
                            echo "Int deployment successful"
                        }
                        failure {
                            echo "Int deployment failed"
                        }
                        always { cleanWs() }
                    }
                }

                stage('Test int') {
                    agent {
                        node {
                            label 'java-21'
                            customWorkspace "/home/jenkins/workspace/${env.JOB_NAME}_${env.BUILD_NUMBER}/int-test"
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            unstash 'workspace-full'
                            configFileProvider([configFile(fileId: 'zuul-consul-int.env', variable: 'ENV_FILE')]) {
                                sh """
                                    set -a
                                    source ${ENV_FILE}
                                    set +a
                                    echo "Running tests against int environment..."
                                    ./gradlew --no-daemon check
                                """
                            }
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
                            cleanWs()
                        }
                        success {
                            echo "Int tests passed"
                        }
                        failure {
                            echo "Int tests failed"
                        }
                    }
                }
            }
        }

        stage('Approve prd') {
            steps {
                script {
                    def deploymentInfo = """
                    Production Deployment Request

                    Tag: ${env.gitlabBranch.replace('refs/tags/', '')}
                    Build: #${env.BUILD_NUMBER}
                    Requested by: ${env.gitlabUserName}
                    Int approved by: ${env.INT_APPROVER}

                    Ready to deploy to production?
                    """

                    def approvalResult = input message: deploymentInfo,
                          ok: 'Deploy to Production',
                          submitterParameter: 'PRD_APPROVER'

                    env.PRD_APPROVER = approvalResult
                    echo "Production deployment approved by: ${env.PRD_APPROVER}"

                    def tag = env.gitlabBranch.replace('refs/tags/', '')
                    currentBuild.description = "Tag: ${tag} | Int: ${env.INT_APPROVER} | Prd: ${env.PRD_APPROVER}"
                }
            }
        }

        stage('Deploy and Test prd') {
            parallel {
                stage('Deploy prd') {
                    agent {
                        node {
                            label 'java-21'
                            customWorkspace "/home/jenkins/workspace/${env.JOB_NAME}_${env.BUILD_NUMBER}/prd-deploy"
                        }
                    }
                    steps {
                        unstash 'gradle-scripts'
                        script {
                            def tag = env.gitlabBranch.replace('refs/tags/', '')
                            writeFile file: 'deployment-approval.txt', text: """
Tag: ${tag}
Build: ${env.BUILD_NUMBER}
Requested by: ${env.gitlabUserName}
Int approved by: ${env.INT_APPROVER}
Prd approved by: ${env.PRD_APPROVER}
Approved at: ${new Date()}
"""
                            archiveArtifacts artifacts: 'deployment-approval.txt'
                        }

                        configFileProvider([configFile(fileId: 'zuul-consul-remotes.gradle', targetLocation: 'gradle/scripts/remotes.gradle')]) {
                            echo "Deploying to production (approved by: ${env.PRD_APPROVER})..."
                            sh './gradlew --no-daemon deployOnly -Penv=prd'
                            echo "Prd deployment completed"
                        }
                    }
                    post {
                        success {
                            echo "Production deployment successful!"
                        }
                        failure {
                            echo "Production deployment failed"
                        }
                        always { cleanWs() }
                    }
                }

                stage('Test prd') {
                    agent {
                        node {
                            label 'java-21'
                            customWorkspace "/home/jenkins/workspace/${env.JOB_NAME}_${env.BUILD_NUMBER}/prd-test"
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            unstash 'workspace-full'
                            configFileProvider([configFile(fileId: 'zuul-consul-prd.env', variable: 'ENV_FILE')]) {
                                sh """
                                    set -a
                                    source ${ENV_FILE}
                                    set +a
                                    echo "Running tests against prd environment..."
                                    ./gradlew --no-daemon check
                                """
                            }
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
                            cleanWs()
                        }
                        success {
                            echo "Prd tests passed"
                        }
                        failure {
                            echo "Prd tests failed"
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                node('java-21') {
                    echo "Pipeline completed at ${new Date()}"
                }
            }
        }
        success {
            echo "Pipeline completed successfully!"
        }
        failure {
            echo "Pipeline failed - check logs for details"
        }
        unstable {
            echo "Pipeline completed with warnings"
        }
    }
}

def checkoutGitRepo() {
    echo "Checking out tag: ${env.gitlabBranch}"

    withCredentials([string(credentialsId: 'pps-gitlab-token', variable: 'GITLAB_ACCESS_TOKEN')]) {
        def repoUrl = "${env.gitlabSourceRepoHttpUrl}".replace('https://', "https://oauth2:${GITLAB_ACCESS_TOKEN}@")

        sh """
            git init --quiet
            if ! git remote | grep -q 'code'; then
                git remote add code ${repoUrl}
            fi
            git fetch code --tags --quiet
            git checkout ${env.gitlabBranch} --quiet
            head -3 gradle.properties
            chmod +x gradlew
        """
    }
}
