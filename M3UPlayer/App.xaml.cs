using System.Net;
using System.Windows;
using LibVLCSharp.Shared;
using M3UPlayer.Services;

namespace M3UPlayer;

public partial class App : Application
{
    public static LibVLC LibVLC { get; private set; } = null!;
    public static HttpClient Http { get; } = CreateHttpClient();

    protected override void OnStartup(StartupEventArgs e)
    {
        // Pasta de dados alternativa (testes / modo portátil): M3UPLAYER_DATA=C:\caminho
        var dataFolder = Environment.GetEnvironmentVariable("M3UPLAYER_DATA");
        if (!string.IsNullOrWhiteSpace(dataFolder))
        {
            AppSettings.Folder = dataFolder;
            WatchHistory.FilePath = Path.Combine(dataFolder, "history.json");
            PlaylistLoader.CacheDirectory = Path.Combine(dataFolder, "cache");
            ImageCache.Folder = Path.Combine(dataFolder, "images");
        }
        ImageCache.TrimDiskCache();

        Core.Initialize();
        LibVLC = new LibVLC("--no-video-title-show");

        // Diagnóstico: defina M3UPLAYER_VLC_LOG=C:\caminho\vlc.log para gravar o log do VLC.
        var logPath = Environment.GetEnvironmentVariable("M3UPLAYER_VLC_LOG");
        if (!string.IsNullOrWhiteSpace(logPath))
        {
            var writer = new StreamWriter(logPath, append: false) { AutoFlush = true };
            var sync = new object();
            LibVLC.Log += (_, log) =>
            {
                lock (sync) writer.WriteLine($"{DateTime.Now:HH:mm:ss.fff} [{log.Level}] {log.Module}: {log.Message}");
            };
        }

        DispatcherUnhandledException += (_, args) =>
        {
            LogError(args.Exception);
            MessageBox.Show(args.Exception.Message, "M3U Player — erro inesperado",
                MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) => LogError(args.ExceptionObject as Exception);

        base.OnStartup(e);
        WarnIfPluginPathsTooLong();
    }

    /// <summary>
    /// O libVLC não carrega plugins cujo caminho completo passa de 260 caracteres (limite do Windows);
    /// nesse caso a gravação e alguns formatos falham sem explicação. Avisa o usuário.
    /// </summary>
    private static void WarnIfPluginPathsTooLong()
    {
        try
        {
            var plugins = Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64", "plugins");
            if (!Directory.Exists(plugins)) return;
            var tooLong = Directory.EnumerateFiles(plugins, "*.dll", SearchOption.AllDirectories).Count(f => f.Length >= 260);
            if (tooLong == 0) return;
            MessageBox.Show(
                $"O M3U Player está numa pasta com caminho muito longo:\n{AppContext.BaseDirectory}\n\n" +
                $"{tooLong} componentes do VLC não puderam ser carregados (limite de 260 caracteres do Windows). " +
                "Alguns formatos e a gravação de streams podem falhar.\n\nMova o aplicativo para uma pasta mais curta, como C:\\M3UPlayer.",
                "M3U Player", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        catch
        {
            // Verificação apenas informativa.
        }
    }

    /// <summary>Erros inesperados vão para %APPDATA%\M3UPlayer\error.log (ajuda a diagnosticar).</summary>
    public static void LogError(Exception? ex)
    {
        if (ex == null) return;
        try
        {
            Directory.CreateDirectory(AppSettings.Folder);
            File.AppendAllText(Path.Combine(AppSettings.Folder, "error.log"), $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {ex}\n\n");
        }
        catch
        {
            // sem log, sem problema
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        LibVLC.Dispose();
        base.OnExit(e);
    }

    private static HttpClient CreateHttpClient()
    {
        var handler = new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 10,
            MaxConnectionsPerServer = 16,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5)
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };
        client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", DownloadManager.DefaultUserAgent);
        return client;
    }
}
