using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace FxTools.App.Pages;

public sealed partial class DataFormatterPage : Page
{
    public DataFormatterPage()
    {
        InitializeComponent();
        FormatBox.ItemsSource = new[] { "JSON", "XML" };
    }

    private void Format_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            OutputBox.Text = DataFormatterService.Format(
                InputBox.Text, FormatBox.SelectedIndex == 0 ? DataFormat.Json : DataFormat.Xml);
            SetStatus("格式化完成", true);
        }
        catch (Exception exception) when (exception is ArgumentException or System.Text.Json.JsonException or System.Xml.XmlException)
        {
            OutputBox.Text = string.Empty;
            SetStatus(exception.Message, false);
        }
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(OutputBox.Text))
        {
            SetStatus("当前没有可复制的结果", false);
            return;
        }
        DataPackage package = new();
        package.SetText(OutputBox.Text);
        Clipboard.SetContent(package);
        Clipboard.Flush();
        SetStatus("结果已复制", true);
    }

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        InputBox.Text = string.Empty;
        OutputBox.Text = string.Empty;
        SetStatus("等待输入", true);
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[
            success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }
}
