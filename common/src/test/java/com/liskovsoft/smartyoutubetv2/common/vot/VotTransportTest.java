package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import java.nio.charset.Charset;
import static org.junit.Assert.*;

public class VotTransportTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void errorMessageIncludesBodySnippetAndTruncatesLongBody() {
        byte[] shortBody = "signature rejected".getBytes(UTF8);
        String shortMessage = VotTransport.OkHttpVotTransport.buildErrorMessage(403, shortBody);

        assertTrue(shortMessage.contains("403"));
        assertTrue(shortMessage.contains("signature rejected"));

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longText.append('a');
        }
        byte[] longBody = longText.toString().getBytes(UTF8);
        String longMessage = VotTransport.OkHttpVotTransport.buildErrorMessage(500, longBody);

        assertTrue(longMessage.length() < longText.length());
        assertFalse(longMessage.contains(longText.toString()));
    }

    @Test
    public void errorMessageHandlesEmptyBody() {
        String message = VotTransport.OkHttpVotTransport.buildErrorMessage(500, null);

        assertTrue(message.contains("500"));
    }
}
