# Troubleshooting

- **“Unsupported client”**: the extension is restricted to an Origins
  Shockwave connection. Verify that G-Earth connected to a `game-o*.habbo.com`
  host, not Flash, Unity or Nitro.
- **“Enter a room before starting”**: start the extension before entering a
  room, or re-enter after attaching it so header 69 supplies a verified room
  ID.
- **“API key rejected”**: save a current API Ninjas key. A 401/403 stops the
  scheduler and requires another explicit Start.
- **No shout after a room change**: this is deliberate. The old request and
  unsent parts are cancelled, then a fresh ten-minute countdown starts in the
  new room.
- **Unicode fact skipped**: Origins Shockwave text is encoded as Latin-1 in
  this profile. The extension refuses characters it cannot represent rather
  than corrupting the fact.
- **Steam launch refuses the profile**: inspect the private launcher log and
  the managed-link inventory. Do not delete the profile lock or replace an
  unfamiliar entry; preserve it for review and restore the previous package if
  necessary.
