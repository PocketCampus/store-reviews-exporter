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
            environment {
                GOOGLE_SPREADSHEET_ID = credentials('google-spreadsheet-id')
                GOOGLE_PRIVATE_KEY_PATH = credentials('google-key-file')
                SLACK_WEBHOOK = credentials('slack-webhook')
                // Colon-separated paths for secret Apple key files
                APPLE_PRIVATE_KEY_PATHS = "${credentials('apple-key-file-1')}:${credentials('apple-key-file-2')}"
            }
            steps {
                // Execute Gradle run task; sensitive credentials are read from environment variables
                sh './gradlew run --args="--googleSpreadsheetId $GOOGLE_SPREADSHEET_ID"'
            }
        }
    }
}
