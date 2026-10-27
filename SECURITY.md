# Security policy

## Supported versions

Kache is 1.x. Security fixes land on `main` and are released as patch versions of the most
recent published line. Only that line is supported.

| Version | Supported |
|---|---|
| 1.0.x | yes |
| anything older | no |

`1.0.0` is published to Maven Central, and every finding from the pre-release audit is fixed in
it. Reports against `main` are welcome and are treated as reports against the next release.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting: open the repository's **Security** tab
and choose **Report a vulnerability**. That channel is visible only to you and the
maintainers.

Do **not** open a public issue for a security problem, and do not include an exploit in a
public discussion.

A useful report includes:

- what the library does that it should not, or fails to do that it should;
- the affected module (`:cache-core`, `:cache-storage`, `:cache-store-room`,
  `:cache-android`) and version;
- a minimal reproduction — a failing test is ideal;
- the impact you believe it has on an application using the library.

## What to expect

- Acknowledgement within a few days.
- An assessment, including whether it is a vulnerability or documented behaviour, and why.
- A fix on `main`, a patch release, and a note in the release description. Credit in the
  advisory unless you prefer otherwise.

## Supply chain

Every build input the project resolves — jars, klibs and `.pom`/`.module` descriptors — is
checked against a recorded SHA-256 in `gradle/verification-metadata.xml`. A dependency bump
fails the build until the metadata is regenerated, which puts the new checksum in a diff a
reviewer can see. Documentation artifacts (`-sources`, `-javadoc` and their variants) are
trusted by name instead: they never reach a compile or runtime classpath and exist only for IDE
indexing. That exception is deliberate and is stated in the file next to the rules.

These are checksums, not signatures: they prove an artifact has not changed, not who produced
it. Origin is covered separately — release artifacts are signed with the maintainer's GPG key
(fingerprint `DA04787CEA27F2D6`, published on `keyserver.ubuntu.com`), and every uploaded file
has a `.asc` beside it:

```bash
gpg --recv-keys DA04787CEA27F2D6
gpg --verify cache-core-1.0.0.pom.asc cache-core-1.0.0.pom
```

## Scope

Kache is a cache. Three properties are deliberate design decisions rather than defects, and
are documented in the [security considerations](README.md#security-considerations) section:

- **Payloads are not encrypted.** The library writes what the serializer produced. An
  application that caches secrets is responsible for wrapping the serializer.
- **Records are not authenticated.** Nothing detects a value edited on disk, and a record's
  own timestamps decide when it expires. The storage directory is treated as untrusted input:
  a record that cannot be parsed becomes a miss, not an error.
- **Keys are stored, reversibly, in file names** in the file backend. Keys are identifiers,
  not secrets. The encoding is reversible and collision-free, so distinct keys never share a
  record — but a directory listing is enough to recover a key.
- **Cache directories are writable by the application** and, on Android, may be purged at any
  time. Losing the cache is expected behaviour, not data loss.

Reports are still welcome if you believe one of these is exploitable in a way the
documentation does not cover, or if the documentation overstates the protection.

Out of scope: vulnerabilities in dependencies (report those upstream), in a consuming
application's own use of the library, and in the sample app's demo data.
