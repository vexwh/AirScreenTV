# Security Policy

## Reporting a vulnerability

Please do not open a public issue for security-sensitive reports. Use GitHub's
private **Security advisory** feature for this repository, or contact the
maintainer through the repository profile.

Include:

- affected version/commit
- steps to reproduce
- impact
- any suggested fix

## Secrets

This repository must never contain:

- release keystores or keystore passwords
- Apple pairing keys (airplay.pem)
- API tokens or credentials
- personal IP addresses, Wi-Fi names, or device identifiers

keystore.properties, keystore/, local.properties, APKs, logs, and pairing keys
are gitignored. If a secret is committed accidentally, rotate it immediately and
remove it from history.
