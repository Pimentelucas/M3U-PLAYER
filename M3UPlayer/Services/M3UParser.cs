using System.Collections.Concurrent;
using System.Globalization;
using System.Text.RegularExpressions;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

/// <summary>
/// Lê listas M3U / M3U8 "estendidas" (formato usado por IPTV, rádios, coleções de mídia).
/// Suporta #EXTINF com atributos (tvg-id, tvg-logo, group-title...), #EXTGRP e #EXTVLCOPT.
/// Lê linha a linha, para aguentar listas com centenas de milhares de itens.
/// </summary>
public static partial class M3UParser
{
    [GeneratedRegex(@"([A-Za-z0-9_\-]+)=""([^""]*)""")]
    private static partial Regex AttributeRegex();

    public static bool IsHlsStream(string text) =>
        text.Contains("#EXT-X-TARGETDURATION", StringComparison.OrdinalIgnoreCase) ||
        text.Contains("#EXT-X-STREAM-INF", StringComparison.OrdinalIgnoreCase);

    public static List<PlaylistEntry> Parse(string text, Uri? baseUri)
    {
        using var reader = new StringReader(text);
        return Parse(reader, baseUri);
    }

    /// <summary>Linhas de um item, juntadas na leitura e interpretadas depois (em paralelo).</summary>
    private readonly record struct RawItem(string? ExtInf, string? ExtGroup, List<string>? VlcOptions, string Url);

    public static List<PlaylistEntry> Parse(TextReader reader, Uri? baseUri)
    {
        // 1) Leitura sequencial: só separa as linhas de cada item (barato).
        var raw = new List<RawItem>();
        string? extInf = null, extGroup = null;
        List<string>? vlcOptions = null;

        string? rawLine;
        while ((rawLine = reader.ReadLine()) != null)
        {
            var line = rawLine.Trim();
            if (line.Length == 0) continue;

            if (line.StartsWith("#EXTINF", StringComparison.OrdinalIgnoreCase))
            {
                extInf = line;
                vlcOptions = null;
                continue;
            }
            if (line.StartsWith("#EXTGRP:", StringComparison.OrdinalIgnoreCase))
            {
                extGroup = line[8..].Trim();
                continue;
            }
            if (line.StartsWith("#EXTVLCOPT:", StringComparison.OrdinalIgnoreCase))
            {
                (vlcOptions ??= []).Add(line[11..]);
                continue;
            }
            if (line.StartsWith('#')) continue;

            // Linha de URL/caminho: fecha o item atual.
            raw.Add(new RawItem(extInf, extGroup, vlcOptions, line));
            extInf = null;
            extGroup = null;
            vlcOptions = null;
        }

        // 2) Interpretação (atributos, título, URL) em paralelo: cada item é independente.
        var strings = new ConcurrentDictionary<string, string>(); // grupos se repetem muito: guarda uma cópia só
        var result = new PlaylistEntry[raw.Count];
        Parallel.For(0, raw.Count, i =>
        {
            var item = raw[i];
            var entry = item.ExtInf != null ? ParseExtInf(item.ExtInf, strings) : new PlaylistEntry();
            if (item.VlcOptions != null)
                foreach (var option in item.VlcOptions) ApplyVlcOption(entry, option, strings);
            entry.Url = ResolveUrl(item.Url, baseUri);
            if (string.IsNullOrWhiteSpace(entry.Title)) entry.Title = TitleFromUrl(entry.Url);
            if (string.IsNullOrWhiteSpace(entry.Group) && item.ExtGroup != null) entry.Group = Intern(strings, item.ExtGroup);
            result[i] = entry;
        });
        return [.. result];
    }

    private static PlaylistEntry ParseExtInf(string line, ConcurrentDictionary<string, string> strings)
    {
        var entry = new PlaylistEntry();
        var colon = line.IndexOf(':');
        var body = colon >= 0 ? line[(colon + 1)..] : "";

        // O título vem após a primeira vírgula que não está entre aspas.
        int comma = -1;
        bool inQuotes = false;
        for (int i = 0; i < body.Length; i++)
        {
            if (body[i] == '"') inQuotes = !inQuotes;
            else if (body[i] == ',' && !inQuotes) { comma = i; break; }
        }

        var head = comma >= 0 ? body[..comma] : body;
        entry.Title = comma >= 0 ? body[(comma + 1)..].Trim() : "";

        var durationText = head.TrimStart().Split([' ', '\t'], 2)[0];
        if (double.TryParse(durationText, NumberStyles.Float, CultureInfo.InvariantCulture, out var duration))
            entry.Duration = duration;

        string? tvgName = null;
        foreach (Match m in AttributeRegex().Matches(head))
        {
            var value = m.Groups[2].Value.Trim();
            if (value.Length == 0) continue;
            switch (m.Groups[1].Value.ToLowerInvariant())
            {
                case "group-title": entry.Group = Intern(strings, value); break;
                case "tvg-logo":
                case "logo": entry.Logo ??= value; break;
                case "tvg-id": entry.TvgId = value; break;
                case "tvg-name": tvgName = value; break;
                case "user-agent":
                case "http-user-agent": entry.UserAgent = Intern(strings, value); break;
                case "http-referrer":
                case "referrer": entry.Referrer = Intern(strings, value); break;
            }
        }
        if (string.IsNullOrWhiteSpace(entry.Title)) entry.Title = tvgName ?? "";

        return entry;
    }

    private static string Intern(ConcurrentDictionary<string, string> pool, string value) => pool.GetOrAdd(value, value);

    private static void ApplyVlcOption(PlaylistEntry entry, string option, ConcurrentDictionary<string, string> strings)
    {
        var eq = option.IndexOf('=');
        if (eq <= 0) return;
        var key = option[..eq].Trim().ToLowerInvariant();
        var value = Intern(strings, option[(eq + 1)..].Trim());
        switch (key)
        {
            case "http-user-agent": entry.UserAgent = value; break;
            case "http-referrer":
            case "http-referer": entry.Referrer = value; break;
        }
    }

    private static string ResolveUrl(string line, Uri? baseUri)
    {
        // Caminho rápido (quase todas as linhas de listas IPTV): já é uma URL completa.
        if (line.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || line.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            return line;
        if (Uri.TryCreate(line, UriKind.Absolute, out _)) return line;
        if (baseUri == null) return line;
        try
        {
            var resolved = new Uri(baseUri, line);
            return resolved.IsFile ? resolved.LocalPath : resolved.AbsoluteUri;
        }
        catch (UriFormatException)
        {
            return line;
        }
    }

    public static string TitleFromUrl(string url)
    {
        try
        {
            if (Uri.TryCreate(url, UriKind.Absolute, out var u))
            {
                var name = Path.GetFileNameWithoutExtension(Uri.UnescapeDataString(u.IsFile ? u.LocalPath : u.AbsolutePath));
                if (!string.IsNullOrWhiteSpace(name)) return name;
                return u.Host;
            }
            var file = Path.GetFileNameWithoutExtension(url);
            return string.IsNullOrWhiteSpace(file) ? url : file;
        }
        catch
        {
            return url;
        }
    }
}
