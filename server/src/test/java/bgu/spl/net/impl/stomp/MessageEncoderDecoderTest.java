package bgu.spl.net.impl.stomp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for MessageEncoderDecoderImpl
 * Tests encoding and decoding of STOMP messages
 */
public class MessageEncoderDecoderTest {

    private MessageEncoderDecoderImpl encdec;

    @BeforeEach
    public void setUp() {
        encdec = new MessageEncoderDecoderImpl();
    }

    @Test
    public void testEncodeSimpleMessage() {
        String message = "CONNECT\naccept-version:1.2\n\n";
        byte[] encoded = encdec.encode(message);

        // Should add null terminator
        String result = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(result.endsWith("\0"), "Encoded message should end with null terminator");
        assertEquals(message + "\0", result, "Encoded message should match original + null terminator");
    }

    @Test
    public void testEncodeEmptyMessage() {
        String message = "";
        byte[] encoded = encdec.encode(message);

        String result = new String(encoded, StandardCharsets.UTF_8);
        assertEquals("\0", result, "Empty message should encode to just null terminator");
    }

    @Test
    public void testEncodeWithSpecialCharacters() {
        String message = "SEND\ndestination:/topic/test\n\nHello\nWorld!";
        byte[] encoded = encdec.encode(message);

        String result = new String(encoded, StandardCharsets.UTF_8);
        assertEquals(message + "\0", result, "Special characters should be preserved");
    }

    @Test
    public void testDecodeCompleteMessage() {
        String message = "CONNECT\naccept-version:1.2\n\n";
        byte[] bytes = (message + "\0").getBytes(StandardCharsets.UTF_8);

        String result = null;
        for (int i = 0; i < bytes.length; i++) {
            result = encdec.decodeNextByte(bytes[i]);
            if (i < bytes.length - 1) {
                assertNull(result, "Should return null until null terminator");
            }
        }

        assertEquals(message, result, "Decoded message should match original");
    }

    @Test
    public void testDecodeMultipleMessages() {
        String msg1 = "CONNECT";
        String msg2 = "SUBSCRIBE";

        byte[] bytes1 = (msg1 + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] bytes2 = (msg2 + "\0").getBytes(StandardCharsets.UTF_8);

        // Decode first message
        String result1 = null;
        for (byte b : bytes1) {
            result1 = encdec.decodeNextByte(b);
        }
        assertEquals(msg1, result1, "First message should be decoded correctly");

        // Decode second message
        String result2 = null;
        for (byte b : bytes2) {
            result2 = encdec.decodeNextByte(b);
        }
        assertEquals(msg2, result2, "Second message should be decoded correctly");
    }

    @Test
    public void testDecodeWithUTF8Characters() {
        String message = "SEND\ndestination:/topic/שלום\n\nהודעה בעברית";
        byte[] bytes = (message + "\0").getBytes(StandardCharsets.UTF_8);

        String result = null;
        for (byte b : bytes) {
            result = encdec.decodeNextByte(b);
        }

        assertEquals(message, result, "UTF-8 characters should be decoded correctly");
    }

    @Test
    public void testEncodeDecodeRoundTrip() {
        String original = "SEND\ndestination:/topic/test\n\nTest Body";

        // Encode
        byte[] encoded = encdec.encode(original);

        // Decode
        MessageEncoderDecoderImpl decoder = new MessageEncoderDecoderImpl();
        String result = null;
        for (byte b : encoded) {
            result = decoder.decodeNextByte(b);
        }

        assertEquals(original, result, "Round trip should preserve message");
    }

    @Test
    public void testDecodeEmptyMessage() {
        byte[] bytes = "\0".getBytes(StandardCharsets.UTF_8);

        String result = encdec.decodeNextByte(bytes[0]);
        assertEquals("", result, "Empty message should decode to empty string");
    }

    @Test
    public void testDecodePartialMessage() {
        String message = "CONNECT\naccept-version:1.2\n\n";
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        // Decode without null terminator
        for (byte b : bytes) {
            String result = encdec.decodeNextByte(b);
            assertNull(result, "Should return null without null terminator");
        }
    }

    @Test
    public void testMultipleNullTerminators() {
        byte[] bytes = "MSG1\0MSG2\0".getBytes(StandardCharsets.UTF_8);

        String result1 = null;
        String result2 = null;

        int nullCount = 0;
        for (byte b : bytes) {
            String decoded = encdec.decodeNextByte(b);
            if (decoded != null) {
                nullCount++;
                if (nullCount == 1) {
                    result1 = decoded;
                } else if (nullCount == 2) {
                    result2 = decoded;
                }
            }
        }

        assertEquals("MSG1", result1, "First message should be decoded");
        assertEquals("MSG2", result2, "Second message should be decoded");
    }
}
