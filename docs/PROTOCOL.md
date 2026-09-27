# Origins shout protocol

The extension uses the public G-Earth API at revision
`b993d5ba0b23ab5644633abb8074229d1cb53b71`. `scripts/bootstrap.py` verifies the
archive checksum before building the API dependency. No private VM-control
source is copied into this project.

For an Origins/Shockwave connection, an outgoing shout is built as:

```java
HPacket packet = HPacketFormat.WEDGIE_OUTGOING.createPacket(55);
packet.appendString(text, StandardCharsets.ISO_8859_1);
```

Header 55 is `@w`. `appendString` writes the two-byte Shockwave string length
and then the Latin-1 bytes. Therefore `test123` becomes the exact 11-byte
sequence `@w@Gtest123`: two header bytes, two length bytes for seven text
bytes, and seven text bytes. A 58-byte text field uses `@z` as its length
prefix. The extension never edits a complete logger display or adds another
header to it.

The UI's 100-byte chunk policy is independent of the protocol's 4095-byte
field limit. The full `prefix + fact` text is split first, with every original
character retained; each resulting chunk is then composed separately. At most
16 chunks are allowed. A character that cannot be encoded in ISO-8859-1 is a
sanitized failure, not a replacement with `?`.

Room context is accepted only from incoming header 69, whose verified Origins
body is the raw `model roomId` form. Header 53 (`QUIT`) and 59 (`GOTOFLAT`) on
the outgoing side clear the current room. A valid Origins connection requires
both `HClient.SHOCKWAVE` and a `game-o*.habbo.com` host.

The extension API's `sendToServer` return value means that G-Earth accepted the
extension send frame. It does not mean that Habbo accepted or displayed the
shout.
