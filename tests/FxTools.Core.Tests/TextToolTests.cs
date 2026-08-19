using System.Text.Json;
using System.Xml;
using FxTools.Core.Infrastructure;
using FxTools.Core.Services;

namespace FxTools.Core.Tests;

public sealed class TextToolTests
{
    [Theory]
    [InlineData(Base64Variant.Standard)]
    [InlineData(Base64Variant.UrlSafe)]
    [InlineData(Base64Variant.Mime)]
    public void Base64RoundTripsUnicode(Base64Variant variant)
    {
        const string source = "FxTools 中文 \U0001F680";
        string encoded = Base64CodecService.Encode(source, variant, TextEncoding.Utf8);
        Assert.Equal(source, Base64CodecService.Decode(encoded, variant, TextEncoding.Utf8));
    }

    [Fact]
    public void Base64EnforcesInputLimit()
    {
        string oversized = new('x', MemoryLimits.Base64InputCharacters + 1);
        Assert.Throws<ArgumentException>(() =>
            Base64CodecService.Encode(oversized, Base64Variant.Standard, TextEncoding.Utf8));
    }

    [Fact]
    public void JsonFormatterKeepsLargeIntegerExact()
    {
        string result = DataFormatterService.FormatJson("{\"id\":123456789012345678901234567890}");
        using JsonDocument document = JsonDocument.Parse(result);
        Assert.Equal("123456789012345678901234567890",
            document.RootElement.GetProperty("id").GetRawText());
    }

    [Fact]
    public void XmlFormatterRejectsDoctype()
    {
        Assert.Throws<XmlException>(() => DataFormatterService.FormatXml(
            "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///c:/windows/win.ini'>]><x>&e;</x>"));
    }

    [Fact]
    public void StringToolRemovesUnicodeWhitespace()
    {
        Assert.Equal("abc", StringTransformService.Transform(
            " a\u3000b\r\nc ", StringTransform.RemoveWhitespace));
    }
}
