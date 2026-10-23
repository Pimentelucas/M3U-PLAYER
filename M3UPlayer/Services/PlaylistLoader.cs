using System.Security.Cryptography;
using System.Text;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

public enum PlaylistOrigin { LocalFile, Network, Cache }

public sealed record PlaylistLoadResult(List<PlaylistEntry> Entries, PlaylistOrigin Origin, DateTime? CachedAt);

/// <summary>
/// Carrega listas M3U de arquivo ou URL. Listas baixadas são guardadas numa cópia local
/// (%APPDATA%\M3UPlayer\cache), o que permite abrir o app instantaneamente e funcionar offline.
/// </summary>
public static class PlaylistLoader
{
    public static string CacheDirectory { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "M3UPlayer", "cache");

    public static bool IsUrl(string source) =>
        source.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
        source.StartsWith("https://", StringComparison.OrdinalIgnoreCase);

    public static string CachePathFor(string source)
    {
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(source.Trim())))[..24];
        return Path.Combine(CacheDirectory, hash + ".m3u");
    }

    public static DateTime? CacheTime(string source)
    {
        var path = CachePathFor(source);
        return File.Exists(path) ? File.GetLastWriteTime(path) : null;
    }

    /// <summary>Lê a cópia local de uma lista baixada anteriormente.</summary>
    public static async Task<PlaylistLoadResult> LoadFromCacheAsync(string source, CancellationToken ct)
    {
        var path = CachePathFor(source);
        var entries = await Task.Run(() => ParseFile(path, new Uri(source.Trim())), ct);
        return new PlaylistLoadResult(entries, PlaylistOrigin.Cache, File.GetLastWriteTime(path));
    }

    /// <summary>
    /// Carrega do arquivo local ou baixa da URL (atualizando a cópia local).
    /// <paramref name="progress"/> recebe os bytes já baixados.
    /// </summary>
    public static async Task<PlaylistLoadResult> LoadAsync(string source, HttpClient http,
        IProgress<long>? progress, CancellationToken ct)
    {
        source = source.Trim();
        if (!IsUrl(source))
        {
            var path = Uri.TryCreate(source, UriKind.Absolute, out var u) && u.IsFile ? u.LocalPath : Path.GetFullPath(source);
            var local = await Task.Run(() => ParseFile(path, new Uri(path)), ct);
            return new PlaylistLoadResult(local, PlaylistOrigin.LocalFile, null);
        }

        var finalUri = await DownloadToCacheAsync(source, http, progress, ct);
        var cachePath = CachePathFor(source);
        var entries = await Task.Run(() => ParseFile(cachePath, finalUri), ct);
        return new PlaylistLoadResult(entries, PlaylistOrigin.Network, DateTime.Now);
    }

    /// <summary>Baixa direto para o disco (sem limite de tempo total), depois troca a cópia antiga.</summary>
    private static async Task<Uri> DownloadToCacheAsync(string url, HttpClient http, IProgress<long>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(CacheDirectory);
        var target = CachePathFor(url);
        var temp = target + ".download";

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        // Vários servidores IPTV devolvem uma página de erro para clientes desconhecidos.
        request.Headers.TryAddWithoutValidation("User-Agent", DownloadManager.DefaultUserAgent);
        // ResponseHeadersRead: o timeout do HttpClient vale só até os cabeçalhos; listas grandes podem levar minutos.
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();

        await using (var source = await response.Content.ReadAsStreamAsync(ct))
        await using (var file = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, true))
        {
            var buffer = new byte[1 << 16];
            long total = 0;
            int read;
            while ((read = await source.ReadAsync(buffer, ct)) > 0)
            {
                await file.WriteAsync(buffer.AsMemory(0, read), ct);
                total += read;
                progress?.Report(total);
            }
        }

        if (!LooksLikePlaylist(temp))
        {
            File.Delete(temp);
            throw new InvalidDataException("O servidor não devolveu uma lista M3U (resposta parece uma página de erro). Confira a URL, usuário e senha.");
        }

        // Só substitui a cópia salva quando o download veio completo e válido.
        File.Move(temp, target, overwrite: true);
        return response.RequestMessage?.RequestUri ?? new Uri(url);
    }

    /// <summary>Rejeita páginas HTML/JSON de erro que alguns servidores devolvem com status 200.</summary>
    internal static bool LooksLikePlaylist(string path)
    {
        var buffer = new byte[4096];
        int length;
        using (var fs = File.OpenRead(path)) length = fs.Read(buffer, 0, buffer.Length);
        var head = Encoding.UTF8.GetString(buffer, 0, length).TrimStart('﻿', ' ', '\r', '\n', '\t');
        if (head.Length == 0) return false;
        if (head.StartsWith("#EXTM3U", StringComparison.OrdinalIgnoreCase) || head.Contains("#EXTINF", StringComparison.OrdinalIgnoreCase))
            return true;
        if (head.StartsWith('<') || head.StartsWith('{') || head.StartsWith('[')) return false;
        // Lista simples (só URLs, sem #EXTM3U)
        return head.Contains("://", StringComparison.Ordinal) || head.Contains('\\') || head.Contains('/');
    }

    private static List<PlaylistEntry> ParseFile(string path, Uri baseUri)
    {
        var encoding = DetectEncoding(path);

        // Arquivo que é na verdade um stream HLS (playlist de segmentos): vira um único item.
        using (var headReader = new StreamReader(path, encoding, detectEncodingFromByteOrderMarks: true))
        {
            var head = new char[8192];
            var headLength = headReader.ReadBlock(head, 0, head.Length);
            if (M3UParser.IsHlsStream(new string(head, 0, headLength)))
            {
                var url = baseUri.IsFile ? baseUri.LocalPath : baseUri.AbsoluteUri;
                return [new PlaylistEntry { Title = M3UParser.TitleFromUrl(url), Url = url }];
            }
        }

        using var reader = new StreamReader(path, encoding, detectEncodingFromByteOrderMarks: true, bufferSize: 1 << 16);
        return M3UParser.Parse(reader, baseUri);
    }

    /// <summary>UTF-8 na grande maioria; se o começo do arquivo não for UTF-8 válido, usa Latin-1.</summary>
    private static Encoding DetectEncoding(string path)
    {
        var buffer = new byte[1 << 20];
        int length;
        using (var fs = File.OpenRead(path)) length = fs.Read(buffer, 0, buffer.Length);
        // Ignora os últimos bytes, que podem cortar um caractere multibyte ao meio.
        var check = Math.Max(0, length - 4);
        try
        {
            new UTF8Encoding(false, true).GetCharCount(buffer, 0, check);
            return new UTF8Encoding(false);
        }
        catch (DecoderFallbackException)
        {
            return Encoding.Latin1;
        }
    }
}
