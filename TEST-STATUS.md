# Test status — AegisAC 1.7.2

- ZIP contents and CRC: validated after packaging.
- YAML syntax: `plugin.yml`, `config.yml`, and both Paper dupe-hardening examples passed a local YAML parse.
- Java parser phase: no syntax-pattern diagnostics observed; javac could not resolve Bukkit/Paper or PacketEvents types because their API artifacts are unavailable here.
- Automatic DupeGuard uses a shared per-tick identity registry so overlapping transfers are not rolled back from stale snapshots.
- Unknown/legacy serial provenance requires two consecutive clean background scans before being trusted.
- Full Gradle build: not run because Gradle and Paper API / PacketEvents artifacts are not present in the environment.
- Runtime Paper integration, synthetic dupe rollback tests, TPS/performance tests, and 1.21.11/26.x cross-version tests: not run.

Do not treat source validation as proof of runtime behavior. Build and test on a disposable Paper staging server before replacing a production plugin.


## 1.7.2 admin flight change
- Source-level review: command permission gate uses `aegis.admin` (default OP).
- Source-level review: only Survival mode is accepted; game mode is never changed.
- Source-level review: flight grant is cleared on disconnect, plugin disable, and leaving Survival; respawn restores allow-flight if the grant remains active.
- Not runtime-tested on a live Paper server in this environment.
