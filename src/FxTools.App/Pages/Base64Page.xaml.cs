using System.Text;
using FxTools.Core.Infrastructure;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace FxTools.App.Pages;

public sealed partial class Base64Page : Page
{
    private static readonly Base64Variant[] Variants =
        [Base64Variant.Standard, Base64Variant.UrlSafe, Base64Variant.Mime];
    private static readonly TextEncoding[] Encodings =
        [TextEncoding.Utf8, TextEncoding.Gb18030, TextEncoding.Utf16LittleEndian, TextEncoding.Utf16BigEndian];

    public Base64Page()
    {
        InitializeComponent();
        VariantBox.ItemsSource = new[] { "标准 Base64", "URL 安全 Base64", "MIME Base64" };
        EncodingBox.ItemsSource = new[] { "UTF-8", "GB18030 / GBK", "UTF-16 LE", "UTF-16 BE" };
    }

    private void Encode_Click(object sender, RoutedEventArgs e) => Execute(encode: true);

    private void Decode_Click(object sender, RoutedEventArgs e) => Execute(encode: false);

    private void Execute(bool encode)
    {
        try
        {
            OutputBox.Text = encode
                ? Base64CodecService.Encode(InputBox.Text, Variants[VariantBox.SelectedIndex], Encodings[EncodingBox.SelectedIndex])
                : Base64CodecService.Decode(InputBox.Text, Variants[VariantBox.SelectedIndex], Encodings[EncodingBox.SelectedIndex]);
            SetStatus(encode ? "编码完成" : "解码完成", success: true);
        }
        catch (Exception exception) when (exception is ArgumentException or EncoderFallbackException)
        {
            OutputBox.Text = string.Empty;
            SetStatus(exception.Message, success: false);
        }
    }

    private async void Paste_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            DataPackageView content = Clipboard.GetContent();
            if (content.Contains(StandardDataFormats.Text))
            {
                string text = await content.GetTextAsync();
                if (text.Length > MemoryLimits.Base64InputCharacters)
                {
                    SetStatus("剪贴板文本超过 100 万字符，未粘贴。", success: false);
                    return;
                }
                InputBox.Text = text;
            }
        }
        catch (Exception exception)
        {
            SetStatus($"读取剪贴板失败: {exception.Message}", success: false);
        }
    }

    private void Swap_Click(object sender, RoutedEventArgs e)
    {
        (InputBox.Text, OutputBox.Text) = (OutputBox.Text, InputBox.Text);
        SetStatus("已交换输入和结果", success: true);
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(OutputBox.Text))
        {
            SetStatus("当前没有可复制的结果", success: false);
            return;
        }
        DataPackage package = new();
        package.SetText(OutputBox.Text);
        Clipboard.SetContent(package);
        Clipboard.Flush();
        SetStatus("结果已复制", success: true);
    }

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        InputBox.Text = string.Empty;
        OutputBox.Text = string.Empty;
        SetStatus("等待输入", success: true);
        InputBox.Focus(FocusState.Programmatic);
    }

    private void InputBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        CountText.Text = $"{InputBox.Text.Length:N0} / {MemoryLimits.Base64InputCharacters:N0}";
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[
            success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }
}
