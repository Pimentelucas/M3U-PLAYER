using System.Text.Json;

namespace M3UPlayer.Services;

/// <summary>Configurações persistidas em %APPDATA%\M3UPlayer\settings.json.</summary>
public sealed class AppSettings
{
    private const int MaxSavedSources = 10;

    public string DownloadFolder { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "M3U Player");

    /// <summary>Pasta dos episódios (cada série numa subpasta). Vazio = a mesma dos filmes.</summary>
    public string? SeriesFolder { get; set; }

    public string SeriesFolderOrDefault => string.IsNullOrWhiteSpace(SeriesFolder) ? DownloadFolder : SeriesFolder;

    /// <summary>Onde salvar: filmes e canais em <see cref="DownloadFolder"/>; episódios numa subpasta com o nome da série.</summary>
    public string FolderFor(Models.PlaylistEntry entry) => entry.IsEpisode
        ? Path.Combine(SeriesFolderOrDefault, DownloadManager.SanitizeFileName(entry.SeriesName!))
        : DownloadFolder;

    public int Volume { get; set; } = 80;

    /// <summary>A prévia abre com som; se o usuário desligar numa prévia, as próximas abrem sem som.</summary>
    public bool PreviewMuted { get; set; }

    /// <summary>Lista ativa: carregada automaticamente ao abrir o app.</summary>
    public string? LastSource { get; set; }

    /// <summary>Listas usadas recentemente (a mais recente primeiro).</summary>
    public List<string> SavedSources { get; set; } = [];

    public int MaxParallelDownloads { get; set; } = 3;

    /// <summary>Depois desse tempo a lista é atualizada em segundo plano ao abrir o app.</summary>
    public int RefreshHours { get; set; } = 12;

    /// <summary>Fim da abertura aprendido por série (ms), usado pelo botão "Pular abertura".</summary>
    public Dictionary<string, long> IntroEnds { get; set; } = [];

    public static string Folder { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "M3UPlayer");

    private static string FilePath => Path.Combine(Folder, "settings.json");

    public void RememberSource(string source)
    {
        LastSource = source;
        SavedSources.RemoveAll(s => string.Equals(s, source, StringComparison.OrdinalIgnoreCase));
        SavedSources.Insert(0, source);
        if (SavedSources.Count > MaxSavedSources) SavedSources.RemoveRange(MaxSavedSources, SavedSources.Count - MaxSavedSources);
        Save();
    }

    public void ForgetSource(string source)
    {
        SavedSources.RemoveAll(s => string.Equals(s, source, StringComparison.OrdinalIgnoreCase));
        if (string.Equals(LastSource, source, StringComparison.OrdinalIgnoreCase)) LastSource = SavedSources.FirstOrDefault();
        Save();
    }

    public static AppSettings Load()
    {
        AppSettings settings;
        try
        {
            settings = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings()
                : new AppSettings();
        }
        catch
        {
            settings = new AppSettings(); // arquivo corrompido: usa os padrões
        }

        // Versões anteriores só guardavam LastSource.
        if (!string.IsNullOrWhiteSpace(settings.LastSource) && !settings.SavedSources.Contains(settings.LastSource))
            settings.SavedSources.Insert(0, settings.LastSource);
        return settings;
    }

    public void Save() => JsonStore.Save(FilePath, this);
}

/// <summary>Gravação de JSON à prova de queda: escreve num temporário e troca.</summary>
internal static class JsonStore
{
    public static void Save<T>(string path, T value)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temp = path + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(value, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(temp, path, overwrite: true);
        }
        catch
        {
            // Falha ao salvar não deve derrubar o app.
        }
    }
}
