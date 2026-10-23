using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace M3UPlayer.Services;

/// <summary>
/// Carrega as imagens dos cards fora da thread da interface, com:
///  • cache em disco (abrir o app de novo mostra as imagens na hora, sem internet);
///  • cache em memória das imagens já decodificadas no tamanho do card;
///  • URLs do TMDB trocadas pelo tamanho certo da mesma imagem (ver <see cref="Artwork.Optimize"/>);
///  • falhas temporárias (servidor lento, timeout) tentadas de novo; só erros definitivos (404, arquivo
///    que não é imagem) ficam marcados para não tentar outra vez.
/// </summary>
public static class ImageCache
{
    public static string Folder { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "M3UPlayer", "images");

    private const long MaxDiskBytes = 400L * 1024 * 1024;
    private const int MaxMemoryItems = 900;

    private static readonly HttpClient Http = CreateClient();
    private static readonly SemaphoreSlim Gate = new(16);
    private static readonly ConcurrentDictionary<string, Task<byte[]?>> Downloads = new();
    private static readonly ConcurrentDictionary<string, byte> FailedUrls = new();

    // Cache em memória (LRU simples; acessado só pela thread da interface)
    private static readonly Dictionary<string, LinkedListNode<(string Key, ImageSource Image)>> MemoryIndex = new();
    private static readonly LinkedList<(string Key, ImageSource Image)> MemoryOrder = new();

    private static HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler
        {
            MaxConnectionsPerServer = 8,
            ConnectTimeout = TimeSpan.FromSeconds(15),
            PooledConnectionLifetime = TimeSpan.FromMinutes(10),
            AutomaticDecompression = DecompressionMethods.All,
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) M3UPlayer/1.0");
        return client;
    }

    /// <summary>Imagem já decodificada nesse tamanho (para mostrar sem piscar).</summary>
    public static ImageSource? TryGetLoaded(string url, int width)
    {
        if (!MemoryIndex.TryGetValue(width + "|" + url, out var node)) return null;
        MemoryOrder.Remove(node);
        MemoryOrder.AddFirst(node);
        return node.Value.Image;
    }

    /// <summary>Baixa (ou lê do disco) e decodifica a imagem na largura pedida. null se não deu.</summary>
    public static async Task<ImageSource?> LoadAsync(string url, int width)
    {
        if (TryGetLoaded(url, width) is { } loaded) return loaded;
        var source = Artwork.Optimize(url, width);
        var bytes = await GetBytesAsync(source).ConfigureAwait(true);
        if (bytes == null) return null;
        var image = await Task.Run(() => Decode(bytes, width)).ConfigureAwait(true);
        if (image == null)
        {
            FailedUrls[source] = 0;
            return null;
        }
        Remember(width + "|" + url, image);
        return image;
    }

    private static void Remember(string key, ImageSource image)
    {
        if (MemoryIndex.TryGetValue(key, out var existing)) MemoryOrder.Remove(existing);
        MemoryIndex[key] = MemoryOrder.AddFirst((key, image));
        while (MemoryOrder.Count > MaxMemoryItems)
        {
            MemoryIndex.Remove(MemoryOrder.Last!.Value.Key);
            MemoryOrder.RemoveLast();
        }
    }

    private static ImageSource? Decode(byte[] bytes, int width)
    {
        try
        {
            var image = new BitmapImage();
            image.BeginInit();
            image.StreamSource = new MemoryStream(bytes);
            image.DecodePixelWidth = width;
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            image.EndInit();
            image.Freeze();
            return image;
        }
        catch
        {
            return null;
        }
    }

    private static Task<byte[]?> GetBytesAsync(string url)
    {
        if (FailedUrls.ContainsKey(url)) return Task.FromResult<byte[]?>(null);
        // Vários cards com a mesma imagem (episódios, a mesma série em duas linhas) → um download só.
        var task = Downloads.GetOrAdd(url, FetchAsync);
        _ = task.ContinueWith(_ => Downloads.TryRemove(url, out var _), TaskScheduler.Default);
        return task;
    }

    private static async Task<byte[]?> FetchAsync(string url)
    {
        var file = Path.Combine(Folder, Hash(url));
        try
        {
            if (File.Exists(file)) return await File.ReadAllBytesAsync(file).ConfigureAwait(false);
        }
        catch { /* arquivo em uso ou corrompido: baixa de novo */ }

        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return Fail(url);
        if (uri.IsFile)
        {
            try { return await File.ReadAllBytesAsync(uri.LocalPath).ConfigureAwait(false); }
            catch { return Fail(url); }
        }
        if (uri.Scheme is not ("http" or "https")) return Fail(url);

        for (var attempt = 0; attempt < 2; attempt++)
        {
            if (attempt > 0) await Task.Delay(1500).ConfigureAwait(false);
            await Gate.WaitAsync().ConfigureAwait(false);
            try
            {
                using var response = await Http.GetAsync(uri, HttpCompletionOption.ResponseContentRead).ConfigureAwait(false);
                var status = (int)response.StatusCode;
                if (status is >= 400 and < 500 && status != 408 && status != 429) return Fail(url); // não existe
                if (!response.IsSuccessStatusCode) continue;                                         // 5xx: tenta de novo
                var bytes = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                if (bytes.Length < 64) return Fail(url);
                Save(file, bytes);
                return bytes;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
            {
                // Sem resposta ou lento: tenta mais uma vez. Não marca como falha definitiva — ao rolar a
                // tela de novo o card tenta outra vez.
            }
            finally
            {
                Gate.Release();
            }
        }
        return null;
    }

    private static byte[]? Fail(string url)
    {
        FailedUrls[url] = 0;
        return null;
    }

    private static void Save(string file, byte[] bytes)
    {
        try
        {
            Directory.CreateDirectory(Folder);
            var temp = file + "." + Environment.CurrentManagedThreadId + ".tmp";
            File.WriteAllBytes(temp, bytes);
            File.Move(temp, file, overwrite: true);
        }
        catch { /* sem cache em disco: só fica mais lento na próxima vez */ }
    }

    private static string Hash(string url) =>
        Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(url))).ToLowerInvariant() + ".img";

    /// <summary>Mantém o cache em disco abaixo de 400 MB (apaga as imagens usadas há mais tempo).</summary>
    public static void TrimDiskCache() => Task.Run(() =>
    {
        try
        {
            if (!Directory.Exists(Folder)) return;
            var files = new DirectoryInfo(Folder).GetFiles("*.img");
            var total = files.Sum(f => f.Length);
            if (total <= MaxDiskBytes) return;
            foreach (var f in files.OrderBy(f => f.LastWriteTimeUtc))
            {
                total -= f.Length;
                f.Delete();
                if (total <= MaxDiskBytes * 0.8) break;
            }
        }
        catch { }
    });
}
