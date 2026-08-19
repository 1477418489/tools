using System.Buffers.Binary;
using System.Net;
using FxTools.Core.Services;

namespace FxTools.Core.Tests;

public sealed class NetworkServiceTests
{
    [Fact]
    public void HeaderParserPreservesColonInValue()
    {
        IReadOnlyList<KeyValuePair<string, string>> headers =
            HttpRequestService.ParseHeaders("Authorization: Bearer a:b\nX-Test: ok");
        Assert.Equal("Bearer a:b", headers[0].Value);
        Assert.Equal("X-Test", headers[1].Key);
    }

    [Fact]
    public void HeaderParserRejectsMalformedLine()
    {
        Assert.Throws<ArgumentException>(() => HttpRequestService.ParseHeaders("broken"));
    }

    [Fact]
    public void ResponseFormatterPrettyPrintsJson()
    {
        string result = HttpRequestService.FormatResponseBody("{\"ok\":true}", ResponseFormat.PrettyJson);
        Assert.Contains(Environment.NewLine, result, StringComparison.Ordinal);
    }

    [Fact]
    public void NetworkStatisticsClassifiesFailures()
    {
        NetworkProbeResult[] samples =
        [
            new(DateTimeOffset.Now, false, TimeSpan.FromSeconds(1), string.Empty, "timeout"),
            new(DateTimeOffset.Now, false, TimeSpan.FromSeconds(1), string.Empty, "timeout"),
            new(DateTimeOffset.Now, true, TimeSpan.FromMilliseconds(30), "ok", null)
        ];
        NetworkStatistics result = NetworkQualityService.Calculate(samples);
        Assert.Equal(NetworkQuality.Poor, result.Quality);
        Assert.Equal(1, result.Received);
    }

    [Fact]
    public void StunDecoderReadsXorMappedAddress()
    {
        byte[] transaction = Enumerable.Range(1, 12).Select(value => (byte)value).ToArray();
        byte[] packet = new byte[32];
        BinaryPrimitives.WriteUInt16BigEndian(packet, 0x0101);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2), 12);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4), 0x2112A442);
        transaction.CopyTo(packet, 8);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(20), 0x0020);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(22), 8);
        packet[25] = 1;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(26), (ushort)(54321 ^ 0x2112));
        byte[] address = IPAddress.Parse("203.0.113.7").GetAddressBytes();
        byte[] cookie = [0x21, 0x12, 0xA4, 0x42];
        for (int index = 0; index < 4; index++) { packet[28 + index] = (byte)(address[index] ^ cookie[index]); }

        IPEndPoint result = NetworkQualityService.DecodeStun(packet, transaction);
        Assert.Equal("203.0.113.7", result.Address.ToString());
        Assert.Equal(54321, result.Port);
    }
}
