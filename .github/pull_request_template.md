<!-- Thanks for contributing to Guillotine! Please fill this in. See CONTRIBUTING.md. -->

## What & why

<!-- What does this change do, and what problem does it solve? Link any issue: "Fixes #123". -->

## How it was tested

<!-- Commands run, platforms exercised, or "CI only" if you couldn't build locally. -->

## Cross-platform note

<!-- If this touches the MCP tool surface or a platform feature, say what the OTHER platform's
     story is: wired / stubbed / follow-up. The editor core lives in :shared; :app (Android) and
     :desktop implement the seam. -->

## Checklist

- [ ] My commits are **signed off** (`git commit -s`) — this certifies the DCO and, for this
      project, my agreement to the [CLA](../CLA.md).
- [ ] The change keeps the **on-device invariant**: frame/audio analysis stays on-device; any cloud
      path is opt-in and exchanges only text, never media.
- [ ] **No faked capabilities** — anything not runnable on-device is an honest stub, not a silent
      cloud call or mocked result.
- [ ] I matched the surrounding code style and didn't reformat unrelated code.
