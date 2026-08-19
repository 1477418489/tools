using System.Text;

namespace FxTools.Core.Services;

public static class StringTransformService
{
    public const int MaxInputCharacters = 2_000_000;

    public static string Transform(string input, StringTransform transform)
    {
        ArgumentNullException.ThrowIfNull(input);
        if (input.Length > MaxInputCharacters)
        {
            throw new ArgumentException("输入不能超过 200 万字符。", nameof(input));
        }

        return transform switch
        {
            StringTransform.RemoveWhitespace => RemoveWhitespace(input),
            StringTransform.Trim => input.Trim(),
            StringTransform.UpperCase => input.ToUpperInvariant(),
            StringTransform.LowerCase => input.ToLowerInvariant(),
            _ => throw new ArgumentOutOfRangeException(nameof(transform))
        };
    }

    private static string RemoveWhitespace(string input)
    {
        StringBuilder builder = new(input.Length);
        foreach (Rune rune in input.EnumerateRunes())
        {
            if (!Rune.IsWhiteSpace(rune))
            {
                builder.Append(rune);
            }
        }
        return builder.ToString();
    }
}

public enum StringTransform
{
    RemoveWhitespace,
    Trim,
    UpperCase,
    LowerCase
}
