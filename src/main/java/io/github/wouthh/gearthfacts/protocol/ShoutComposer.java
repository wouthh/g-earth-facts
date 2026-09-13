package io.github.wouthh.gearthfacts.protocol;

import gearth.protocol.HPacket;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Composes and splits text before it reaches the native G-Earth send boundary. */
public final class ShoutComposer {
    public static final int MAX_CHUNK_BYTES = 100;
    public static final int MAX_PARTS = 16;

    private ShoutComposer() {}

    public static List<String> split(String completeText) {
        if (completeText == null) throw new IllegalArgumentException("Text is missing");
        CharsetEncoder encoder = StandardCharsets.ISO_8859_1.newEncoder();
        if (!encoder.canEncode(completeText))
            throw new IllegalArgumentException("Text must be representable in Latin-1");
        List<String> parts = new ArrayList<>();
        int offset = 0;
        while (offset < completeText.length()) {
            int remaining = completeText.length() - offset;
            if (remaining <= MAX_CHUNK_BYTES) {
                parts.add(completeText.substring(offset));
                break;
            }
            int hardEnd = Math.min(offset + MAX_CHUNK_BYTES, completeText.length());
            int boundary = -1;
            for (int i = offset; i < hardEnd; i++) {
                if (Character.isWhitespace(completeText.charAt(i))) boundary = i;
            }
            int end = boundary > offset ? boundary + 1 : hardEnd;
            int remainingParts =
                    (completeText.length() - end + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
            if (end != hardEnd && parts.size() + 1 + remainingParts > MAX_PARTS) end = hardEnd;
            if (end <= offset) end = hardEnd;
            parts.add(completeText.substring(offset, end));
            offset = end;
        }
        if (parts.size() > MAX_PARTS)
            throw new IllegalArgumentException("Text exceeds the 16-part shout limit");
        for (String part : parts) {
            if (part.isEmpty()
                    || part.getBytes(StandardCharsets.ISO_8859_1).length > MAX_CHUNK_BYTES)
                throw new IllegalArgumentException("Invalid shout part");
        }
        return List.copyOf(parts);
    }

    public static List<HPacket> compose(String prefix, String fact) {
        String complete = (prefix == null ? "" : prefix) + (fact == null ? "" : fact);
        if (fact == null || fact.isBlank()) throw new IllegalArgumentException("Fact is empty");
        List<HPacket> packets = new ArrayList<>();
        for (String part : split(complete)) packets.add(OriginsProtocol.composeShout(part));
        return List.copyOf(packets);
    }

    public static String decode(HPacket packet) {
        if (packet == null
                || packet.getFormat() != gearth.protocol.HPacketFormat.WEDGIE_OUTGOING
                || packet.headerId() != OriginsProtocol.SHOUT_HEADER)
            throw new IllegalArgumentException("Not an Origins shout");
        packet.resetReadIndex();
        String text = packet.readString(StandardCharsets.ISO_8859_1);
        if (packet.isEOF() != 1) throw new IllegalArgumentException("Shout contains unread bytes");
        return text;
    }
}
