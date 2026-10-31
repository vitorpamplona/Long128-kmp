# Long128-kmp

`Int128` and `UInt128` for Kotlin Multiplatform, with arithmetic that reaches the hardware on every
target. Published to Maven Central; see `README.md`.

## Build and test

```bash
./gradlew :long128-core:jvmTest
./gradlew :long128-core:linuxX64Test      # macOS: macosArm64Test / macosX64Test
./verify-instructions.sh                  # proves the README's claims about CPU instruction use (needs gcc, objdump, javap)
./benchmark/run-benchmarks.sh
```

## Remotes and pull requests

The repo is on GitHub, `github.com/vitorpamplona/Long128-kmp`, which holds the canonical `main`, and
on nostr as a NIP-34 repository,
`nostr://npub1gcxzte5zlkncx26j68ez60fzkvtkm9e0vrwdcvsjakxf9mu9qewqlfnj5z/relay.ngit.dev/long128-kmp`:
a push to that remote goes to its GRASP git servers and to GitHub. A PR can go out either way: a
GitHub PR (`gh`), or a nostr proposal (`ngit`, a `pr/…` branch, reviewed on
[gitworkshop.dev](https://gitworkshop.dev/npub1gcxzte5zlkncx26j68ez60fzkvtkm9e0vrwdcvsjakxf9mu9qewqlfnj5z/relay.ngit.dev/long128-kmp)).
Remote names vary per clone, so identify them by URL. Open a PR only when the human you work for
asks, and before opening, revising or merging one by either path, use the `ngit-pr` skill.
