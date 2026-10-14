# Security policy

## Supported versions

Kache is pre-1.0. Security fixes land on `main` and are released as patch versions of the
most recent published line. Only that line is supported.

| Version | Supported |
|---|---|
| 0.1.x | yes |
| anything older | no |

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

## Scope

Kache is a cache. Three properties are deliberate design decisions rather than defects, and
are documented in the [security considerations](README.md#security-considerations) section:

- **Payloads are not encrypted.** The library writes what the serializer produced. An
  application that caches secrets is responsible for wrapping the serializer.
- **Records are not authenticated.** Nothing detects a value edited on disk, and a record's
  own timestamps decide when it expires. The storage directory is treated as untrusted input:
  a record that cannot be parsed becomes a miss, not an error.
- **Keys are stored, reversibly, in file names** in the file backend. Keys are identifiers,
  not secrets.

Reports are still welcome if you believe one of these is exploitable in a way the
documentation does not cover, or if the documentation overstates the protection.

Out of scope: vulnerabilities in dependencies (report those upstream), in a consuming
application's own use of the library, and in the sample app's demo data.
