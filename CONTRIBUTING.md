# Development flow

1. Open a GitHub Issue for a feature or bug. State the user-facing result and how it will be checked. Assign the target release milestone.
2. Work on a short-lived branch. Keep provider parsing, calculations, and UI in their existing modules.
3. Run `./gradlew test :app-desktop:compileKotlin` with JDK 21. Add tests for changed calculations, provider fields, migrations, and alert behavior.
4. Open a pull request linked to the issue. CI is configured to run the same checks on macOS, Windows, and Linux. Require a successful run before merging to `main`.
5. For a release, complete the roadmap checks on real machines, update `CHANGELOG.md`, then tag `vX.Y.Z`. Native installers, signing, and release uploads are manual until tested.

Never add provider keys, local databases, or unredacted provider responses to issues or commits.
