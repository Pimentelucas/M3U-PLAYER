using System.Text.RegularExpressions;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

/// <summary>
/// Encontra as mídias já baixadas nas pastas de download (inclusive as baixadas em outras vezes que o
/// app foi aberto) para a aba Downloads mostrar tudo, não só o que foi baixado agora.
/// </summary>
public static partial class DownloadScanner
{
    private static readonly HashSet<string> MediaExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".mkv", ".ts", ".avi", ".mov", ".m4v", ".webm", ".wmv", ".flv", ".mpg", ".mpeg",
        ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav"
    };

    /// <summary>"Dexter S02E03 (2).mp4" → "Dexter S02E03" (o sufixo é de nomes repetidos).</summary>
    [GeneratedRegex(@"\s\(\d{1,3}\)$")] // não confunde com o ano: "Filme (2008)"
    private static partial Regex DuplicateSuffix();

    /// <summary>Vídeos e áudios nas pastas (e em uma subpasta abaixo, onde ficam as séries), mais novos primeiro.</summary>
    public static List<FileInfo> Find(IEnumerable<string> folders)
    {
        var files = new Dictionary<string, FileInfo>(StringComparer.OrdinalIgnoreCase);
        foreach (var folder in folders.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                if (!Directory.Exists(folder)) continue;
                var root = new DirectoryInfo(folder);
                var all = root.EnumerateFiles().Concat(root.EnumerateDirectories().SelectMany(d =>
                {
                    try { return d.EnumerateFiles().ToList(); }
                    catch { return []; }
                }));
                foreach (var file in all)
                    if (MediaExtensions.Contains(file.Extension) && file.Length > 0) files[file.FullName] = file;
            }
            catch { /* pasta sem acesso: ignora */ }
        }
        return files.Values.OrderByDescending(f => f.LastWriteTimeUtc).ToList();
    }

    /// <summary>Título a partir do nome do arquivo.</summary>
    public static string TitleOf(string fileName) => DuplicateSuffix().Replace(Path.GetFileNameWithoutExtension(fileName), "").Trim();

    /// <summary>
    /// Item "concluído" para um arquivo já baixado. Usa o título da lista com o mesmo nome (imagem, série,
    /// episódio); se a lista não tem, deduz o episódio pelo nome ("Nome S01E02").
    /// </summary>
    public static DownloadItem ItemFor(FileInfo file, MediaLibrary? library)
    {
        var title = TitleOf(file.Name);
        var entry = library?.FindByFileName(title);
        if (entry == null)
        {
            entry = new PlaylistEntry { Title = title, Url = new Uri(file.FullName).AbsoluteUri, Kind = MediaKind.Movie };
            MediaLibrary.ParseEpisode(entry);
        }
        return new DownloadItem(entry)
        {
            FilePath = file.FullName,
            State = DownloadState.Completed,
            Detail = $"Salvo · {Format.Bytes(file.Length)} · {file.Name}",
            FromDisk = true,
        };
    }
}
