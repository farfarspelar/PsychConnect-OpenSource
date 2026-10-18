# Contributing

Thanks for helping improve PsychConnect. Bug reports, gameplay ideas, translations, accessibility feedback, Android device testing, and code contributions are welcome.

## Before you start

- Search existing issues and pull requests for related work.
- For a significant change, open an issue first so its scope can be discussed.
- Keep gameplay, haptics, networking, UI, and persistence changes focused and testable.
- Do not add analytics, tracking, ads, mandatory accounts, or monetization without a separate project decision.
- Do not submit credentials, personal data, copyrighted assets, or files you do not have permission to contribute.
- The repository's MIT license applies to original code, not the excluded artwork or audio. Submit only artwork/audio for which the project has a clear redistribution license, and record its attribution.

## Report bugs and suggest features

Use GitHub Issues in the existing PsychConnect repository. Include the app/server version, Android version/device model where relevant, reproduction steps, and sanitized logs. Remove room codes, profile credentials, recovery codes, database URLs, and personal information from screenshots and logs.

For a security concern, follow [SECURITY.md](SECURITY.md) rather than posting exploit details publicly.

## Pull requests

1. Branch from `main` and keep the change focused.
2. Explain the user-visible behavior and any protocol, database, or compatibility impact.
3. Add or update tests for changed logic where practical.
4. Update English documentation, migrations, and configuration examples when needed.
5. Run the relevant checks:
   - `./gradlew testDebugUnitTest assembleDebug`
   - `npm --prefix server ci`
   - `npm --prefix server test`
6. Never include generated build output, signing files, real environment configuration, or private credentials.

By submitting a code contribution, you confirm that you have the right to contribute it and that it may be distributed under the project's MIT license. No separate Contributor License Agreement is currently required. Contributions are reviewed; acceptance is not guaranteed.
