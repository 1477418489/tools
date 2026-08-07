package plugin.javafxtools.service;

import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Text-oriented Base64 codec with explicit alphabet and character encoding. */
public final class Base64CodecService {
    public static final int MAX_INPUT_CHARACTERS = 1_000_000;

    public String encode(String input, Variant variant, TextEncoding encoding) {
        String value = validate(input);
        byte[] bytes = value.getBytes(encoding.charset());
        return switch (variant) {
            case STANDARD -> Base64.getEncoder().encodeToString(bytes);
            case URL_SAFE -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            case MIME -> Base64.getMimeEncoder().encodeToString(bytes);
        };
    }

    public String decode(String input, Variant variant, TextEncoding encoding) {
        String value = validate(input);
        try {
            byte[] decoded = switch (variant) {
                case STANDARD -> Base64.getDecoder().decode(removeWhitespace(value));
                case URL_SAFE -> Base64.getUrlDecoder().decode(removeWhitespace(value));
                case MIME -> decodeMime(value);
            };
            try {
                return encoding.charset().newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(decoded)).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("解码后的字节不符合所选字符编码", e);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("输入不是有效的 " + variant.displayName()
                    + " 内容", e);
        }
    }

    private static String validate(String input) {
        String value = input == null ? "" : input;
        if (value.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("输入不能超过 "
                    + MAX_INPUT_CHARACTERS / 1_000_000 + " 百万字符");
        }
        return value;
    }

    private static String removeWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private static byte[] decodeMime(String value) {
        String normalized = removeWhitespace(value);
        boolean validAlphabet = normalized.chars().allMatch(character ->
                character >= 'A' && character <= 'Z'
                        || character >= 'a' && character <= 'z'
                        || character >= '0' && character <= '9'
                        || character == '+' || character == '/' || character == '=');
        if (!validAlphabet) {
            throw new IllegalArgumentException("MIME Base64 只能包含编码字符和空白");
        }
        return Base64.getDecoder().decode(normalized);
    }

    public enum Variant {
        STANDARD("标准 Base64"),
        URL_SAFE("URL 安全 Base64"),
        MIME("MIME Base64");

        private final String displayName;

        Variant(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum TextEncoding {
        UTF_8("UTF-8", StandardCharsets.UTF_8),
        GB18030("GB18030 / GBK", Charset.forName("GB18030")),
        UTF_16LE("UTF-16 LE", StandardCharsets.UTF_16LE),
        UTF_16BE("UTF-16 BE", StandardCharsets.UTF_16BE);

        private final String displayName;
        private final Charset charset;

        TextEncoding(String displayName, Charset charset) {
            this.displayName = displayName;
            this.charset = charset;
        }

        public Charset charset() {
            return charset;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
