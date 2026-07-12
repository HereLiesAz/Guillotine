# Security Policy

Guillotine is a privacy-first, on-device video editor. Security and privacy are the point, so
reports are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.**

Instead, use GitHub's private reporting:
[**Report a vulnerability**](https://github.com/HereLiesAz/Guillotine/security/advisories/new) (Security
tab → *Report a vulnerability*). This opens a private advisory visible only to the maintainer.

Please include:

- What the issue is and where (file, screen, or tool).
- Steps to reproduce, or a proof of concept.
- The impact you think it has.
- Your platform and app version.

You can expect an initial acknowledgement within a reasonable time. Once a fix is available, the
advisory will be published with credit to you (unless you prefer to remain anonymous).

## What's in scope

Things that especially matter for this project:

- Anything that would cause footage, frames, or audio to **leave the device** without explicit,
  opt-in consent — this breaks the core promise and is treated as high severity.
- Leakage of stored secrets (encrypted BYO cloud keys) or the local MCP server's bearer token.
- The in-app / on-device **MCP server** (the local control surface) accepting unauthenticated or
  cross-origin requests it shouldn't.
- Any path that exfiltrates data or executes untrusted code from a project file, LUT, shader, or
  extension.

## Supported versions

Guillotine is pre-1.0 and moves fast; fixes land on the latest release. Please reproduce on the most
recent build before reporting.
