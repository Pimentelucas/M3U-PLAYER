using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media.Imaging;

namespace M3UPlayer;

/// <summary>
/// Converte a URL do tvg-logo numa imagem (com cache), ignorando URLs inválidas.
/// O parâmetro opcional é a largura de decodificação em pixels (padrão 160).
/// </summary>
public sealed class LogoConverter : IValueConverter
{
    private const int MaxCached = 1500;
    private static readonly Dictionary<string, BitmapImage> Cache = new();

    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string url || string.IsNullOrWhiteSpace(url)) return null;
        var width = parameter is string p && int.TryParse(p, out var w) ? w : 160;
        var key = width + "|" + url;
        if (Cache.TryGetValue(key, out var cached)) return cached;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return null;

        try
        {
            var image = new BitmapImage();
            image.BeginInit();
            image.UriSource = uri;
            image.DecodePixelWidth = width;
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            image.EndInit();

            if (Cache.Count >= MaxCached) Cache.Clear();
            Cache[key] = image;
            return image;
        }
        catch
        {
            return null;
        }
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}

/// <summary>true → Visible; false → Collapsed. Com parâmetro "invert", o contrário.</summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var flag = value is true;
        if (parameter is "invert") flag = !flag;
        return flag ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
