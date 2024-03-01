pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                // Checkout source code from Git repository
                git url: 'https://github.com/PocketCampus/store-reviews-exporter.git', branch: 'main'
            }
        }

        stage('Build') {
            steps {
                // Execute Gradle build
                sh './gradlew build'
            }
        }

        stage('Run') {
            steps {
                // Execute Gradle run task
                sh './gradlew run'
            }
        }
    }
}