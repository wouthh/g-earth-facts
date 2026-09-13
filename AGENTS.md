# G-Earth Facts contributor guide

This repository owns a small Java 21 G-Earth extension for Habbo Origins. It
publishes API Ninjas facts only after an explicit Start action. The default
prefix is the empty string and prefix edits are saved immediately for the next
launch. API keys and local settings never belong in Git.

## Boundaries and invariants

- `protocol/` is the only native packet boundary. Origins uses Shockwave,
  outgoing shout header 55, and ISO-8859-1 text. Always compose typed strings
  through the pinned G-Earth API; never prepend a header to logger bytes.
- `runtime/` owns settings, HTTP, scheduling and cancellation. A scheduler has
  one generation, one in-flight request and one multipart sequence. Stop,
  disconnect and room changes cancel stale work and never replay it.
- `ui/` owns Swing controls. Swing work stays on the event-dispatch thread;
  prefix edits are persisted as typed, including whitespace and clearing.
- Tests use temporary settings, fake HTTP/local HTTP servers and synthetic
  packets. They never contact Habbo, API Ninjas, a real account or a live
  G-Earth process.
- The Steam profile is operational state outside this repository. Installation
  must preserve the shared existing extension targets, refuse collisions, use
  the existing profile lock for runtime startup, and leave non-Steam G-Earth
  unable to discover this extension.

## Validation

```text
python3 scripts/bootstrap.py
./mvnw -B clean verify
python3 scripts/check-public.py
python3 scripts/check.py
python3 scripts/test-install.py
```

`scripts/check.py` is the canonical local gate. It bootstraps the immutable
public API source, runs unit and packaging checks, validates the folder ZIP,
checks public hygiene and runs `git diff --check`. A local pass does not prove
that a live account accepted a shout.

## Delivery

Use a feature branch from `main`, normal commits and the approved GitHub
noreply identity. Inspect the complete diff, package contents and current-head
checks before opening a ready PR. Every substantive head needs a fresh review;
eyes or silence are not a clean review. Leave the PR unmerged unless explicit
merge authority is separately provided. Never commit generated JARs, Maven
caches, API keys, local settings, packet captures or workstation paths.
