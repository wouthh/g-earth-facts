# Steam-only installation and rollback

The Steam companion uses a dedicated G-Earth data directory beneath its own
Proton prefix. Run `python3 scripts/install_steam.py` from this checkout after
`python3 scripts/check.py` has produced the ZIP. Its `Extensions` directory is
a view: existing active extensions are symbolic links to the canonical shared
Bottles profile, while G-Earth Facts is a regular installed folder from the
versioned ZIP. The private receipt records the managed folder name, so a later
versioned package upgrades the previously installed folder instead of leaving
an older managed version as an unfamiliar extension. The non-Steam launcher
continues to use the canonical profile and therefore cannot discover the
Steam-only folder.

The Steam launcher passes `-Dgearth.data.dir=C:\\G-Earth\\steam-profile` to
the companion host. This override also scopes G-Earth's certificate files to
that profile. Existing certificate files are preserved and linked when present;
if none exist, the host creates a new profile-scoped certificate on first use.
The runtime checks the shared profile lock before validating and starting the
view. Unknown entries and link collisions are preserved and cause a refusal.

Installation is staged into a temporary directory, checked for path traversal,
then exchanged into the Steam profile. It changes only the dedicated Steam
view and reads the canonical targets; the launcher still takes the existing
shared profile lock before it starts G-Earth. Keep the previous version in the
private workstation ledger until the next startup has been inspected. Upgrades
write a small private pending marker after the complete backup is made; a later
install or rollback invocation restores that backup if replacement was
interrupted. To roll back, stop the Steam G-Earth session and run
`python3 scripts/install_steam.py --rollback`. The command keeps the current
folder as a rollback backup and restores the newest retained version. Do not
remove the shared extension targets, the profile lock, certificates, or other
Steam/G-Earth state.

This project records package and link verification separately from live
extension authentication. A successful installation alone is not proof that a
running G-Earth session has loaded the new folder; the next normal Steam launch
is the activation boundary.
