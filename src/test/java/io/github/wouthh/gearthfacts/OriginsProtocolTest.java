package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import gearth.protocol.HPacket;
import gearth.protocol.HPacketFormat;
import gearth.protocol.connection.HClient;
import gearth.protocol.packethandler.shockwave.packets.ShockPacketIncoming;
import io.github.wouthh.gearthfacts.protocol.OriginsProtocol;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OriginsProtocolTest {
    @Test
    void recognizesOnlyOriginsShockwave() {
        assertTrue(OriginsProtocol.isOrigins("game-ous.habbo.com", HClient.SHOCKWAVE));
        assertFalse(OriginsProtocol.isOrigins("game-ous.habbo.com", HClient.FLASH));
        assertFalse(OriginsProtocol.isOrigins("game-us.habbo.com", HClient.SHOCKWAVE));
    }

    @Test
    void parsesVerifiedRoomReadyBody() {
        HPacket packet =
                new ShockPacketIncoming(69, "model 123456".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(123456, OriginsProtocol.roomId(packet).orElseThrow());
        assertTrue(
                OriginsProtocol.roomId(
                                new ShockPacketIncoming(
                                        69, "model nope".getBytes(StandardCharsets.ISO_8859_1)))
                        .isEmpty());
    }

    @Test
    void rejectsWrongPacketFormat() {
        assertTrue(
                OriginsProtocol.roomId(HPacketFormat.WEDGIE_OUTGOING.createPacket(69)).isEmpty());
    }

    @Test
    void composesLatin1ShoutsAtTheVerifiedFieldBoundary() {
        HPacket seven = OriginsProtocol.composeShout("test123");
        assertArrayEquals("@w@Gtest123".getBytes(StandardCharsets.ISO_8859_1), seven.toBytes());

        String accented = "café | déjà vu  ";
        HPacket latin1 = OriginsProtocol.composeShout(accented);
        assertEquals(accented, readShout(latin1));

        String max = "x".repeat(OriginsProtocol.MAX_FIELD_BYTES);
        HPacket maxPacket = OriginsProtocol.composeShout(max);
        assertEquals(max, readShout(maxPacket));
        assertEquals(
                "@w\u007f\u007f" + max,
                new String(maxPacket.toBytes(), StandardCharsets.ISO_8859_1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OriginsProtocol.composeShout(
                                "x".repeat(OriginsProtocol.MAX_FIELD_BYTES + 1)));
        assertThrows(
                IllegalArgumentException.class, () -> OriginsProtocol.composeShout("emoji 😀"));
    }

    private static String readShout(HPacket packet) {
        packet.resetReadIndex();
        String text = packet.readString(StandardCharsets.ISO_8859_1);
        assertEquals(1, packet.isEOF());
        return text;
    }
}
