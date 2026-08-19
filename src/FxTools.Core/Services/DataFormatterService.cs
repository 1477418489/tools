using System.Text;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;

namespace FxTools.Core.Services;

public static class DataFormatterService
{
    public const int MaxInputCharacters = 2_000_000;
    private static readonly JsonSerializerOptions JsonWriteOptions = new() { WriteIndented = true };

    public static string Format(string input, DataFormat format) => format switch
    {
        DataFormat.Json => FormatJson(input),
        DataFormat.Xml => FormatXml(input),
        _ => throw new ArgumentOutOfRangeException(nameof(format))
    };

    public static string FormatJson(string input)
    {
        string value = Validate(input);
        using JsonDocument document = JsonDocument.Parse(value, new JsonDocumentOptions
        {
            AllowTrailingCommas = false,
            CommentHandling = JsonCommentHandling.Disallow,
            MaxDepth = 128
        });
        return JsonSerializer.Serialize(document.RootElement, JsonWriteOptions);
    }

    public static string FormatXml(string input)
    {
        string value = Validate(input);
        XmlReaderSettings readerSettings = new()
        {
            DtdProcessing = DtdProcessing.Prohibit,
            XmlResolver = null,
            MaxCharactersInDocument = MaxInputCharacters,
            MaxCharactersFromEntities = 0,
            IgnoreWhitespace = true
        };
        using StringReader textReader = new(value);
        using XmlReader reader = XmlReader.Create(textReader, readerSettings);
        XDocument document = XDocument.Load(reader, LoadOptions.PreserveWhitespace);

        StringBuilder output = new();
        XmlWriterSettings writerSettings = new()
        {
            Indent = true,
            IndentChars = "    ",
            NewLineChars = Environment.NewLine,
            NewLineHandling = NewLineHandling.Replace,
            OmitXmlDeclaration = false,
            Encoding = new UTF8Encoding(false)
        };
        using (XmlWriter writer = XmlWriter.Create(output, writerSettings))
        {
            document.Save(writer);
        }
        return output.ToString();
    }

    private static string Validate(string input)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(input);
        if (input.Length > MaxInputCharacters)
        {
            throw new ArgumentException("输入不能超过 200 万字符。", nameof(input));
        }
        return input.Trim();
    }
}

public enum DataFormat
{
    Json,
    Xml
}
