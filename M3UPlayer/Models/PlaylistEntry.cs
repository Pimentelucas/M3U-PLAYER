using System.ComponentModel;

namespace M3UPlayer.Models;

public enum MediaKind { Live, Movie, Series }

/// <summary>Algo que aparece como um card na biblioteca: canal, filme ou série.</summary>
public abstract class MediaItem : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public string Title { get; set; } = "";
    public string? Logo { get; set; }
    public string Group { get; set; } = "";
    public MediaKind Kind { get; set; }

    public string DisplayGroup => string.IsNullOrWhiteSpace(Group) ? "Sem grupo" : Group;

    /// <summary>Canais ao vivo usam card quadrado (logo); filmes e séries, pôster 2:3.</summary>
    public bool IsSquare => Kind == MediaKind.Live;

    public virtual string Subtitle => DisplayGroup;

    /// <summary>Linha abaixo do título no card (layout minimalista: só aparece em episódios).</summary>
    public virtual string CardSubtitle => "";

    /// <summary>Letras exibidas enquanto o logo não carrega (ou quando não existe).</summary>
    public string Initials
    {
        get
        {
            var words = Title.Split([' ', '-', '_', '.', ':', '|', '[', '(' ], StringSplitOptions.RemoveEmptyEntries)
                .Where(w => char.IsLetterOrDigit(w[0])).Take(2).ToArray();
            return words.Length == 0 ? "?" : string.Concat(words.Select(w => char.ToUpperInvariant(w[0])));
        }
    }

    private double _progress;
    /// <summary>Quanto já foi assistido (0 a 1).</summary>
    public double Progress
    {
        get => _progress;
        set
        {
            if (Math.Abs(_progress - value) < 0.0005) return;
            _progress = value;
            OnPropertyChanged(nameof(Progress));
            OnPropertyChanged(nameof(HasProgress));
        }
    }

    public bool HasProgress => _progress > 0.005 && !_watched;

    private bool _watched;
    public bool Watched
    {
        get => _watched;
        set
        {
            if (_watched == value) return;
            _watched = value;
            OnPropertyChanged(nameof(Watched));
            OnPropertyChanged(nameof(HasProgress));
        }
    }

    protected void OnPropertyChanged(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    public override string ToString() => Title;
}

/// <summary>Um item de uma lista M3U (canal, filme, episódio...).</summary>
public sealed class PlaylistEntry : MediaItem
{
    public string Url { get; set; } = "";
    public string? TvgId { get; set; }

    /// <summary>Duração declarada no #EXTINF (em segundos); -1 normalmente indica stream ao vivo.</summary>
    public double Duration { get; set; } = -1;

    public string? UserAgent { get; set; }
    public string? Referrer { get; set; }

    // Preenchidos quando o título segue o padrão "Nome S01E02"
    public string? SeriesName { get; set; }
    public int Season { get; set; }
    public int Episode { get; set; }
    public string? EpisodeTitle { get; set; }
    public SeriesInfo? Series { get; set; }

    public bool IsEpisode => SeriesName != null;

    public string EpisodeCode => $"T{Season} E{Episode}";

    public string EpisodeHeading => string.IsNullOrWhiteSpace(EpisodeTitle)
        ? $"Episódio {Episode}"
        : $"{Episode}. {EpisodeTitle}";

    public override string Subtitle => IsEpisode ? $"{SeriesName} · {EpisodeCode}" : DisplayGroup;

    public override string CardSubtitle => IsEpisode ? EpisodeCode : "";
}

/// <summary>Série montada a partir dos episódios de um mesmo grupo.</summary>
public sealed class SeriesInfo : MediaItem
{
    public List<PlaylistEntry> Episodes { get; } = [];
    public List<int> Seasons { get; } = [];

    public override string Subtitle =>
        Seasons.Count == 1 ? $"{Episodes.Count} episódios" : $"{Seasons.Count} temporadas · {Episodes.Count} episódios";

    public PlaylistEntry? NextAfter(PlaylistEntry episode)
    {
        var index = Episodes.IndexOf(episode);
        return index >= 0 && index + 1 < Episodes.Count ? Episodes[index + 1] : null;
    }
}
