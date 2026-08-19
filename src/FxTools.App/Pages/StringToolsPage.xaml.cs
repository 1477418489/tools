using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace FxTools.App.Pages;

public sealed partial class StringToolsPage : Page
{
    private static readonly StringTransform[] Operations =
        [StringTransform.RemoveWhitespace, StringTransform.Trim, StringTransform.UpperCase, StringTransform.LowerCase];

    public StringToolsPage()
    {
        InitializeComponent();
        OperationBox.ItemsSource = new[] { "去除所有空白", "去除首尾空白", "转大写", "转小写" };
    }

    private void Transform_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            OutputBox.Text = StringTransformService.Transform(
                InputBox.Text, Operations[OperationBox.SelectedIndex]);
            SetStatus($"处理完成，共 {OutputBox.Text.Length:N0} 个字符", true);
        }
        catch (ArgumentException exception)
        {
            OutputBox.Text = string.Empty;
            SetStatus(exception.Message, false);
        }
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(OutputBox.Text))
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
