using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Net.Http.Headers;
using LibVLCSharp.Shared;
using M3UPlayer.Models;
using VlcMedia = LibVLCSharp.Shared.Media;

namespace M3UPlayer.Services;

/// <summary>
/// Fila de downloads. Escolhe a estratégia conforme o tipo de mídia:
///  • arquivo HTTP comum (mp4, mkv, mp3, ts...) → download direto com progresso;
///  • HLS (.m3u8) → baixa os segmentos e junta num único arquivo (ver DownloadManager.Hls.cs);
///  • outros protocolos (rtmp, rtsp, udp...) ou HLS com áudio separado → grava pelo VLC.
/// Streams ao vivo são gravados até o usuário clicar em "Parar".
/// </summary>
public sealed partial class DownloadManager(HttpClient http, LibVLC libVLC, int maxParallel)
{
    public const string DefaultUserAgent = "VLC/3.0.21 LibVLC/3.0.21";

    private static readonly object PathLock = new();
    private readonly SemaphoreSlim _slots = new(Math.Max(1, maxParallel));

    public ObservableCollection<DownloadItem> Items { get; } = [];

    public bool HasActiveDownloads => Items.Any(i => i.IsActive);

    /// <summary>Deve ser chamado na thread da interface (altera a coleção observável).</summary>
    public DownloadItem Enqueue(PlaylistEntry entry, string folder)
    {
        var item = new DownloadItem(entry);
        Items.Insert(0, item); // mais recentes primeiro
        _ = RunAsync(item, folder);
        return item;
    }

    /// <summary>
    /// Troca os itens vindos do disco pelos encontrados agora (arquivos baixados em outras vezes).
    /// Arquivos já listados por um download desta sessão não se repetem. Thread da interface.
    /// </summary>
    public void ShowExisting(IReadOnlyList<DownloadItem> found)
    {
        foreach (var old in Items.Where(i => i.FromDisk).ToList()) Items.Remove(old);
        var known = new HashSet<string>(Items.Select(i => i.FilePath).OfType<string>(), StringComparer.OrdinalIgnoreCase);
        foreach (var item in found)
            if (item.FilePath != null && known.Add(item.FilePath)) Items.Add(item);
    }

    public void CancelAll()
    {
        foreach (var item in Items.Where(i => i.IsActive)) item.Cancel();
    }

    /// <summary>Opções de mídia do VLC (user-agent/referer) usadas na reprodução e gravação.</summary>
    public static IEnumerable<string> VlcOptionsFor(PlaylistEntry entry)
    {
        yield return ":network-caching=1500";
        if (!string.IsNullOrWhiteSpace(entry.UserAgent)) yield return $":http-user-agent={entry.UserAgent}";
        if (!string.IsNullOrWhiteSpace(entry.Referrer)) yield return $":http-referrer={entry.Referrer}";
    }

    private async Task RunAsync(DownloadItem item, string folder)
    {
        var ct = item.Cts.Token;
        try
        {
            await _slots.WaitAsync(ct);
        }
        catch (OperationCanceledException)
        {
            item.State = DownloadState.Canceled;
            item.Detail = "Cancelado antes de iniciar";
            return;
        }

        try
        {
            item.State = DownloadState.Running;
            item.Detail = "Conectando...";
            Directory.CreateDirectory(folder);
            await DownloadCoreAsync(item, folder, ct);
            item.State = DownloadState.Completed;
            item.Detail = DescribeFinishedFile(item, "Salvo");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            if (item.IsLiveRecording && FileSize(item.FilePath) > 0)
            {
                item.State = DownloadState.Completed;
                item.Detail = DescribeFinishedFile(item, "Gravação encerrada");
            }
            else
            {
                DeletePartial(item);
                item.State = DownloadState.Canceled;
                item.Detail = "Cancelado";
            }
        }
        catch (Exception ex)
        {
            if (!item.IsLiveRecording || FileSize(item.FilePath) == 0) DeletePartial(item);
            item.State = DownloadState.Failed;
            item.Detail = ex is HttpRequestException { StatusCode: { } status }
                ? $"O servidor respondeu {(int)status} ({status})"
                : ex.Message;
        }
        finally
        {
            _slots.Release();
        }
    }

    private async Task DownloadCoreAsync(DownloadItem item, string folder, CancellationToken ct)
    {
        var entry = item.Entry;
        if (!Uri.TryCreate(entry.Url, UriKind.Absolute, out var uri))
            throw new InvalidOperationException("Endereço inválido: " + entry.Url);

        if (uri.IsFile)
        {
            await CopyLocalFileAsync(item, uri.LocalPath, folder, ct);
            return;
        }

        if (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
        {
            await RecordWithVlcAsync(item, uri, folder, ct);
            return;
        }

        if (LooksLikeHls(uri))
        {
            if (!await DownloadHlsAsync(item, uri, folder, ct))
                await RecordWithVlcAsync(item, uri, folder, ct);
            return;
        }

        using var request = CreateRequest(uri, entry);
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        var finalUri = response.RequestMessage?.RequestUri ?? uri;

        if (IsHlsContentType(response.Content.Headers.ContentType?.MediaType))
        {
            response.Dispose();
            if (!await DownloadHlsAsync(item, finalUri, folder, ct))
                await RecordWithVlcAsync(item, finalUri, folder, ct);
            return;
        }

        await DownloadDirectAsync(item, response, finalUri, folder, ct);
    }

    // ───────────────────────── Download direto ─────────────────────────

    private static async Task DownloadDirectAsync(DownloadItem item, HttpResponseMessage response, Uri uri, string folder, CancellationToken ct)
    {
        var total = response.Content.Headers.ContentLength;
        var ext = GuessExtension(response.Content.Headers, uri);

        item.FilePath = ReservePath(folder, item.Title, ext);
        item.IsLiveRecording = total is null or 0;
        item.IsIndeterminate = item.IsLiveRecording;

        await using var source = await response.Content.ReadAsStreamAsync(ct);
        await using var target = new FileStream(item.FilePath, FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 16, true);

        var buffer = new byte[1 << 16];
        long received = 0;
        var clock = Stopwatch.StartNew();
        var speed = new SpeedMeter();
        long lastReport = 0;
        int read;

        while ((read = await source.ReadAsync(buffer, ct)) > 0)
        {
            await target.WriteAsync(buffer.AsMemory(0, read), ct);
            received += read;

            if (clock.ElapsedMilliseconds - lastReport >= 400)
            {
                lastReport = clock.ElapsedMilliseconds;
                var rate = speed.Update(received);
                if (total is > 0)
                {
                    item.Progress = received * 100.0 / total.Value;
                    item.Detail = $"{Format.Bytes(received)} de {Format.Bytes(total.Value)} · {Format.Bytes(rate)}/s";
                }
                else
                {
                    item.Detail = $"Gravando stream · {Format.Bytes(received)} · {Format.Duration(clock.Elapsed)} · {Format.Bytes(rate)}/s";
                }
            }
        }

        if (total is > 0 && received < total)
            throw new IOException("A conexão foi encerrada antes do fim do arquivo.");
    }

    private static async Task CopyLocalFileAsync(DownloadItem item, string sourcePath, string folder, CancellationToken ct)
    {
        // Só mantém extensões de mídia: um item da lista apontando para um programa (.exe, .bat...) não vira
        // um arquivo executável na pasta de downloads (o botão "Abrir" o executaria).
        var extension = Path.GetExtension(sourcePath);
        if (!IsMediaExtension(extension)) throw new InvalidOperationException("Este arquivo não é um vídeo ou áudio");
        item.FilePath = ReservePath(folder, item.Title, extension.ToLowerInvariant());
        var total = new FileInfo(sourcePath).Length;
        await using var source = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16, true);
        await using var target = new FileStream(item.FilePath, FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 16, true);
        var buffer = new byte[1 << 20];
        long copied = 0;
        int read;
        while ((read = await source.ReadAsync(buffer, ct)) > 0)
        {
            await target.WriteAsync(buffer.AsMemory(0, read), ct);
            copied += read;
            item.Progress = total > 0 ? copied * 100.0 / total : 0;
            item.Detail = $"Copiando · {Format.Bytes(copied)} de {Format.Bytes(total)}";
        }
    }

    // ───────────────────────── Gravação pelo VLC ─────────────────────────

    private async Task RecordWithVlcAsync(DownloadItem item, Uri uri, string folder, CancellationToken ct)
    {
        item.FilePath ??= ReservePath(folder, item.Title, ".ts");
        if (Path.GetExtension(item.FilePath) != ".ts")
        {
            DeletePartial(item);
            item.FilePath = ReservePath(folder, item.Title, ".ts");
        }
        item.IsLiveRecording = true;
        item.IsIndeterminate = true;
        item.Detail = "Gravando via VLC...";

        // O parser de cadeias do VLC trata "\" como escape; barras normais funcionam no Windows.
        var dst = item.FilePath.Replace('\\', '/');
        using var media = new VlcMedia(libVLC, uri);
        foreach (var option in VlcOptionsFor(item.Entry)) media.AddOption(option);
        media.AddOption($":sout=#std{{access=file,mux=ts,dst=\"{dst}\"}}");
        media.AddOption(":sout-keep");

        using var player = new MediaPlayer(media);
        var finished = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        player.EndReached += (_, _) => finished.TrySetResult();
        player.EncounteredError += (_, _) =>
            finished.TrySetException(new InvalidOperationException("O VLC não conseguiu abrir o stream."));
        using var registration = ct.Register(() => finished.TrySetCanceled(ct));

        if (!player.Play()) throw new InvalidOperationException("Falha ao iniciar a gravação no VLC.");

        var clock = Stopwatch.StartNew();
        try
        {
            while (!finished.Task.IsCompleted)
            {
                await Task.WhenAny(finished.Task, Task.Delay(1000, CancellationToken.None));
                var size = FileSize(item.FilePath);
                var length = player.Length;
                if (length > 0 && player.Position > 0)
                {
                    item.IsIndeterminate = false;
                    item.Progress = player.Position * 100.0;
                    item.Detail = $"Gravando via VLC · {Format.Bytes(size)} · {Format.Duration(TimeSpan.FromMilliseconds(player.Time))} de {Format.Duration(TimeSpan.FromMilliseconds(length))}";
                }
                else
                {
                    item.Detail = $"Gravando via VLC · {Format.Bytes(size)} · {Format.Duration(clock.Elapsed)}";
                }
            }
            await finished.Task;
        }
        finally
        {
            // Stop() não pode rodar numa thread de evento do VLC; aqui estamos no thread pool.
            await Task.Run(player.Stop, CancellationToken.None);
        }
    }

    // ───────────────────────── Utilitários ─────────────────────────

    private static HttpRequestMessage CreateRequest(Uri uri, PlaylistEntry entry)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, uri);
        request.Headers.TryAddWithoutValidation("User-Agent",
            string.IsNullOrWhiteSpace(entry.UserAgent) ? DefaultUserAgent : entry.UserAgent);
        if (!string.IsNullOrWhiteSpace(entry.Referrer))
            request.Headers.TryAddWithoutValidation("Referer", entry.Referrer);
        return request;
    }

    private static bool LooksLikeHls(Uri uri) =>
        uri.AbsolutePath.EndsWith(".m3u8", StringComparison.OrdinalIgnoreCase) ||
        uri.Query.Contains("m3u8", StringComparison.OrdinalIgnoreCase);

    private static bool IsHlsContentType(string? mediaType) =>
        mediaType != null && mediaType.Contains("mpegurl", StringComparison.OrdinalIgnoreCase);

    /// <summary>Extensões que o app grava e abre (vídeo, áudio, legenda).</summary>
    public static bool IsMediaExtension(string extension) => MediaExtensions.Contains(extension);

    private static readonly HashSet<string> MediaExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".m4v", ".mkv", ".avi", ".mov", ".webm", ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".wmv", ".3gp",
        ".mp3", ".aac", ".m4a", ".flac", ".ogg", ".oga", ".opus", ".wav", ".wma", ".srt", ".vtt"
    };

    private static readonly Dictionary<string, string> MimeExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ["video/mp4"] = ".mp4", ["video/mp2t"] = ".ts", ["video/x-matroska"] = ".mkv", ["video/webm"] = ".webm",
        ["video/x-msvideo"] = ".avi", ["video/quicktime"] = ".mov", ["video/x-flv"] = ".flv", ["video/mpeg"] = ".mpg",
        ["audio/mpeg"] = ".mp3", ["audio/mp3"] = ".mp3", ["audio/aac"] = ".aac", ["audio/aacp"] = ".aac",
        ["audio/mp4"] = ".m4a", ["audio/ogg"] = ".ogg", ["audio/flac"] = ".flac", ["audio/wav"] = ".wav",
        ["audio/x-wav"] = ".wav", ["audio/webm"] = ".webm"
    };

    private static string GuessExtension(HttpContentHeaders headers, Uri uri)
    {
        var fromDisposition = Path.GetExtension(headers.ContentDisposition?.FileNameStar ?? headers.ContentDisposition?.FileName?.Trim('"') ?? "");
        if (MediaExtensions.Contains(fromDisposition)) return fromDisposition.ToLowerInvariant();

        var fromUrl = Path.GetExtension(uri.AbsolutePath);
        if (MediaExtensions.Contains(fromUrl)) return fromUrl.ToLowerInvariant();

        var mime = headers.ContentType?.MediaType;
        if (mime != null && MimeExtensions.TryGetValue(mime, out var ext)) return ext;

        return ".ts"; // típico de canais IPTV sem extensão na URL
    }

    /// <summary>Escolhe um nome de arquivo livre e cria o arquivo vazio para reservá-lo.</summary>
    internal static string ReservePath(string folder, string title, string extension)
    {
        var baseName = SanitizeFileName(title);
        lock (PathLock)
        {
            var path = Path.Combine(folder, baseName + extension);
            for (int n = 2; File.Exists(path); n++)
                path = Path.Combine(folder, $"{baseName} ({n}){extension}");
            File.Create(path).Dispose();
            return path;
        }
    }

    internal static string SanitizeFileName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var clean = new string(name.Select(c => invalid.Contains(c) ? '_' : c).ToArray()).Trim().TrimEnd('.');
        if (clean.Length > 120) clean = clean[..120].Trim();
        return string.IsNullOrWhiteSpace(clean) ? "midia" : clean;
    }

    private static long FileSize(string? path)
    {
        try { return path != null && File.Exists(path) ? new FileInfo(path).Length : 0; }
        catch { return 0; }
    }

    private static void DeletePartial(DownloadItem item)
    {
        try { if (item.FilePath != null && File.Exists(item.FilePath)) File.Delete(item.FilePath); }
        catch { /* arquivo pode estar em uso; não é crítico */ }
    }

    private static string DescribeFinishedFile(DownloadItem item, string prefix) =>
        $"{prefix} · {Format.Bytes(FileSize(item.FilePath))} · {Path.GetFileName(item.FilePath)}";

    /// <summary>Velocidade média dos últimos segundos.</summary>
    private sealed class SpeedMeter
    {
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private long _lastBytes;
        private double _lastSeconds;
        private double _rate;

        public long Update(long totalBytes)
        {
            var now = _clock.Elapsed.TotalSeconds;
            var dt = now - _lastSeconds;
            if (dt >= 0.3)
            {
                var instant = (totalBytes - _lastBytes) / dt;
                _rate = _rate == 0 ? instant : _rate * 0.7 + instant * 0.3;
                _lastBytes = totalBytes;
                _lastSeconds = now;
            }
            return (long)_rate;
        }
    }
}
