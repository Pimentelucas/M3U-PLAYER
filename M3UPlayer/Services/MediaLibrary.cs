using System.Text.RegularExpressions;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

public sealed class LibraryGroup(string name)
{
    public string Name { get; } = name;
    public MediaKind Kind { get; set; }

    /// <summary>Cards do grupo, na ordem da lista (episódios já agrupados em séries).</summary>
    public List<MediaItem> Items { get; } = [];

    /// <summary>Itens tocáveis diretamente (canais/filmes), usados para anterior/próximo no player.</summary>
    public List<PlaylistEntry> Entries { get; } = [];
}

/// <summary>
/// Organiza a lista como no Stremio: separa TV ao vivo, filmes e séries, junta episódios
/// ("Nome S01E02") numa única série e mantém os grupos na ordem em que aparecem na lista.
/// </summary>
public sealed partial class MediaLibrary
{
    // Procura direto o "S01E02" (o nome é o que vem antes; o resto, depois) — bem mais rápido que casar o título inteiro.
    [GeneratedRegex(@"\b[Ss](?<s>\d{1,3})\s*[Ee](?<e>\d{1,4})\b")]
    private static partial Regex EpisodeRegex();

    [GeneratedRegex(@"[\(\[](19|20)\d{2}[\)\]]|\s(19|20)\d{2}\s*$")]
    private static partial Regex YearRegex();

    private static readonly HashSet<string> VodExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm", ".flv", ".mpg", ".mpeg", ".3gp",
        ".mp3", ".aac", ".flac", ".m4a", ".ogg", ".opus", ".wav"
    };

    public IReadOnlyList<PlaylistEntry> Entries { get; }
    public IReadOnlyList<LibraryGroup> Groups { get; }
    private readonly Dictionary<string, List<PlaylistEntry>> _byUrl;

    private MediaLibrary(List<PlaylistEntry> entries, List<LibraryGroup> groups)
    {
        Entries = entries;
        Groups = groups;
        _byUrl = new Dictionary<string, List<PlaylistEntry>>(entries.Count);
        foreach (var e in entries)
        {
            if (!_byUrl.TryGetValue(e.Url, out var list)) _byUrl[e.Url] = list = new List<PlaylistEntry>(1);
            list.Add(e);
        }
    }

    public bool HasKind(MediaKind kind) => Groups.Any(g => g.Kind == kind);

    public IReadOnlyList<PlaylistEntry> FindByUrl(string url) =>
        _byUrl.TryGetValue(url, out var list) ? list : [];

    public LibraryGroup? FindGroup(string name) => Groups.FirstOrDefault(g => g.Name == name);

    private Dictionary<string, PlaylistEntry>? _byFileName;

    /// <summary>Título da lista que gera esse nome de arquivo (os downloads usam o título como nome).</summary>
    public PlaylistEntry? FindByFileName(string fileTitle)
    {
        if (_byFileName == null)
        {
            var map = new Dictionary<string, PlaylistEntry>(Entries.Count, StringComparer.OrdinalIgnoreCase);
            foreach (var e in Entries) map.TryAdd(DownloadManager.SanitizeFileName(e.Title), e);
            _byFileName = map;
        }
        return _byFileName.TryGetValue(fileTitle, out var entry) ? entry : null;
    }

    /// <summary>Preenche série/temporada/episódio quando o título segue "Nome S01E02".</summary>
    public static bool ParseEpisode(PlaylistEntry e)
    {
        var match = EpisodeRegex().Match(e.Title);
        if (!match.Success) return false;
        var name = e.Title[..match.Index].TrimEnd(' ', '\t', '-', '_', '.', ':', '|').Trim();
        if (name.Length == 0) return false;
        e.Kind = MediaKind.Series;
        e.SeriesName = name;
        e.Season = int.Parse(match.Groups["s"].ValueSpan);
        e.Episode = int.Parse(match.Groups["e"].ValueSpan);
        var rest = e.Title[(match.Index + match.Length)..].TrimStart(' ', '\t', '-', '_', '.', ':', '|').Trim();
        e.EpisodeTitle = rest.Length > 0 ? rest : null;
        return true;
    }

    /// <summary>Busca por título em canais, filmes e séries (não em episódios soltos).</summary>
    public List<MediaItem> Search(string query, MediaKind? kind, int limit)
    {
        var result = new List<MediaItem>();
        foreach (var group in Groups)
        {
            if (kind != null && group.Kind != kind) continue;
            foreach (var item in group.Items)
            {
                if (!item.Title.Contains(query, StringComparison.CurrentCultureIgnoreCase)) continue;
                result.Add(item);
                if (result.Count >= limit) return result;
            }
        }
        return result;
    }

    public static MediaLibrary Build(List<PlaylistEntry> entries)
    {
        Classify(entries);

        var groups = new List<LibraryGroup>();
        var byName = new Dictionary<string, LibraryGroup>();
        var series = new Dictionary<(string Group, string Name), SeriesInfo>();
        var kindCounts = new Dictionary<LibraryGroup, int[]>();

        foreach (var entry in entries)
        {
            var name = entry.DisplayGroup;
            if (!byName.TryGetValue(name, out var group))
            {
                byName[name] = group = new LibraryGroup(name);
                groups.Add(group);
                kindCounts[group] = new int[3];
            }
            kindCounts[group][(int)entry.Kind]++;

            if (entry.Kind == MediaKind.Series && entry.SeriesName != null)
            {
                var key = (name, entry.SeriesName.ToUpperInvariant());
                if (!series.TryGetValue(key, out var show))
                {
                    series[key] = show = new SeriesInfo
                    {
                        Title = entry.SeriesName,
                        Group = entry.Group,
                        Kind = MediaKind.Series,
                        Logo = entry.Logo
                    };
                    group.Items.Add(show);
                }
                show.Logo ??= entry.Logo;
                show.Episodes.Add(entry);
                entry.Series = show;
            }
            else
            {
                group.Items.Add(entry);
                group.Entries.Add(entry);
            }
        }

        foreach (var show in series.Values)
        {
            show.Episodes.Sort((a, b) => a.Season != b.Season ? a.Season.CompareTo(b.Season) : a.Episode.CompareTo(b.Episode));
            show.Seasons.AddRange(show.Episodes.Select(e => e.Season).Distinct());
        }

        foreach (var group in groups)
        {
            var counts = kindCounts[group];
            group.Kind = counts[(int)MediaKind.Series] >= counts[(int)MediaKind.Movie] && counts[(int)MediaKind.Series] >= counts[(int)MediaKind.Live]
                ? MediaKind.Series
                : counts[(int)MediaKind.Movie] >= counts[(int)MediaKind.Live] ? MediaKind.Movie : MediaKind.Live;
        }

        return new MediaLibrary(entries, groups);
    }

    /// <summary>
    /// Decide se cada item é canal, filme ou episódio. Listas IPTV raramente dizem isso explicitamente,
    /// então combinamos pistas:
    ///  • fortes: título "S01E02", "/movie/" ou "/live/" na URL, extensão de arquivo, duração no #EXTINF;
    ///  • o "formato" da URL (nº de segmentos + extensão): servidores usam um formato para VOD e outro
    ///    para canais, então as pistas fortes de um formato valem para os itens sem pista;
    ///  • fraca: ano no título — só decide quando o formato da URL não diz nada.
    /// </summary>
    private static void Classify(List<PlaylistEntry> entries)
    {
        var strong = new bool[entries.Count];
        var hasYear = new bool[entries.Count];
        var shapes = new string[entries.Count];
        var strongKind = new MediaKind?[entries.Count];
        var shapeVotes = new Dictionary<string, (int Live, int Movie)>();
        var groupVotes = new Dictionary<string, (int Live, int Movie)>();

        // 1ª passada (a parte cara: regex do título e análise da URL) em paralelo — cada item só mexe em si mesmo.
        Parallel.For(0, entries.Count, i =>
        {
            var e = entries[i];
            var path = UrlPath(e.Url);
            shapes[i] = UrlShape(path);

            if (ParseEpisode(e))
            {
                strongKind[i] = MediaKind.Series;
                return;
            }

            var ext = Path.GetExtension(path);
            if (path.Contains("/movie/", StringComparison.OrdinalIgnoreCase) || path.Contains("/vod/", StringComparison.OrdinalIgnoreCase) ||
                path.Contains("/series/", StringComparison.OrdinalIgnoreCase) || VodExtensions.Contains(ext) || e.Duration > 0)
                strongKind[i] = MediaKind.Movie;
            else if (path.Contains("/live/", StringComparison.OrdinalIgnoreCase) || ext.Equals(".ts", StringComparison.OrdinalIgnoreCase))
                strongKind[i] = MediaKind.Live;
            else
                hasYear[i] = YearRegex().IsMatch(e.Title);
        });

        // Votos (rápido, na ordem da lista)
        for (int i = 0; i < entries.Count; i++)
        {
            var e = entries[i];
            switch (strongKind[i])
            {
                case MediaKind.Series:
                    strong[i] = true;
                    Vote(shapeVotes, shapes[i], MediaKind.Movie); // episódio = conteúdo sob demanda
                    break;
                case { } k:
                    e.Kind = k;
                    strong[i] = true;
                    Vote(shapeVotes, shapes[i], k);
                    Vote(groupVotes, e.DisplayGroup, k);
                    break;
                default:
                    if (hasYear[i]) Vote(groupVotes, e.DisplayGroup, MediaKind.Movie);
                    break;
            }
        }

        // Se a lista tem um formato de URL claramente de VOD, um formato diferente e sem nenhuma
        // pista forte é, na prática, o dos canais ao vivo.
        var vodShapeKnown = shapeVotes.Values.Any(v => v.Movie >= 50 && v.Movie > v.Live);

        for (int i = 0; i < entries.Count; i++)
        {
            if (strong[i]) continue;
            var e = entries[i];
            e.Kind = Decide(shapeVotes, shapes[i])
                     ?? (vodShapeKnown && !shapeVotes.ContainsKey(shapes[i]) ? MediaKind.Live : (MediaKind?)null)
                     ?? (hasYear[i] ? MediaKind.Movie : (MediaKind?)null)
                     ?? Decide(groupVotes, e.DisplayGroup)
                     ?? MediaKind.Live;
        }
    }

    private static void Vote(Dictionary<string, (int Live, int Movie)> votes, string key, MediaKind kind)
    {
        votes.TryGetValue(key, out var v);
        votes[key] = kind == MediaKind.Live ? (v.Live + 1, v.Movie) : (v.Live, v.Movie + 1);
    }

    private static MediaKind? Decide(Dictionary<string, (int Live, int Movie)> votes, string key) =>
        votes.TryGetValue(key, out var v) && v.Live + v.Movie > 0
            ? (v.Movie > v.Live ? MediaKind.Movie : MediaKind.Live)
            : null;

    private static string UrlPath(string url)
    {
        // Caminho rápido para http(s): o trecho entre o servidor e "?"/"#" (sem montar um Uri, que é caro).
        var scheme = url.IndexOf("://", StringComparison.Ordinal);
        if (scheme > 0 && url.StartsWith("http", StringComparison.OrdinalIgnoreCase))
        {
            var start = url.IndexOf('/', scheme + 3);
            if (start < 0) return "/";
            var end = url.IndexOfAny(['?', '#'], start);
            return end < 0 ? url[start..] : url[start..end];
        }
        return Uri.TryCreate(url, UriKind.Absolute, out var u) ? (u.IsFile ? u.LocalPath : u.AbsolutePath) : url;
    }

    /// <summary>"Formato" da URL: número de segmentos + extensão (ex.: "3:" ou "2:.mp4").</summary>
    private static string UrlShape(string path)
    {
        int segments = 0;
        foreach (var c in path) if (c == '/') segments++;
        return $"{segments}:{Path.GetExtension(path).ToLowerInvariant()}";
    }
}
