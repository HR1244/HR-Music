# Setup Instructions

This document provides instructions for setting up HR Music for development.

## Prerequisites

- Android Studio (latest version recommended)
- Android SDK 37.0 (`platforms;android-37.0`)
- JDK 21
- Git

## Initial Setup

### 1. Clone the Repository

```bash
git clone https://github.com/HR1244/HR-Music.git
cd HR-Music
```

### 2. Configure Local Properties

Create a `local.properties` file from the template:

```bash
cp local.properties.template local.properties
```

Edit `local.properties` and set your Android SDK path:

```properties
sdk.dir=/path/to/your/android/sdk
```

**Example paths:**

- macOS: `/Users/username/Library/Android/sdk`
- Linux: `/home/username/Android/sdk`
- Windows: `C:\\Users\\username\\AppData\\Local\\Android\\sdk`

### 3. Configure Firebase (Optional)

Firebase is used for analytics and crash reporting. If you want to use these features:

1. Create a Firebase project at [Firebase Console](https://console.firebase.google.com/)
2. Register `com.hrmusic.player` as an Android app in that Firebase project. Register `com.hrmusic.player.debug` as well if you use Firebase in debug builds.
3. Download that app’s `google-services.json` file
4. Place it in the `app/` directory

5. Set the Gradle property `HR_MUSIC_ENABLE_FIREBASE=true` when you want to include your Firebase configuration.

### 4. Configure Release Signing (Optional for Development)

For signed local releases, set these environment variables. Use the same private Android signing key for every HR Music release.

```bash
# Environment variables
export KEYSTORE_PATH=/path/to/your/keystore.jks
export STORE_PASSWORD=your_store_password
export KEY_ALIAS=your_key_alias
export KEY_PASSWORD=your_key_password
```

Set `HR_MUSIC_UPDATE_REPOSITORY=OWNER/REPOSITORY` in `local.properties` or the environment if the local release should check your public GitHub repository. GitHub Actions supplies this value automatically for tagged releases.

### 5. Build the Project

Open the project in Android Studio or build from the command line.

HR Music builds as a **GMS** variant with Cast and optional Firebase support.

```bash
# Debug build
./gradlew assembleUniversalGmsDebug

# Release build (requires signing configuration)
./gradlew assembleUniversalGmsRelease
```

*(On Windows, use `.\gradlew.bat` instead of `./gradlew`)*

### Publish HR Music on GitHub

For local Last.fm testing, maintainers can set `HR_MUSIC_LASTFM_API_KEY` and `HR_MUSIC_LASTFM_API_SECRET` in their ignored `local.properties` file. These values are compiled into that local APK. The standard GitHub release ships without Last.fm access.

1. Push the contents of the Android project folder to your own public GitHub repository with Actions enabled. The repository root should contain `gradlew`, `settings.gradle.kts`, and `.github/workflows/android-build.yml`. Pushes and pull requests for `main` build a separate debug APK for testing.
2. Generate a release keystore using Android Studio or Java `keytool`. Keep a backup of the key and its passwords outside the repository.
3. Under your repository’s **Settings → Secrets and variables → Actions**, add the following secrets.
   - `HR_MUSIC_KEYSTORE_BASE64` with the complete base64-encoded keystore file on one line.
   - `HR_MUSIC_KEY_ALIAS` with the key’s alias.
   - `HR_MUSIC_KEY_PASSWORD` with the key’s password.
   - `HR_MUSIC_STORE_PASSWORD` with the keystore password.
4. Read `versionName` and `versionCode` in `app/build.gradle.kts`. When publishing an update, increase `versionCode` above every prior HR Music release. Create a tag matching `versionName`, such as `v1.4.9` for version `1.4.9`, and push it to the repository.
5. After the workflow succeeds, install the signed Universal GMS APK from your release for the first time. In **Settings → App Updates**, future versions can be downloaded and installed after Android’s approval. Debug builds install separately and use development signing.

For example, publish the current version with `git tag v1.4.9` and `git push origin v1.4.9` after committing and pushing the release source.

If publication fails and the tag already exists, open **Actions → HR Music Android → Run workflow**, select `main`, and enter the existing tag. The workflow builds the tagged source and publishes the signed release.

If you enabled a Vercel return link, set `HR_MUSIC_VERCEL_CALLBACK_HOST` as an Actions repository variable. The release job prints the APK certificate fingerprint for Android App Links. [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) · [Android app signing](https://developer.android.com/studio/publish/app-signing)

### 6. Configure AI Translation (Optional)

Echo Music supports AI-powered lyrics translation. You can configure this in **Settings -> AI Settings**.

#### Option A: Using OpenRouter (Default)

This is the recommended setup for most users.

1. Get an API Key from [OpenRouter](https://openrouter.ai/).
2. In the app, go to **Settings -> AI Settings**.
3. Ensure **Provider** is set to **OpenRouter**.
4. Enter your **API Key**.

#### Option B: Using Custom Provider

Use this for other services like OpenAI, Anthropic, or local LLMs.

1. In the app, go to **Settings -> AI Settings**.
2. Select your **Provider** (e.g., ChatGPT, Gemini, or Custom).
3. If using **Custom**, enter your provider's **Base URL**.
4. Enter your **API Key**.

## Important Files

### Confidential Files (Never commit these)

- `local.properties` - Contains your local SDK path
- `app/google-services.json` - Contains Firebase credentials
- `*.keystore` - Contains signing keys for release builds
- `gradle.properties` - May contain signing credentials

These files are already listed in `.gitignore` and should never be committed to version control.

### Template Files (Safe to commit)

- `local.properties.template` - Template for local properties
- `app/google-services.json` - Optional Firebase configuration

## Troubleshooting

### Build Fails with "SDK location not found"

Make sure you've created `local.properties` with the correct SDK path.

### Firebase-related Build Errors

If you're not using Firebase, you can still build the standard debug variant without `app/google-services.json` — Firebase features will simply be disabled:

```bash
./gradlew assembleUniversalGmsDebug
```

### Gradle Sync Issues

Try cleaning and rebuilding:

```bash
./gradlew clean
./gradlew build
```

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct and the process for submitting pull requests.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
