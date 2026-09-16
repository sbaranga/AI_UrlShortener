package com.example.urlshortener.util;

/**
 * Converts non-negative longs to and from base62 text using the alphabet
 * {@code 0-9A-Za-z}. Codes are case sensitive: {@code aB} and {@code Ab} are different.
 */
public final class Base62Encoder {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    /** 62^10 is the largest power of the base that still fits in a long. */
    public static final int MAX_LENGTH = 10;

    private Base62Encoder() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, got " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder digits = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            digits.append(ALPHABET.charAt((int) (remaining % BASE)));
            remaining /= BASE;
        }
        return digits.reverse().toString();
    }

    /** Left-pads the encoded value with the zero digit so every code has the same width. */
    public static String encodePadded(long value, int minLength) {
        String encoded = encode(value);
        if (encoded.length() >= minLength) {
            return encoded;
        }
        StringBuilder padded = new StringBuilder(minLength);
        padded.append(String.valueOf(ALPHABET.charAt(0)).repeat(minLength - encoded.length()));
        return padded.append(encoded).toString();
    }

    public static long decode(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("not a base62 string: " + text);
            }
            value = value * BASE + digit;
        }
        return value;
    }

    /** Exclusive upper bound on the values that fit in {@code length} base62 characters. */
    public static long capacityFor(int length) {
        if (length < 1 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length must be between 1 and " + MAX_LENGTH + ", got " + length);
        }
        long capacity = 1;
        for (int i = 0; i < length; i++) {
            capacity *= BASE;
        }
        return capacity;
    }

    public static boolean isValidCode(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (ALPHABET.indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
