using System.Text;
using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public static class Base64CodecService
{
    static Base64CodecService() => Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);

    public static string Encode(string? input, Base64Variant variant, TextEncoding encoding)
    {
        string value = Validate(input);
        byte[] bytes = GetEncoding(encoding).GetBytes(value);
        string encoded = Convert.ToBase64String(
            bytes,
            variant == Base64Variant.Mime
                ? Base64FormattingOptions.InsertLineBreaks
                : Base64FormattingOptions.None);
        return variant == Base64Variant.UrlSafe
            ? encoded.TrimEnd('=').Replace('+', '-').Replace('/', '_')
            : encoded;
    }

    public static string Decode(string? input, Base64Variant variant, TextEncoding encoding)
    {
        string normalized = RemoveWhitespace(Validate(input));
        try
        {
            if (variant == Base64Variant.UrlSafe)
            {
                if (normalized.Any(character =>
                        !(char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '=')))
                {
                    throw new FormatException();
                }
                normalized = normalized.Replace('-', '+').Replace('_', '/');
                normalized = normalized.TrimEnd('=');
                normalized += (normalized.Length % 4) switch
                {
                    0 => string.Empty,
                    2 => "==",
                    3 => "=",
                    _ => throw new FormatException()
                };
            }
            else if (normalized.Any(character =>
                         !(char.IsAsciiLetterOrDigit(character) || character is '+' or '/' or '=')))
            {
                throw new FormatException();
            }

            byte[] decoded = Convert.FromBase64String(normalized);
            return GetEncoding(encoding).GetString(decoded);
        }
        catch (Exception exception) when (exception is FormatException or DecoderFallbackException)
        {
            throw new ArgumentException(
                $"输入不是有效的 {DisplayName(variant)} 内容，或解码结果不符合所选字符编码。",
                nameof(input),
                exception);
        }
    }

    private static string Validate(string? input)
    {
        string value = input ?? string.Empty;
        if (value.Length > MemoryLimits.Base64InputCharacters)
        {
            throw new ArgumentException("输入不能超过 100 万字符。", nameof(input));
        }
        return value;
    }

    private static string RemoveWhitespace(string value)
    {
        if (!value.Any(char.IsWhiteSpace))
        {
            return value;
        }

        StringBuilder builder = new(value.Length);
        foreach (char character in value)
        {
            if (!char.IsWhiteSpace(character))
            {
                builder.Append(character);
            }
        }
        return builder.ToString();
    }

    private static Encoding GetEncoding(TextEncoding encoding) => encoding switch
    {
        TextEncoding.Utf8 => new UTF8Encoding(false, true),
        TextEncoding.Gb18030 => Encoding.GetEncoding(
            "GB18030", EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback),
        TextEncoding.Utf16LittleEndian => new UnicodeEncoding(false, false, true),
        TextEncoding.Utf16BigEndian => new UnicodeEncoding(true, false, true),
        _ => throw new ArgumentOutOfRangeException(nameof(encoding))
    };

    public static string DisplayName(Base64Variant variant) => variant switch
    {
        Base64Variant.Standard => "标准 Base64",
        Base64Variant.UrlSafe => "URL 安全 Base64",
        Base64Variant.Mime => "MIME Base64",
        _ => throw new ArgumentOutOfRangeException(nameof(variant))
    };
}

public enum Base64Variant
{
    Standard,
    UrlSafe,
    Mime
}

public enum TextEncoding
{
    Utf8,
    Gb18030,
    Utf16LittleEndian,
    Utf16BigEndian
}
