using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using M3UPlayer.Models;

namespace M3UPlayer.Services;

/// <summary>
/// Download de streams HLS: escolhe a variante de maior qualidade, baixa os segmentos em paralelo,
/// descriptografa AES-128 quando necessário e grava tudo em sequência num único arquivo (.ts ou .mp4).
/// </summary>
public sealed partial class DownloadManager
{
    private const int ParallelSegments = 6;

    private sealed record HlsKey(string Method, Uri? Uri, byte[]? Iv);
    private sealed record HlsSegment(Uri Uri, long Sequence, HlsKey? Key, Uri? Map);

    private sealed class MediaPlaylist
    {
        public List<HlsSegment> Segments { get; } = [];
        public bool EndList { get; set; }
        public double TargetDuration { get; set; } = 6;
        public bool HasByteRange { get; set; }
    }

    [GeneratedRegex(@"([A-Z0-9\-]+)=(""[^""]*""|[^,]*)")]
    private static partial Regex HlsAttributeRegex();

    /// <returns>false quando o stream precisa ser gravado pelo VLC (áudio separado, byte-range).</returns>
    private async Task<bool> DownloadHlsAsync(DownloadItem item, Uri uri, string folder, CancellationToken ct)
    {
        var entry = item.Entry;
        item.Detail = "Lendo playlist HLS...";
        var (text, playlistUri) = await GetTextAsync(uri, entry, ct);

        if (text.Contains("#EXT-X-STREAM-INF", StringComparison.OrdinalIgnoreCase))
        {
            if (HasSeparateAudio(text)) return false;
            var variant = PickBestVariant(text, playlistUri)
                          ?? throw new InvalidOperationException("Playlist HLS sem variantes válidas.");
            (text, playlistUri) = await GetTextAsync(variant, entry, ct);
        }

        var playlist = ParseMediaPlaylist(text, playlistUri);
        if (playlist.HasByteRange) return false;
        if (playlist.Segments.Count == 0) throw new InvalidOperationException("A playlist HLS não contém segmentos.");
        if (playlist.Segments.Any(s => s.Key != null && s.Key.Method != "AES-128"))
            throw new NotSupportedException("Este stream usa criptografia/DRM não suportada (SAMPLE-AES).");

        var first = playlist.Segments[0];
        var ext = first.Map != null ? ".mp4" : Path.GetExtension(first.Uri.AbsolutePath).ToLowerInvariant() switch
        {
            ".aac" => ".aac",
            ".mp4" or ".m4s" or ".m4v" => ".mp4",
            ".mp3" => ".mp3",
            _ => ".ts"
        };

        item.FilePath = ReservePath(folder, item.Title, ext);
        item.IsLiveRecording = !playlist.EndList;
        item.IsIndeterminate = !playlist.EndList;

        await using var output = new FileStream(item.FilePath, FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 16, true);
        var state = new HlsWriteState(output);
        var clock = Stopwatch.StartNew();

        if (playlist.EndList)
        {
            int total = playlist.Segments.Count;
            await WriteSegmentsAsync(playlist.Segments, entry, state, () =>
            {
                item.Progress = state.Done * 100.0 / total;
                item.Detail = $"{state.Done}/{total} segmentos · {Format.Bytes(state.Bytes)}";
            }, ct);
            return true;
        }

        // Ao vivo: recarrega a playlist periodicamente e grava os novos segmentos até o usuário parar.
        long lastSequence = -1;
        while (true)
        {
            var fresh = playlist.Segments.Where(s => s.Sequence > lastSequence).ToList();
            if (fresh.Count > 0)
            {
                await WriteSegmentsAsync(fresh, entry, state, () =>
                    item.Detail = $"Gravando ao vivo · {Format.Bytes(state.Bytes)} · {Format.Duration(clock.Elapsed)}", ct);
                lastSequence = fresh[^1].Sequence;
            }
            if (playlist.EndList) return true;

            await Task.Delay(TimeSpan.FromSeconds(Math.Clamp(playlist.TargetDuration / 2, 1, 10)), ct);
            (text, _) = await GetTextAsync(playlistUri, entry, ct);
            playlist = ParseMediaPlaylist(text, playlistUri);
        }
    }

    private sealed class HlsWriteState(FileStream output)
    {
        public FileStream Output { get; } = output;
        public ConcurrentDictionary<Uri, Task<byte[]>> Keys { get; } = new();
        public Uri? CurrentMap { get; set; }
        public int Done { get; set; }
        public long Bytes { get; set; }
    }

    /// <summary>Baixa até N segmentos em paralelo, mas grava estritamente na ordem.</summary>
    private async Task WriteSegmentsAsync(IReadOnlyList<HlsSegment> segments, PlaylistEntry entry,
        HlsWriteState state, Action report, CancellationToken ct)
    {
        using var windowCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var window = new Queue<(HlsSegment Segment, Task<byte[]> Data)>();
        int next = 0;
        try
        {
            while (next < segments.Count || window.Count > 0)
            {
                while (window.Count < ParallelSegments && next < segments.Count)
                {
                    var seg = segments[next++];
                    window.Enqueue((seg, FetchSegmentAsync(seg, entry, state, windowCts.Token)));
                }

                var (segment, dataTask) = window.Dequeue();
                var data = await dataTask;

                if (segment.Map != null && segment.Map != state.CurrentMap)
                {
                    var init = await GetBytesWithRetryAsync(segment.Map, entry, ct);
                    await state.Output.WriteAsync(init, ct);
                    state.Bytes += init.Length;
                    state.CurrentMap = segment.Map;
                }

                await state.Output.WriteAsync(data, ct);
                state.Bytes += data.Length;
                state.Done++;
                report();
            }
        }
        finally
        {
            // Se algo falhou, interrompe os downloads ainda em andamento na janela.
            windowCts.Cancel();
            foreach (var pending in window)
                _ = pending.Data.ContinueWith(t => _ = t.Exception, TaskScheduler.Default);
        }
    }

    private async Task<byte[]> FetchSegmentAsync(HlsSegment segment, PlaylistEntry entry, HlsWriteState state, CancellationToken ct)
    {
        Task<byte[]>? keyTask = null;
        if (segment.Key is { Uri: not null } key)
            keyTask = state.Keys.GetOrAdd(key.Uri, u => GetBytesWithRetryAsync(u, entry, CancellationToken.None));

        var data = await GetBytesWithRetryAsync(segment.Uri, entry, ct);
        if (keyTask == null) return data;

        var keyBytes = await keyTask;
        var iv = segment.Key!.Iv ?? SequenceIv(segment.Sequence);
        using var aes = Aes.Create();
        aes.Key = keyBytes;
        return aes.DecryptCbc(data, iv, PaddingMode.PKCS7);
    }

    private async Task<byte[]> GetBytesWithRetryAsync(Uri uri, PlaylistEntry entry, CancellationToken ct)
    {
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                using var request = CreateRequest(uri, entry);
                using var response = await http.SendAsync(request, ct);
                response.EnsureSuccessStatusCode();
                return await response.Content.ReadAsByteArrayAsync(ct);
            }
            catch (Exception ex) when (attempt < 4 && !ct.IsCancellationRequested &&
                                       ex is HttpRequestException or IOException or TaskCanceledException)
            {
                await Task.Delay(TimeSpan.FromSeconds(attempt), ct);
            }
        }
    }

    private async Task<(string Text, Uri FinalUri)> GetTextAsync(Uri uri, PlaylistEntry entry, CancellationToken ct)
    {
        using var request = CreateRequest(uri, entry);
        using var response = await http.SendAsync(request, ct);
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadAsStringAsync(ct), response.RequestMessage?.RequestUri ?? uri);
    }

    private static bool HasSeparateAudio(string master) =>
        master.Split('\n').Any(l =>
            l.StartsWith("#EXT-X-MEDIA:", StringComparison.OrdinalIgnoreCase) &&
            l.Contains("TYPE=AUDIO", StringComparison.OrdinalIgnoreCase) &&
            l.Contains("URI=", StringComparison.OrdinalIgnoreCase));

    private static Uri? PickBestVariant(string master, Uri baseUri)
    {
        Uri? best = null;
        long bestBandwidth = -1;
        long pendingBandwidth = -1;
        bool expectUri = false;

        foreach (var raw in master.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith("#EXT-X-STREAM-INF:", StringComparison.OrdinalIgnoreCase))
            {
                var attrs = ParseHlsAttributes(line[18..]);
                pendingBandwidth = attrs.TryGetValue("BANDWIDTH", out var bw) && long.TryParse(bw, out var b) ? b : 0;
                expectUri = true;
                continue;
            }
            if (line.StartsWith('#')) continue;
            if (expectUri)
            {
                if (pendingBandwidth > bestBandwidth)
                {
                    bestBandwidth = pendingBandwidth;
                    best = new Uri(baseUri, line);
                }
                expectUri = false;
            }
        }
        return best;
    }

    private static MediaPlaylist ParseMediaPlaylist(string text, Uri baseUri)
    {
        var playlist = new MediaPlaylist();
        long sequence = 0;
        HlsKey? key = null;
        Uri? map = null;

        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;

            if (line.StartsWith("#EXT-X-MEDIA-SEQUENCE:", StringComparison.OrdinalIgnoreCase))
                long.TryParse(line[22..], out sequence);
            else if (line.StartsWith("#EXT-X-TARGETDURATION:", StringComparison.OrdinalIgnoreCase))
            {
                if (double.TryParse(line[22..], NumberStyles.Float, CultureInfo.InvariantCulture, out var td))
                    playlist.TargetDuration = td;
            }
            else if (line.StartsWith("#EXT-X-ENDLIST", StringComparison.OrdinalIgnoreCase))
                playlist.EndList = true;
            else if (line.StartsWith("#EXT-X-BYTERANGE", StringComparison.OrdinalIgnoreCase))
                playlist.HasByteRange = true;
            else if (line.StartsWith("#EXT-X-KEY:", StringComparison.OrdinalIgnoreCase))
            {
                var attrs = ParseHlsAttributes(line[11..]);
                var method = attrs.GetValueOrDefault("METHOD", "NONE").ToUpperInvariant();
                key = method == "NONE"
                    ? null
                    : new HlsKey(method,
                        attrs.TryGetValue("URI", out var keyUri) ? new Uri(baseUri, keyUri) : null,
                        attrs.TryGetValue("IV", out var iv) ? ParseIv(iv) : null);
            }
            else if (line.StartsWith("#EXT-X-MAP:", StringComparison.OrdinalIgnoreCase))
            {
                var attrs = ParseHlsAttributes(line[11..]);
                if (attrs.ContainsKey("BYTERANGE")) playlist.HasByteRange = true;
                map = attrs.TryGetValue("URI", out var mapUri) ? new Uri(baseUri, mapUri) : null;
            }
            else if (!line.StartsWith('#'))
            {
                playlist.Segments.Add(new HlsSegment(new Uri(baseUri, line), sequence++, key, map));
            }
        }
        return playlist;
    }

    private static Dictionary<string, string> ParseHlsAttributes(string list)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (Match m in HlsAttributeRegex().Matches(list))
            result[m.Groups[1].Value] = m.Groups[2].Value.Trim('"');
        return result;
    }

    private static byte[] ParseIv(string hex)
    {
        hex = hex.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? hex[2..] : hex;
        var bytes = Convert.FromHexString(hex.PadLeft(32, '0'));
        return bytes.Length == 16 ? bytes : bytes[^16..];
    }

    private static byte[] SequenceIv(long sequence)
    {
        var iv = new byte[16];
        BinaryPrimitives.WriteInt64BigEndian(iv.AsSpan(8), sequence);
        return iv;
    }
}
