package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

public final class VotProto {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private VotProto() {}

    public static byte[] encodeTranslateRequest(String url, double durationSec, String srcLang, String dstLang) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 3, url);          // url
        writeBool(out, 5, true);           // firstRequest
        writeDouble(out, 6, durationSec);  // duration
        writeVarintField(out, 7, 1);       // unknown0
        writeString(out, 8, srcLang);      // language
        // forceSourceLang(9) = false, unknown1(10) = 0, wasStream(13) = false -> пропускаем
        writeString(out, 14, dstLang);     // responseLanguage
        writeVarintField(out, 15, 1);      // unknown2
        writeVarintField(out, 16, 2);      // unknown3
        // bypassCache(17), useLivelyVoice(18), videoTitle(19) -> пропускаем
        return out.toByteArray();
    }

    public static VotResult decodeTranslateResponse(byte[] data) {
        VotResult result = new VotResult();
        int pos = 0;
        while (pos < data.length) {
            long[] tag = readVarint(data, pos);
            pos = (int) tag[1];
            int field = (int) (tag[0] >>> 3);
            int wireType = (int) (tag[0] & 0x7);

            if (wireType == 0) { // varint
                long[] v = readVarint(data, pos);
                pos = (int) v[1];
                if (field == 4) {
                    result.status = (int) v[0];
                } else if (field == 5) {
                    result.remainingSec = (int) v[0];
                }
            } else if (wireType == 1) { // 64-bit
                pos += 8;
            } else if (wireType == 2) { // length-delimited
                long[] len = readVarint(data, pos);
                pos = (int) len[1];
                int size = (int) len[0];
                if (size < 0 || pos + size > data.length) {
                    throw new IllegalArgumentException("truncated VOT response");
                }
                String value = new String(data, pos, size, UTF8);
                if (field == 1) {
                    result.audioUrl = value;
                } else if (field == 7) {
                    result.translationId = value;
                } else if (field == 8) {
                    result.detectedLanguage = value;
                } else if (field == 9) {
                    result.message = value;
                }
                pos += size;
            } else if (wireType == 5) { // 32-bit
                pos += 4;
            } else {
                throw new IllegalArgumentException("Unsupported wire type: " + wireType);
            }
        }
        return result;
    }

    /** @return [значение, новая позиция] */
    private static long[] readVarint(byte[] data, int pos) {
        long value = 0;
        int shift = 0;
        while (true) {
            if (pos >= data.length) {
                throw new IllegalArgumentException("truncated VOT response");
            }
            int b = data[pos++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return new long[] {value, pos};
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, (field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeTag(out, field, 0);
        writeVarint(out, value);
    }

    private static void writeBool(ByteArrayOutputStream out, int field, boolean value) {
        writeVarintField(out, field, value ? 1 : 0);
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(UTF8);
        writeTag(out, field, 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeDouble(ByteArrayOutputStream out, int field, double value) {
        writeTag(out, field, 1);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) {
            out.write((int) ((bits >>> (i * 8)) & 0xFF));
        }
    }
}
