# G-Earth Facts

G-Earth Facts is a small Java/Swing extension for Habbo Origins. When it is
connected to Origins and you press Start, it waits ten minutes, fetches one
random fact from [API Ninjas](https://api-ninjas.com/api/facts), and shouts it.
It then repeats every ten minutes. The default prefix is empty; the Prefix
field can contain any Latin-1 text, including `Did you know? `, and is saved as
you edit it for the next launch.

The extension sends a real Origins shout with header 55. It calculates each
text field length through the G-Earth API, preserves literal spaces and `|`
characters, and splits long facts at word boundaries into at most 16 parts of
100 bytes, paced five seconds apart. Unsupported Unicode is rejected instead
of silently changing the message. Stop, disconnect, and room changes cancel
pending HTTP or multipart work.

## Setup

1. Create an API key at API Ninjas.
2. Build the folder ZIP and run `python3 scripts/install_steam.py`. The
   installer creates the Steam-only view, links the existing shared
   extensions, and the included `command.txt` selects the companion profile's
   Java 21 runtime. It refuses unfamiliar collisions.
3. Open the extension from G-Earth, enter the key, and press Save key.
4. Enter a room, choose the prefix, and press Start. Publishing is stopped on
   every extension boot until you press Start again.

The API key and prefix are stored in the Steam prefix's private Local AppData
settings. This project does not include a key, a settings export, or a
non-Steam installation. See [installation and rollback](docs/INSTALLATION.md)
for the profile layout and managed-link rules.

## Development

Requirements are a full JDK 21+, Python 3.12+, Git, and network access for the
first pinned API bootstrap. Run the complete gate with:

```sh
python3 scripts/check.py
```

The gate uses a clean synthetic/fake-host boundary. It does not open a Habbo
connection or call API Ninjas. Protocol assumptions and the exact `@w@G` /
`@z` examples are documented in [PROTOCOL.md](docs/PROTOCOL.md).

## Limits

API Ninjas quotas, HTTP availability, Habbo chat limits and server acceptance
are external. A successful `sendToServer` call proves submission to G-Earth's
extension socket only; it is not a server acknowledgement. This extension is
independently authored and is not an official Habbo, API Ninjas or G-Earth
product.
