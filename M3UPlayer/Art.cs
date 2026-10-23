using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using M3UPlayer.Models;
using M3UPlayer.Services;

namespace M3UPlayer;

/// <summary>
/// Imagem carregada em segundo plano como fundo de um Border:
/// <c>local:Art.Url="{Binding Logo}" local:Art.Width="370" local:Art.Fit="True"</c>.
/// A imagem é sempre a da própria lista (tvg-logo).
/// </summary>
public static class Art
{
    public static readonly DependencyProperty UrlProperty = DependencyProperty.RegisterAttached(
        "Url", typeof(string), typeof(Art), new PropertyMetadata(null, (d, _) => Load(d as Border)));

    public static readonly DependencyProperty WidthProperty = DependencyProperty.RegisterAttached(
        "Width", typeof(int), typeof(Art), new PropertyMetadata(370));

    public static readonly DependencyProperty FitProperty = DependencyProperty.RegisterAttached(
        "Fit", typeof(bool), typeof(Art), new PropertyMetadata(false));

    public static string? GetUrl(DependencyObject d) => (string?)d.GetValue(UrlProperty);
    public static void SetUrl(DependencyObject d, string? value) => d.SetValue(UrlProperty, value);
    public static int GetWidth(DependencyObject d) => (int)d.GetValue(WidthProperty);
    public static void SetWidth(DependencyObject d, int value) => d.SetValue(WidthProperty, value);
    public static bool GetFit(DependencyObject d) => (bool)d.GetValue(FitProperty);
    public static void SetFit(DependencyObject d, bool value) => d.SetValue(FitProperty, value);

    private static async void Load(Border? border)
    {
        if (border == null) return;
        var url = GetUrl(border);
        border.Background = null;
        if (string.IsNullOrWhiteSpace(url)) return;
        var width = GetWidth(border);

        // Já decodificada (voltar para a página, rolar de volta): aparece sem animação.
        if (ImageCache.TryGetLoaded(url, width) is { } ready)
        {
            border.Background = Brush(ready, border);
            return;
        }

        var image = await ImageCache.LoadAsync(url, width);
        if (GetUrl(border) != url) return; // o card foi reaproveitado para outra mídia nesse meio-tempo
        if (image == null) return; // fica com as iniciais
        border.Background = Brush(image, border);
        border.Background.BeginAnimation(System.Windows.Media.Brush.OpacityProperty,
            new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
    }

    private static ImageBrush Brush(ImageSource image, Border border) =>
        new(image) { Stretch = GetFit(border) ? Stretch.Uniform : Stretch.UniformToFill };

    /// <summary>Para elementos preenchidos pelo código (pôster da série, prévia, próximo episódio).</summary>
    public static void Show(Border border, string? url, int width, bool fit = false)
    {
        SetWidth(border, width);
        SetFit(border, fit);
        border.ClearValue(UrlProperty);
        SetUrl(border, url);
    }
}
