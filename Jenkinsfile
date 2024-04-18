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
                GOOGLE_KEY_FILE = credentials('google-key-file')
                SLACK_WEBHOOK = credentials('slack-webhook')
                /* List your secret Apple key files here, e.g.
                APPLE_KEY_FILE_AB1234C5DE = credentials('apple-key-file-AB1234C5DE')
                APPLE_KEY_FILE_FG5789H0IJ = credentials('apple-key-file-FG5789H0IJ')
                etc... */
            }
            steps {
                // Execute Gradle run task
                /* List the environment variable names defined above here by repeating the applePrivateKeyPath flag e.g.
                sh './gradlew run --args "--slackWebhook $SLACK_WEBHOOK --googleSpreadsheetId $GOOGLE_SPREADSHEET_ID --googlePrivateKeyPath \'$GOOGLE_KEY_FILE\' --applePrivateKeyPath \'$APPLE_KEY_FILE_AB1234C5DE\' --applePrivateKeyPath \'$APPLE_KEY_FILE_FG5789H0IJ\'"'
                */
                sh './gradlew run --args="--slackWebhook $SLACK_WEBHOOK --googleSpreadsheetId $GOOGLE_SPREADSHEET_ID --googlePrivateKeyPath \'$GOOGLE_KEY_FILE\' --applePrivateKeyPath \'<env-var-name>\'"'
            }
        }
    }
}
