package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MessageEncoderDecoderImpl implements MessageEncoderDecoder<String> {

    private List<Byte> bytes = new ArrayList<>();

    @Override
    public byte[] encode(String message) {
        return (message + '\u0000').getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String decodeNextByte(byte nextByte) {
        if (nextByte == '\u0000') {
            byte[] resultBytes = new byte[bytes.size()];
            for (int i = 0; i < bytes.size(); i++) {
                resultBytes[i] = bytes.get(i);
            }
            String result = new String(resultBytes, StandardCharsets.UTF_8);
            bytes.clear();
            return result;
        }

        bytes.add(nextByte);
        return null;
    }
        }
