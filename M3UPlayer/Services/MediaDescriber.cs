using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

/// <summary>Texto da prévia (estilo Netflix). Listas M3U não trazem sinopse, então o resumo é montado com o que há.</summary>
public sealed record MediaDescription(string Meta, string Text, string PlayLabel);

public static partial class MediaDescriber
{
    [GeneratedRegex(@"[\(\[](19|20)\d{2}[\)\]]|\b(19|20)\d{2}\b")]
    private static partial Regex YearRegex();

    [GeneratedRegex(@"\b(4K|UHD|FHD|FULL\s?HD|HD|SD|H\.?265|HEVC)\b", RegexOptions.IgnoreCase)]
    private static partial Regex QualityRegex();

    [GeneratedRegex(@"\[L\]|\bLEG(ENDADO|ENDADA|ENDADOS|ENDADAS)?\b", RegexOptions.IgnoreCase)]
    private static partial Regex SubtitledRegex();

    [GeneratedRegex(@"\bDUB(LADO|LADA|LADOS)?\b", RegexOptions.IgnoreCase)]
    private static partial Regex DubbedRegex();

    /// <param name="record">Histórico do item (filme/canal) ou do episódio-alvo (série).</param>
    /// <param name="seriesTarget">Para séries: o episódio que o botão vai tocar.</param>
    public static MediaDescription Describe(MediaItem item, WatchRecord? record, PlaylistEntry? seriesTarget = null)
    {
        var source = item.Title + " " + item.Group;
        var year = YearRegex().Match(item.Title) is { Success: true } y ? y.Value.Trim('(', ')', '[', ']') : null;
        var quality = Quality(source);
        var language = SubtitledRegex().IsMatch(source) ? "Legendado" : DubbedRegex().IsMatch(source) ? "Dublado" : null;
        var genre = CleanGroup(item.Group);

        var kind = item switch
        {
            SeriesInfo => "Série",
            PlaylistEntry { IsEpisode: true } => "Episódio",
            _ when item.Kind == MediaKind.Live => "Ao vivo",
            _ => "Filme",
        };
        var meta = string.Join("  ·  ", new[] { year, kind, quality, language }.Where(s => !string.IsNullOrEmpty(s)));

        var text = new StringBuilder();
        var playLabel = "Assistir";
        switch (item)
        {
            case SeriesInfo series:
                text.Append(series.Seasons.Count == 1
                    ? $"Série com {series.Episodes.Count} episódios"
                    : $"Série com {series.Seasons.Count} temporadas e {series.Episodes.Count} episódios");
                if (genre.Length > 0) text.Append($", em {genre}");
                text.Append('.');
                if (seriesTarget != null)
                {
                    if (record is { CanResume: true })
                    {
                        text.Append($" Você parou em {Format.Duration(TimeSpan.FromMilliseconds(record.PositionMs))} de {seriesTarget.EpisodeCode}.");
                        playLabel = $"Continuar {seriesTarget.EpisodeCode}";
                    }
                    else if (record != null || seriesTarget != series.Episodes[0])
                    {
                        text.Append($" Próximo: {seriesTarget.EpisodeCode}.");
                        playLabel = $"Assistir {seriesTarget.EpisodeCode}";
                    }
                    else
                    {
                        playLabel = $"Assistir {seriesTarget.EpisodeCode}";
                    }
                }
                break;

            case PlaylistEntry { Kind: MediaKind.Live }:
                text.Append("Canal ao vivo");
                if (quality != null) text.Append($" em {quality}");
                if (genre.Length > 0) text.Append($", do grupo {genre}");
                text.Append('.');
                break;

            case PlaylistEntry entry:
                text.Append(entry.IsEpisode ? $"{entry.SeriesName}, {entry.EpisodeCode}" : "Filme");
                if (!entry.IsEpisode && genre.Length > 0) text.Append($" de {genre}");
                if (!entry.IsEpisode && year != null) text.Append($", de {year}");
                text.Append('.');
                if (record is { CanResume: true })
                {
                    text.Append($" Você parou em {Format.Duration(TimeSpan.FromMilliseconds(record.PositionMs))}" +
                                $" de {Format.Duration(TimeSpan.FromMilliseconds(record.DurationMs))}.");
                    playLabel = "Continuar";
                }
                else if (record is { Completed: true })
                {
                    text.Append(" Você já assistiu.");
                    playLabel = "Assistir de novo";
                }
                else if (record is { DurationMs: > 0 })
                {
                    text.Append($" Duração: {Format.Duration(TimeSpan.FromMilliseconds(record.DurationMs))}.");
                }
                break;
        }

        return new MediaDescription(meta, text.ToString(), playLabel);
    }

    private static string? Quality(string text)
    {
        var found = QualityRegex().Matches(text).Select(m => m.Value.ToUpperInvariant().Replace(" ", "")).ToHashSet();
        if (found.Contains("4K") || found.Contains("UHD")) return "4K";
        if (found.Contains("FHD") || found.Contains("FULLHD")) return "Full HD";
        if (found.Contains("H265") || found.Contains("H.265") || found.Contains("HEVC")) return "H.265";
        if (found.Contains("HD")) return "HD";
        if (found.Contains("SD")) return "SD";
        return null;
    }

    /// <summary>"♦️ CANAIS | FHD H265 ⭐" → "Canais · FHD H265"; "♦️ Ação ✔️" → "Ação".</summary>
    public static string CleanGroup(string group)
    {
        var parts = group.Split('|')
            .Select(p => new string(p.Where(c => char.IsLetterOrDigit(c) || c is ' ' or '&' or '+' or '-' or '\'').ToArray()).Trim())
            .Where(p => p.Length > 0)
            .Select(p => p.Length > 3 && p.All(c => !char.IsLetter(c) || char.IsUpper(c)) && p.Any(char.IsLetter) && !QualityRegex().IsMatch(p)
                ? CultureInfo.GetCultureInfo("pt-BR").TextInfo.ToTitleCase(p.ToLowerInvariant())
                : p);
        return string.Join(" · ", parts);
    }
}
