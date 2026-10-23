using System.ComponentModel;
using System.Runtime.CompilerServices;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

public enum DownloadState { Queued, Running, Completed, Failed, Canceled }

/// <summary>Um download em andamento ou concluído, exibido no painel de downloads.</summary>
public sealed class DownloadItem(PlaylistEntry entry) : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public PlaylistEntry Entry { get; } = entry;
    public string Title => Entry.Title;

    /// <summary>Título da linha dentro do grupo: o episódio ("T1 E3 · Episódio 3"); vazio para filmes/canais.</summary>
    public string RowTitle => Entry.IsEpisode ? $"{Entry.EpisodeCode} · {Entry.EpisodeHeading}" : "";

    internal CancellationTokenSource Cts { get; } = new();

    /// <summary>Arquivo encontrado na pasta de downloads (baixado numa vez anterior).</summary>
    public bool FromDisk { get; init; }

    /// <summary>Gravação de stream sem fim (ao vivo): ao cancelar, o arquivo parcial é mantido.</summary>
    internal bool IsLiveRecording { get; set; }

    private string? _filePath;
    public string? FilePath { get => _filePath; set => Set(ref _filePath, value); }

    private DownloadState _state = DownloadState.Queued;
    public DownloadState State
    {
        get => _state;
        set
        {
            if (!Set(ref _state, value)) return;
            if (!IsActive) IsIndeterminate = false;
            if (value == DownloadState.Completed) Progress = 100;
            OnPropertyChanged(nameof(IsActive));
            OnPropertyChanged(nameof(IsCompleted));
            OnPropertyChanged(nameof(StateText));
        }
    }

    private double _progress;
    public double Progress { get => _progress; set => Set(ref _progress, value); }

    private bool _isIndeterminate;
    public bool IsIndeterminate { get => _isIndeterminate; set => Set(ref _isIndeterminate, value); }

    private string _detail = "Na fila";
    public string Detail { get => _detail; set => Set(ref _detail, value); }

    public bool IsActive => State is DownloadState.Queued or DownloadState.Running;
    public bool IsCompleted => State == DownloadState.Completed;

    public string StateText => State switch
    {
        DownloadState.Queued => "Na fila",
        DownloadState.Running => "Baixando",
        DownloadState.Completed => "Concluído",
        DownloadState.Failed => "Falhou",
        DownloadState.Canceled => "Cancelado",
        _ => ""
    };

    public void Cancel() => Cts.Cancel();

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(name);
        return true;
    }

    private void OnPropertyChanged(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
