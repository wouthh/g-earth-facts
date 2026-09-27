package io.github.wouthh.gearthfacts.protocol;

import gearth.protocol.HPacket;
import gearth.protocol.HPacketFormat;
import gearth.protocol.connection.HClient;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** Small, explicit Origins/Shockwave protocol boundary. */
public final class OriginsProtocol {
    public static final int SHOUT_HEADER = 55;
    public static final int ROOM_READY_HEADER = 69;
    public static final int QUIT_HEADER = 53;
    public static final int GOTO_FLAT_HEADER = 59;
    public static final int MAX_FIELD_BYTES = 4095;
    private static final Pattern ORIGINS_HOST =
            Pattern.compile("game-o(?:d|us|br|es)\\.habbo\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOM_MODEL = Pattern.compile("[A-Za-z0-9_*-]{1,64}");

    private OriginsProtocol() {}

    public static boolean isOrigins(String host, HClient client) {
        return client == HClient.SHOCKWAVE && host != null && ORIGINS_HOST.matcher(host).matches();
    }

    public static HPacket composeShout(String text) {
        if (text == null) throw new IllegalArgumentException("Shout text is missing");
        if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(text))
            throw new IllegalArgumentException("Shout text must be representable in Latin-1");
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length > MAX_FIELD_BYTES)
            throw new IllegalArgumentException("Shout text exceeds the Origins field limit");
        HPacket packet = HPacketFormat.WEDGIE_OUTGOING.createPacket(SHOUT_HEADER);
        packet.appendString(text, StandardCharsets.ISO_8859_1);
        return packet;
    }

    /** ROOM_READY carries a raw `model roomId` body in the verified Origins profile. */
    public static OptionalLong roomId(HPacket packet) {
        if (packet == null
                || packet.getFormat() != HPacketFormat.WEDGIE_INCOMING
                || packet.headerId() != ROOM_READY_HEADER) return OptionalLong.empty();
        byte[] bytes = packet.toBytes();
        if (bytes.length < 4) return OptionalLong.empty();
        String body = new String(bytes, 2, bytes.length - 2, StandardCharsets.ISO_8859_1).trim();
        String[] fields = body.split(" ", -1);
        if (fields.length != 2
                || !ROOM_MODEL.matcher(fields[0]).matches()
                || !fields[1].matches("[1-9][0-9]*")) return OptionalLong.empty();
        try {
            long id = Long.parseLong(fields[1]);
            return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
