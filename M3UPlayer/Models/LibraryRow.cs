using System.ComponentModel;

namespace M3UPlayer.Models;

/// <summary>
/// Uma linha da página inicial ("Continuar assistindo", ou um grupo da lista) em forma de carrossel:
/// mostra os títulos que cabem na largura e as setas passam para os próximos/anteriores.
/// </summary>
public sealed class LibraryRow(string title, IReadOnlyList<MediaItem> all, string? groupName, bool isContinue)
    : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public string Title { get; } = title;
    public IReadOnlyList<MediaItem> All { get; } = all;
    public string? GroupName { get; } = groupName;
    public bool IsContinue { get; } = isContinue;
    public bool ShowSeeAll { get; init; } = true;

    private int _start;
    private int _pageSize = 6;

    /// <summary>Títulos visíveis agora.</summary>
    public IReadOnlyList<MediaItem> Items => All.Skip(_start).Take(_pageSize).ToList();
    public bool CanBack => _start > 0;
    public bool CanForward => _start + _pageSize < All.Count;

    public void SetPageSize(int size)
    {
        size = Math.Max(1, size);
        if (size == _pageSize) return;
        _pageSize = size;
        _start = _start / size * size;
        Changed();
    }

    public void Forward()
    {
        if (!CanForward) return;
        _start += _pageSize;
        Changed();
    }

    public void Back()
    {
        if (!CanBack) return;
        _start = Math.Max(0, _start - _pageSize);
        Changed();
    }

    private void Changed()
    {
        foreach (var name in new[] { nameof(Items), nameof(CanBack), nameof(CanForward) })
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
