# Configuration

Configuration is edited in the Swing window opened from G-Earth. The API key
field is masked; enter the key and choose **Save key**. The extension never
stores a key in the repository or in a package artifact.

The **Prefix** field is saved on every edit, including clearing the field. Its
default value is the empty string, so a fact is sent exactly as returned by
API Ninjas. To add the familiar wording, enter this exact prefix:

```text
Did you know? 
```

The trailing space is part of the value. Prefix text is combined with the fact
without inserting a separator. Only Latin-1 text can be sent by the Origins
Shockwave profile; unsupported characters are skipped with a sanitized status
message. A running fact sequence keeps the prefix it captured when its request
started, while later sequences use the newest saved value.

Settings are kept in the Steam Proton profile's private Local AppData as
`G-Earth Facts/settings.properties`. The extension restores the saved values on
launch but always starts stopped. Enter an Origins room, then press **Start**
to begin the ten-minute countdown. **Stop**, disconnecting, leaving the room, or
closing the window cancels pending work.
