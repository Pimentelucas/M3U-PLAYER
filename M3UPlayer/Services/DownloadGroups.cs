using System.Collections.ObjectModel;
using System.ComponentModel;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

/// <summary>Um bloco da aba Downloads: um filme/canal, ou todos os episódios baixados de uma série.</summary>
public sealed class DownloadGroup(string key, string title, string? logo, bool isSeries, bool isSquare) : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public string Key { get; } = key;
    public string Title { get; } = title;
    public string? Logo { get; } = logo;
    public bool IsSeries { get; } = isSeries;
    public bool IsSquare { get; } = isSquare;
    public ObservableCollection<DownloadItem> Items { get; } = [];

    public string Initials => new MediaInitials(Title).Value;

    public string Summary
    {
        get
        {
            var done = Items.Count(i => i.IsCompleted);
            var active = Items.Count(i => i.IsActive);
            var parts = new List<string>();
            if (IsSeries) parts.Add(Items.Count == 1 ? "1 episódio" : $"{Items.Count} episódios");
            if (active > 0) parts.Add(active == 1 ? "1 baixando" : $"{active} baixando");
            if (IsSeries && done > 0) parts.Add(done == 1 ? "1 concluído" : $"{done} concluídos");
            if (!IsSeries && parts.Count == 0) parts.Add(Items.FirstOrDefault()?.StateText ?? "");
            return string.Join("  ·  ", parts);
        }
    }

    public void Refresh() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Summary)));

    /// <summary>Agrupa mantendo a ordem: episódios da mesma série ficam juntos, na ordem de temporada/episódio.</summary>
    public static List<DownloadGroup> Build(IEnumerable<DownloadItem> items)
    {
        var groups = new List<DownloadGroup>();
        var bySeries = new Dictionary<string, DownloadGroup>();
        foreach (var item in items)
        {
            var e = item.Entry;
            if (e.IsEpisode)
            {
                // Pela série (sem o grupo da lista): episódios baixados em outras vezes ficam juntos dos novos
                var key = "s:" + e.SeriesName!.ToUpperInvariant();
                if (!bySeries.TryGetValue(key, out var group))
                {
                    bySeries[key] = group = new DownloadGroup(key, e.SeriesName!, e.Series?.Logo ?? e.Logo, true, false);
                    groups.Add(group);
                }
                group.Items.Add(item);
            }
            else
            {
                var group = new DownloadGroup("i:" + groups.Count, e.Title, e.Logo, false, e.IsSquare);
                group.Items.Add(item);
                groups.Add(group);
            }
        }

        foreach (var group in groups.Where(g => g.IsSeries && g.Items.Count > 1))
        {
            var sorted = group.Items.OrderBy(i => i.Entry.Season).ThenBy(i => i.Entry.Episode).ToList();
            group.Items.Clear();
            foreach (var item in sorted) group.Items.Add(item);
        }
        return groups;
    }
}

/// <summary>Iniciais para o placeholder da imagem (mesma regra dos cards).</summary>
internal readonly struct MediaInitials(string title)
{
    public string Value
    {
        get
        {
            var words = title.Split([' ', '-', '_', '.', ':', '|', '[', '('], StringSplitOptions.RemoveEmptyEntries)
                .Where(w => char.IsLetterOrDigit(w[0])).Take(2).ToArray();
            return words.Length == 0 ? "?" : string.Concat(words.Select(w => char.ToUpperInvariant(w[0])));
        }
    }
}
