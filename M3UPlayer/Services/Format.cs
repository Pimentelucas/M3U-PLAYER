using System.Globalization;

namespace M3UPlayer.Services;

public static class Format
{
    private static readonly string[] Units = ["B", "KB", "MB", "GB", "TB"];

    public static string Bytes(long bytes)
    {
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < Units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return unit == 0
            ? $"{bytes} B"
            : value.ToString(value >= 100 ? "0" : "0.0", CultureInfo.CurrentCulture) + " " + Units[unit];
    }

    public static string Duration(TimeSpan t) =>
        t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"mm\:ss");
}
