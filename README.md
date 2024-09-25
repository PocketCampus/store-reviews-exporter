# Store reviews exporter

This repository contains a tool that exports all Apple App Store and Google Play Store reviews for specified apps into a single Google Spreadsheet, with support for delegated accounts.

## How to use

You will first need to setup API keys on all the Google Play Store and Apple App Store Connect accounts on which your apps are published and create a dedicate Google Spreadsheet.

### Google service account

We will create a Google service account which will retrieve reviews API and write to the Google spreadsheet on our behalf. Go to the [Google Cloud console](https://console.cloud.google.com/) and select your relevant project, or [create a new one](https://console.cloud.google.com/projectcreate).

Then under [APIs & Services](https://console.cloud.google.com/apis/dashboard), enable the ["Google Play Android Developer API"](https://console.cloud.google.com/apis/api/androidpublisher.googleapis.com/) and the ["Google Sheets API"](https://console.cloud.google.com/apis/api/sheets.googleapis.com/). In the ["Credentials"](https://console.cloud.google.com/apis/credentials) tab, [create a new "Service account"](https://console.cloud.google.com/iam-admin/serviceaccounts/create) for the reviews exporter. In the settings of this service account, access the "Keys" tab and create a new key (JSON type). The private key file will automatically be downloaded.

Now that the service account has been created, we can conveniently add it to other services by using its email address `<id>@<project>.iam.gserviceaccount.com`. We will hence only need a single key for automating operations on Google services.

### Spreadsheet

Create a new Google Spreadsheet with 2 sheets named "Reviews" and "Config".

In the "Reviews" sheet, create a header row with the keys of your choosing from [ReviewsSheet.kt (`class Review`)](src/main/kotlin/ReviewsSheet.kt). For instance:

| customer | store | appId | reviewId | date | title | body | rating | author | territory | device | thumbsUpCount | thumbsDownCount | androidOsVersion | appVersionCode | appVersionName | deviceProductName | deviceManufacturer | deviceClass | screenWidthPx | screenHeightPx | nativePlatform | screenDensityDpi | glEsVersion | cpuModel | cpuMake | ramMb | replyText | replyDate | misc | reviewLink |
|----------|-------|-------|----------|------|-------|------|--------|--------|-----------|--------|---------------|-----------------|------------------|----------------|----------------|-------------------|--------------------|-------------|---------------|----------------|----------------|------------------|-------------|----------|---------|-------|-----------|-----------|------|------------|

In the "Config sheet", create a header row with all keys from [Config.kt (`class ConfigSheet enum class Headers`)](src/main/kotlin/Config.kt):

| CustomerName | GooglePlayStorePackageName | AppleAppStoreResourceId | GooglePlayStoreReviewsReportsBucketUri | AppleAppStoreConnectIssuerId | AppleAppStoreKeyId |
|--------------|----------------------------|-------------------------|----------------------------------------|------------------------------|--------------------|

Then "Share" the spreadsheet with the service account by adding the e-mail address from above (of the shape `<id>@<project>.iam.gserviceaccount.com`) as a user with "Editor" rights. Save the ID of the spreadsheet (the long string in the URL before `/edit`).

### Google Play

For each app, access a Google Play Store account which holds admin rights on it and perform the following steps. If you do not hold admin rights on the Google Play Store account for your app (e.g. it belongs to a customer), you will need to forward these instructions to them.

1. Sign-in to the Google Play Console (https://play.google.com/console)
2. Copy the package name(s) of the desired app(s) (reverse domain name) and paste it (them) in an empty row of the "Config" sheet of the spreadsheet (at column "GooglePlayStorePackageName")
3. Select the “Users and permissions” tab
4. In the drop-down menu select “Invite new users”
5. In the “email address” field, fill-in the service account e-mail address from above (of the shape `<id>@<project>.iam.gserviceaccount.com`). Do not set an expiry date.
6. In the “Account permissions” tab, select the following: "App access > view app information and download bulk reports (read-only)" and "App access > View app quality information (read-only)". Leave all other permissions unchecked. Then click “Invite user”. Note that this access is required to access review reports older than 1 week (the reports are stored in a Google Cloud Storage bucket associated with the account instead). The service account should now appear in the users list.
7. In the left menu, click on “Download Reports” and then “Reviews”. Make sure that the correct app is selected in the top right selector
8. If the “Copy Cloud Storage URI” button is available, click on it and paste the resulting URI in the corresponding row of the "Config" sheet of the spreadsheet (at column "GooglePlayStoreReviewsReportsBucketUri"). Otherwise, the text “No monthly reports available” is displayed: in which case the
   Cloud Storage URI will only be available the month after your first store review.

### Apple App Store Connect

For each app, access an Apple App Store Connect account which holds admin rights on it and perform the following steps. If you do not hold admin rights on the Apple App Store Connect account for your app (e.g. it belongs to a customer), you will need to forward these instructions to them.

1. Sign-in to the Apple App Store Connect platform: https://appstoreconnect.apple.com/
2. Click on the "Apps" icon
3. Click on the desired app(s) and then "App information".
4. Copy the Apple ID and paste it (them) to the row of the spreadsheet matching its Android version or a new empty row otherwise in the "Config" sheet of the spreadsheet (column "AppleAppStoreResourceId")
5. Click on the "Users and Access" icon
6. Click on the “Integrations” tab, then “App Store Connect API” in the “Keys” menu
7. Copy your issuer ID and paste it in the corresponding row of the "Config" sheet of the spreadsheet (at column "AppleAppStoreConnectIssuerId")
8. Next to the “Active” text, click on the “+” (plus) button
9. In the “Name" field, provide some text describing the key, such as “Reviews
   Tool”. In the “Access” field, please select “Developer” only. Then click “Generate”. Note that the “Developer” access is the lowest available role in App Store Connect for the “View
   ratings and reviews” permission: https://developer.apple.com/support/roles/ (under “my apps”).
10. The key should now appear in the list. Hover over the corresponding key ID, click on “Copy
    Key ID” and paste it in the corresponding row of the "Config" sheet of the spreadsheet (at column "AppleAppStoreKeyId")
11. In the row of the key, click on “Download”. Note that you can only download the API key once. Save it for later (make sure that the file name is AuthKey_<your-key-id>.p8).

### Slack webhook

The tool will send a Slack message after processing new reviews.

1. Create a new Slack app: https://api.slack.com/apps
2. Create a new webhook that will send the message to a channel of your choosing and save the Webhook URL for later

Note that sending the message may fail when the message gets too long (e.g. if it contains long reviews). Slack arbitrarily cuts off the message at around 20 blocks or 2958 characters in a text block but since there is no official documentation for it, the reviews exporter does not enforce any restriction (see https://stackoverflow.com/questions/60344831/slack-api-invalid-block).

### Running the tool

Make sure that you have a JDK distribution installed (the tool has been tested successfully on `openjdk 21.0.1`).

`cd` to the root of this repository and build the application:

```shell
# linux / macos (on windows replace gradlew with gradlew.bat)
./gradlew build
```

Then run the exporter with the following command (replace the argument values by your own from above):

```shell
# linux / macos (on windows replace gradlew with gradlew.bat)
./gradlew run --args="--slackWebhook https://hooks.slack.com/<your-url> --googleSpreadsheetId <google-spreadsheet-id> --googlePrivateKeyPath 'path/to/google-key-file.json' --applePrivateKeyPath 'path/to/AuthKey_<keyid1>.p8' # repeat --applePrivateKeyPath for all your Apple keys"
```

#### List of command line arguments:

| Name                     | Description                                                                                                                                                                                                   |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--slackWebhook`         | The Slack webhook URL to which the summary message will be sent for the Slack app created earlier                                                                                                             |
| `--googleSpreadsheetId`  | The Google Spreadsheet ID of the spreadsheet created earlier which contains your "Config" sheet with parameters and "Reviews" sheet where the reviews will be written                                         |
| `--googlePrivateKeyPath` | The path to the Google Service Account private key (JSON format) downloaded earlier                                                                                                                           |
| `--applePrivateKeyPath`  | The path to the Apple Store Connect API private key file (p8 format) downloaded earlier for a specific publisher account. Repeat the argument as many times as the number of customer keys downloaded earlier |

### Periodic retrieval in Jenkins

For convenience, we provide a [Jenkinsfile](Jenkinsfile) pipeline to automate the periodic retrieval of new app store reviews.

1. We recommend using the [Folders plugin](https://plugins.jenkins.io/cloudbees-folder/) so that you can set up a credentials scope for the reviews exporter tool
2. Create a folder (named for instance "Store reviews exporter") for the reviews exporter tool and create credentials in it:
    - Create a "Secret file" for each Apple Store Connect API private key p8 file
    - Create a "Secret file" for the Google Service Account private key JSON file
    - Create a "Secret text" for the Slack webhook URL
    - Create a "Secret text" for the Google spreadsheet ID
3. In the folder create a "New item" (named for instance "Run store reviews exporter") of type "Pipeline"
4. Tick "Do not allow concurrent builds" and optionally "Abort previous builds"
5. Tick "Build periodically" and setup a cron schedule
6. In the "Pipeline" section, under "Definition" select "Pipeline script"
7. Copy and adapt the code from [Jenkinsfile](Jenkinsfile):
    - Update the credentials IDs in the `credentials('<id>')` calls with the values from step 2
    - List your Apple app keys and retrieve them from your credentials as well
    - Repeat the `--applePrivateKeyPath` with the variables that you just defined for each Apple Store Connect API private key file

## License

See [LICENSE](LICENSE)

```
Copyright 2024 PocketCampus

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```