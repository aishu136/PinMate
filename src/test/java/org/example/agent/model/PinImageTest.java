package org.example.agent.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinImageTest {

    @Test
    void detectsSupportedFormatsFromMagicBytes() {
        assertEquals(Optional.of(PinImage.JPEG), PinImage.detectMediaType(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals(Optional.of(PinImage.PNG), PinImage.detectMediaType(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0)));
        assertEquals(Optional.of(PinImage.GIF), PinImage.detectMediaType(ascii("GIF89a....")));
        assertEquals(Optional.of(PinImage.GIF), PinImage.detectMediaType(ascii("GIF87a....")));
        assertEquals(Optional.of(PinImage.WEBP), PinImage.detectMediaType(ascii("RIFF\0\0\0\0WEBPVP8 ")));
    }

    @Test
    void rejectsOtherFiles() {
        assertTrue(PinImage.detectMediaType(ascii("%PDF-1.7")).isEmpty());
        assertTrue(PinImage.detectMediaType(ascii("RIFF\0\0\0\0WAVEfmt ")).isEmpty());
        assertTrue(PinImage.detectMediaType(new byte[0]).isEmpty());
        assertTrue(PinImage.detectMediaType(bytes(0xFF, 0xD8)).isEmpty());
    }

    @Test
    void toStringOmitsImageBytes() {
        String s = new PinImage(new byte[1000], PinImage.PNG).toString();
        assertEquals("PinImage[mediaType=image/png, bytes=1000]", s);
        assertFalse(s.contains("[B@"));
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
