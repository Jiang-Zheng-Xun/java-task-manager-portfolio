package io.github.jiangzhengxun.taskmanager.evidence.domain;

public final class EvidenceContent {

    public static final int MAX_CODE_POINTS = 2000;
    public static final String NOT_STRING = "content must be a string";
    public static final String BLANK = "content must not be blank";
    public static final String TOO_LONG = "content must not exceed 2000 code points";
    public static final String UNSUPPORTED = "content contains unsupported characters";

    private EvidenceContent() {
    }

    /** Rejects NUL / unpaired surrogates, trims Unicode whitespace by code point, enforces length. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(NOT_STRING);
        }
        int length = raw.length();
        for (int i = 0; i < length; i++) {
            char c = raw.charAt(i);
            if (c == '\u0000') {
                throw new IllegalArgumentException(UNSUPPORTED);
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(raw.charAt(i + 1))) {
                    throw new IllegalArgumentException(UNSUPPORTED);
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(UNSUPPORTED);
            }
        }
        int start = 0;
        while (start < length) {
            int cp = raw.codePointAt(start);
            if (!isTrimmable(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        int end = length;
        while (end > start) {
            int cp = raw.codePointBefore(end);
            if (!isTrimmable(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        if (start == end) {
            throw new IllegalArgumentException(BLANK);
        }
        String trimmed = raw.substring(start, end);
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException(TOO_LONG);
        }
        return trimmed;
    }

    private static boolean isTrimmable(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
