using System.Text.Json;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

/// <summary>Um item assistido, com o ponto onde o usuário parou.</summary>
public sealed class WatchRecord
{
    public string Url { get; set; } = "";
    public string Title { get; set; } = "";
    public string? Logo { get; set; }
    public string Group { get; set; } = "";
    public MediaKind Kind { get; set; }
    public string? UserAgent { get; set; }
    public string? Referrer { get; set; }
    public string? SeriesName { get; set; }
    public int Season { get; set; }
    public int Episode { get; set; }

    public long PositionMs { get; set; }
    public long DurationMs { get; set; }
    public bool Completed { get; set; }
    public DateTime LastWatched { get; set; }

    public double Progress => Completed ? 1 : DurationMs > 0 ? Math.Clamp((double)PositionMs / DurationMs, 0, 1) : 0;

    /// <summary>Vale a pena oferecer "continuar de onde parou"?</summary>
    public bool CanResume => !Completed && DurationMs > 0 && PositionMs >= 10_000 && !WatchHistory.IsFinished(PositionMs, DurationMs);

    public PlaylistEntry ToEntry() => new()
    {
        Url = Url, Title = Title, Logo = Logo, Group = Group, Kind = Kind,
        UserAgent = UserAgent, Referrer = Referrer, SeriesName = SeriesName, Season = Season, Episode = Episode,
        Progress = Progress, Watched = Completed
    };
}

/// <summary>Histórico (%APPDATA%\M3UPlayer\history.json), do mais recente para o mais antigo.</summary>
public sealed class WatchHistory
{
    private const int MaxItems = 300;

    public List<WatchRecord> Items { get; private set; } = [];

    public static string FilePath { get; set; } = Path.Combine(AppSettings.Folder, "history.json");

    public static WatchHistory Load()
    {
        var history = new WatchHistory();
        try
        {
            if (File.Exists(FilePath))
                history.Items = JsonSerializer.Deserialize<List<WatchRecord>>(File.ReadAllText(FilePath)) ?? [];
        }
        catch
        {
            // histórico corrompido: começa vazio
        }
        return history;
    }

    public void Save() => JsonStore.Save(FilePath, Items);

    /// <summary>Terminou? (≥ 93% ou faltando menos de 2 minutos num vídeo longo).</summary>
    public static bool IsFinished(long positionMs, long durationMs) =>
        durationMs > 0 && (positionMs >= durationMs * 0.93 || (durationMs > 10 * 60_000 && durationMs - positionMs < 120_000));

    public WatchRecord? Get(string url) => Items.FirstOrDefault(r => r.Url == url);

    /// <summary>Registra que o item começou a ser assistido agora (sem perder a posição salva).</summary>
    public WatchRecord Touch(PlaylistEntry entry)
    {
        var record = Get(entry.Url);
        if (record == null)
        {
            record = new WatchRecord { Url = entry.Url };
        }
        else
        {
            Items.Remove(record);
        }

        record.Title = entry.Title;
        record.Logo = entry.Logo ?? record.Logo;
        record.Group = entry.Group;
        record.Kind = entry.Kind;
        record.UserAgent = entry.UserAgent;
        record.Referrer = entry.Referrer;
        record.SeriesName = entry.SeriesName;
        record.Season = entry.Season;
        record.Episode = entry.Episode;
        record.LastWatched = DateTime.Now;

        Items.Insert(0, record);
        if (Items.Count > MaxItems) Items.RemoveRange(MaxItems, Items.Count - MaxItems);
        return record;
    }

    public void UpdatePosition(string url, long positionMs, long durationMs)
    {
        var record = Get(url);
        if (record == null || durationMs <= 0) return;
        record.DurationMs = durationMs;
        record.PositionMs = Math.Max(0, positionMs);
        record.Completed = IsFinished(positionMs, durationMs);
        if (record.Completed) record.PositionMs = 0; // da próxima vez começa do início
        record.LastWatched = DateTime.Now;
    }

    public void MarkCompleted(string url)
    {
        var record = Get(url);
        if (record == null) return;
        record.Completed = true;
        record.PositionMs = 0;
    }

    public void Remove(string url) => Items.RemoveAll(r => r.Url == url);

    public void RemoveSeries(string seriesName, string group) =>
        Items.RemoveAll(r => r.SeriesName == seriesName && r.Group == group);
}
