package com.liskovsoft.smartyoutubetv2.common.vot;

public final class VotHex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private VotHex() {}

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(DIGITS[(b >> 4) & 0xF]).append(DIGITS[b & 0xF]);
        }
        return sb.toString();
    }

    public static byte[] decode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
