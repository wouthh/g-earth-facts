package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.gearthfacts.protocol.OriginsProtocol;
import io.github.wouthh.gearthfacts.protocol.ShoutComposer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShoutComposerTest {
    @Test
    void emitsExactVerifiedSevenByteShout() {
        var packet = ShoutComposer.compose("", "test123").getFirst();
        assertArrayEquals("@w@Gtest123".getBytes(StandardCharsets.ISO_8859_1), packet.toBytes());
        assertEquals("test123", ShoutComposer.decode(packet));
        assertEquals(OriginsProtocol.SHOUT_HEADER, packet.headerId());
    }

    @Test
    void calculatesLongLatin1LengthAndKeepsLiteralSpacing() {
        String fact = "wb & gm! | say no to harassment | keep habbo fun for all |";
        assertEquals(58, fact.length());
        var packet = ShoutComposer.compose("", fact).getFirst();
        assertEquals(fact, ShoutComposer.decode(packet));
        assertEquals("@w@z" + fact, new String(packet.toBytes(), StandardCharsets.ISO_8859_1));
        assertEquals(List.of("prefix  "), ShoutComposer.split("prefix  "));
    }

    @Test
    void includesPrefixWithoutAddingASeparator() {
        var packet = ShoutComposer.compose("Did you know? ", "café | yes").getFirst();
        assertEquals("Did you know? café | yes", ShoutComposer.decode(packet));
        assertEquals(
                "Did you know? café | yes",
                ShoutComposer.decode(
                        ShoutComposer.compose("", "Did you know? café | yes").getFirst()));
    }

    @Test
    void splitsAtWordsWithoutDroppingBytes() {
        String text = "alpha ".repeat(30);
        List<String> parts = ShoutComposer.split(text);
        assertTrue(parts.size() > 1 && parts.size() <= ShoutComposer.MAX_PARTS);
        assertTrue(
                parts.stream()
                        .allMatch(
                                part -> part.getBytes(StandardCharsets.ISO_8859_1).length <= 100));
        assertEquals(text, String.join("", parts));
    }

    @Test
    void rejectsUnrepresentableAndTooManyParts() {
        assertThrows(IllegalArgumentException.class, () -> ShoutComposer.compose("", "emoji 😀"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ShoutComposer.split(
                                "x"
                                        .repeat(
                                                ShoutComposer.MAX_CHUNK_BYTES
                                                                * ShoutComposer.MAX_PARTS
                                                        + 1)));
    }
}
