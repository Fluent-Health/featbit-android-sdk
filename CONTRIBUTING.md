# Contributing

Thanks for your interest in contributing to the FeatBit Kotlin/Android client SDK.

## Reporting bugs

Open an issue describing the problem, steps to reproduce, expected vs. actual behaviour,
and the SDK / Android / Gradle versions involved. For security issues, see
[SECURITY.md](./SECURITY.md) instead — do not open a public issue.

## Proposing changes

1. Fork the repository.
2. Create a branch: `git checkout -b my-fix`.
3. Make your changes with a clear, focused commit history.
4. Make sure the build and unit tests pass (see below).
5. Open a pull request against `main`. CI must be green before review.

## Building and testing

The repository ships a Gradle wrapper and targets JDK 17.

```bash
# Unit tests (no Docker required)
./gradlew :featbit-client:testDebugUnitTest

# Assemble the library AAR
./gradlew :featbit-client:assembleRelease
```

End-to-end tests run a real FeatBit stack via Testcontainers and are gated so they never
run in the default unit pass. They require a reachable Docker daemon:

```bash
FEATBIT_E2E=1 ./gradlew :featbit-client:testDebugUnitTest --tests "co.featbit.client.e2e.*"
```

## Code style

- Kotlin official style (4-space indentation); match the conventions of the surrounding code.
- Keep the public API small and documented with KDoc; favour `suspend` functions for async work
  and synchronous reads for flag evaluation, consistent with the existing design.
- Add or update tests for any behavioural change.

## No CLA required

You do not need to sign a Contributor License Agreement. By submitting a pull request, you
agree to license your contribution under the repository's [LICENSE](./LICENSE) (Apache-2.0).
