using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using M3UPlayer.Models;
using M3UPlayer.Services;
using Microsoft.Win32;

namespace M3UPlayer;

/// <summary>Item da lista "Listas salvas" no painel de listas.</summary>
public sealed record SavedSourceView(string Source, string Display, bool IsActive);

/// <summary>
/// Janela principal. Dividida em partes:
///  • este arquivo: inicialização, listas M3U, navegação, avisos e downloads;
///  • MainWindow.Library.cs: biblioteca no estilo Stremio (linhas, grade, séries);
///  • MainWindow.Player.cs: player, controles que se escondem e histórico de reprodução.
/// </summary>
public partial class MainWindow : Window
{
    private enum Page { Library, Series, Downloads, Player }

    private readonly AppSettings _settings = AppSettings.Load();
    private readonly WatchHistory _history = WatchHistory.Load();
    private readonly LibVLC _libVLC = App.LibVLC;
    private readonly MediaPlayer _player;
    private readonly DownloadManager _downloads;
    private readonly DispatcherTimer _searchTimer;
    private readonly DispatcherTimer _toastTimer;

    private MediaLibrary? _library;
    private List<DownloadGroup> _downloadGroups = [];
    private CancellationTokenSource? _loadCts;
    private Page _page = Page.Library;

    public MainWindow()
    {
        InitializeComponent();

        _player = new MediaPlayer(_libVLC);
        _downloads = new DownloadManager(App.Http, _libVLC, _settings.MaxParallelDownloads);
        // A aba Downloads mostra grupos (episódios da mesma série juntos), refeitos quando a lista muda.
        _downloads.Items.CollectionChanged += (_, e) =>
        {
            foreach (DownloadItem item in e.NewItems ?? Array.Empty<DownloadItem>())
                item.PropertyChanged += (_, a) =>
                {
                    if (a.PropertyName != nameof(DownloadItem.State)) return;
                    Dispatcher.BeginInvoke(() =>
                    {
                        UpdateDownloadBadge();
                        foreach (var group in _downloadGroups) group.Refresh();
                    });
                };
            _downloadGroups = DownloadGroup.Build(_downloads.Items);
            DownloadList.ItemsSource = _downloadGroups;
            UpdateDownloadBadge();
        };

        _searchTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(300) };
        _searchTimer.Tick += (_, _) =>
        {
            _searchTimer.Stop();
            _query = SearchBox.Text.Trim();
            RefreshLibraryView();
        };
        _toastTimer = new DispatcherTimer();
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer.Stop();
            Toast.Visibility = Visibility.Collapsed;
            PlayerToast.Visibility = Visibility.Collapsed;
        };

        VolumeSlider.Value = _settings.Volume;
        InitPlayer();
        UpdateFolderText();
        RefreshSavedSources();
        UpdateDownloadBadge();
    }

    // ───────────────────────── Janela ─────────────────────────

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // Barra de título escura (Windows 10 20H1+ usa o atributo 20; versões anteriores, 19).
        var hwnd = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        var dark = 1;
        if (DwmSetWindowAttribute(hwnd, 20, ref dark, sizeof(int)) != 0)
            DwmSetWindowAttribute(hwnd, 19, ref dark, sizeof(int));
    }

    private void Window_Loaded(object sender, RoutedEventArgs e)
    {
        RefreshDownloadedFiles();
        VideoView.MediaPlayer = _player;
        // A janela dos controles é criada quando o VideoView carrega; esconde-a até o player abrir.
        Dispatcher.BeginInvoke(SyncPlayerOverlay, DispatcherPriority.ContextIdle);
        Activated += (_, _) => SyncPlayerOverlay();
        // Trocou de janela com a prévia aberta: pausa (e volta a tocar ao voltar)
        Deactivated += (_, _) => _previewPlayer?.SetPause(true);
        Activated += (_, _) => { if (IsPreviewOpen) _previewPlayer?.SetPause(false); };

        var args = Environment.GetCommandLineArgs();
        var startup = args.Length > 1 ? args[1] : _settings.LastSource;
        if (string.IsNullOrWhiteSpace(startup))
        {
            ShowMessage("", "Nenhuma lista ainda",
                "Adicione a URL ou o arquivo da sua lista M3U. Ela fica salva e abre sozinha nas próximas vezes.", showButton: true);
            return;
        }
        _ = OpenSourceAsync(startup);
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_downloads.HasActiveDownloads)
        {
            var answer = MessageBox.Show(this,
                "Há downloads em andamento. Se sair agora eles serão interrompidos.\n\nDeseja sair mesmo assim?",
                "M3U Player", MessageBoxButton.YesNo, MessageBoxImage.Warning);
            if (answer != MessageBoxResult.Yes)
            {
                e.Cancel = true;
                return;
            }
            _downloads.CancelAll();
        }

        SaveCurrentPosition();
        _settings.Volume = (int)VolumeSlider.Value;
        _settings.Save();

        _player.Stop();
        VideoView.MediaPlayer = null;
        _player.Dispose();
    }

    private void Window_Drop(object sender, DragEventArgs e)
    {
        string? source = null;
        if (e.Data.GetData(DataFormats.FileDrop) is string[] { Length: > 0 } files) source = files[0];
        else if (e.Data.GetData(DataFormats.UnicodeText) is string text) source = text.Trim();
        if (!string.IsNullOrWhiteSpace(source)) _ = OpenSourceAsync(source);
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (_page == Page.Player)
        {
            HandlePlayerKey(e);
            return;
        }
        if (IsPreviewOpen)
        {
            HandlePreviewKey(e);
            return;
        }

        var ctrl = Keyboard.Modifiers == ModifierKeys.Control;
        if (Keyboard.FocusedElement is TextBox box)
        {
            if (e.Key == Key.Escape && box == SearchBox && SearchBox.Text.Length > 0)
            {
                SearchBox.Text = "";
                e.Handled = true;
            }
            return;
        }

        if (ctrl && e.Key == Key.F) { SearchBox.Focus(); e.Handled = true; }
        else if (ctrl && e.Key == Key.O) { BrowseForPlaylist(); e.Handled = true; }
        else if (e.Key == Key.Escape && _page == Page.Series) { ShowPage(Page.Library); e.Handled = true; }
    }

    // ───────────────────────── Listas M3U ─────────────────────────

    /// <summary>
    /// Abre uma lista: usa a cópia salva (instantâneo) quando existe e atualiza em segundo plano
    /// se ela estiver velha; sem cópia, baixa. Se a rede falhar, cai para a cópia salva.
    /// </summary>
    private async Task OpenSourceAsync(string source, bool forceRefresh = false)
    {
        source = source.Trim().Trim('"');
        if (source.Length == 0) return;

        _loadCts?.Cancel();
        var cts = _loadCts = new CancellationTokenSource();
        _settings.RememberSource(source);
        RefreshSavedSources();
        SourceBox.Text = source;

        var isUrl = PlaylistLoader.IsUrl(source);
        var cachedAt = isUrl ? PlaylistLoader.CacheTime(source) : null;

        try
        {
            if (cachedAt != null && !forceRefresh)
            {
                if (_library == null) ShowLoading("Abrindo sua lista...", "Usando a cópia salva no computador");
                var cached = await PlaylistLoader.LoadFromCacheAsync(source, cts.Token);
                if (!await ApplyEntriesAsync(cached.Entries, cts)) return;
                SetListStatus(cached.Entries.Count, cachedAt);

                if (DateTime.Now - cachedAt.Value > TimeSpan.FromHours(_settings.RefreshHours))
                    _ = RefreshInBackgroundAsync(source, cached.Entries.Count, cts);
                return;
            }

            ShowLoading(isUrl ? "Baixando sua lista..." : "Abrindo sua lista...", isUrl ? "Isso só é demorado na primeira vez" : "");
            ListStatusText.Text = "Carregando...";
            var progress = new Progress<long>(bytes =>
            {
                if (cts.IsCancellationRequested) return;
                MessageText.Text = $"{Format.Bytes(bytes)} baixados";
                ListStatusText.Text = $"Baixando... {Format.Bytes(bytes)}";
            });
            var result = await PlaylistLoader.LoadAsync(source, App.Http, progress, cts.Token);
            if (!await ApplyEntriesAsync(result.Entries, cts)) return;
            SetListStatus(result.Entries.Count, result.CachedAt);
            if (forceRefresh) ShowToast($"Lista atualizada · {result.Entries.Count:N0} itens");
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
        }
        catch (Exception ex)
        {
            if (cts.IsCancellationRequested) return;
            if (isUrl && PlaylistLoader.CacheTime(source) is { } fallbackTime)
            {
                try
                {
                    var cached = await PlaylistLoader.LoadFromCacheAsync(source, cts.Token);
                    if (await ApplyEntriesAsync(cached.Entries, cts))
                    {
                        SetListStatus(cached.Entries.Count, fallbackTime);
                        ShowToast($"Não foi possível atualizar a lista ({ex.Message}). Usando a cópia de {fallbackTime:dd/MM HH:mm}.", 6);
                    }
                    return;
                }
                catch
                {
                    // segue para a mensagem de erro
                }
            }
            ListStatusText.Text = "Falha ao carregar: " + ex.Message;
            if (_library == null)
                ShowMessage("", "Não foi possível carregar a lista", ex.Message, showButton: true);
            else
                ShowToast("Não foi possível carregar a lista: " + ex.Message, 6);
        }
    }

    private async Task RefreshInBackgroundAsync(string source, int previousCount, CancellationTokenSource cts)
    {
        try
        {
            ListStatusText.Text += " · atualizando...";
            var result = await PlaylistLoader.LoadAsync(source, App.Http, null, cts.Token);
            if (cts.IsCancellationRequested) return;
            if (result.Entries.Count != previousCount)
            {
                if (!await ApplyEntriesAsync(result.Entries, cts)) return;
                ShowToast($"Lista atualizada · {result.Entries.Count:N0} itens");
            }
            SetListStatus(result.Entries.Count, result.CachedAt);
        }
        catch
        {
            if (!cts.IsCancellationRequested) SetListStatus(_library?.Entries.Count ?? 0, PlaylistLoader.CacheTime(source));
        }
    }

    /// <summary>Organiza os itens (em segundo plano) e mostra a biblioteca.</summary>
    private async Task<bool> ApplyEntriesAsync(List<PlaylistEntry> entries, CancellationTokenSource cts)
    {
        var library = await Task.Run(() => MediaLibrary.Build(entries));
        if (cts.IsCancellationRequested) return false;

        _library = library;
        _chipsKey = null;
        RefreshDownloadedFiles(); // agora dá para mostrar a imagem/série dos arquivos já baixados
        ApplyHistoryToLibrary();
        TabLive.Visibility = library.HasKind(MediaKind.Live) ? Visibility.Visible : Visibility.Collapsed;
        TabMovies.Visibility = library.HasKind(MediaKind.Movie) ? Visibility.Visible : Visibility.Collapsed;
        TabSeries.Visibility = library.HasKind(MediaKind.Series) ? Visibility.Visible : Visibility.Collapsed;
        HideMessage();
        RefreshLibraryView();

        // Testes: M3UPLAYER_AUTOPREVIEW=<título> abre a prévia desse card sem precisar clicar.
        if (Environment.GetEnvironmentVariable("M3UPLAYER_AUTOPREVIEW") is { Length: > 0 } previewTitle)
            _ = Dispatcher.BeginInvoke(DispatcherPriority.ContextIdle, () =>
            {
                if (FindCard(this, previewTitle) is { } card) OpenPreview(card);
            });
        return true;
    }

    private void SetListStatus(int count, DateTime? updatedAt)
    {
        var when = updatedAt is not { } t ? ""
            : (DateTime.Now - t).TotalMinutes < 2 ? " · atualizada agora"
            : (DateTime.Now - t).TotalHours < 24 ? $" · atualizada há {Math.Max(1, (int)(DateTime.Now - t).TotalHours)} h"
            : $" · atualizada em {t:dd/MM HH:mm}";
        ListStatusText.Text = $"{count:N0} itens · {_library?.Groups.Count ?? 0} grupos{when}";
    }

    private void RefreshSavedSources()
    {
        SavedSourcesList.ItemsSource = _settings.SavedSources
            .Select(s => new SavedSourceView(s, DisplaySource(s), string.Equals(s, _settings.LastSource, StringComparison.OrdinalIgnoreCase)))
            .ToList();
    }

    /// <summary>Mostra a URL sem a query (que costuma ter usuário e senha do IPTV).</summary>
    private static string DisplaySource(string source)
    {
        if (PlaylistLoader.IsUrl(source) && Uri.TryCreate(source, UriKind.Absolute, out var u))
            return u.Host + (u.AbsolutePath.Length > 1 ? u.AbsolutePath : "") + (u.Query.Length > 0 ? "?…" : "");
        return $"{Path.GetFileName(source)}  —  {Path.GetDirectoryName(source)}";
    }

    private void ListsButton_Click(object sender, RoutedEventArgs e)
    {
        SourceBox.Text = _settings.LastSource ?? "";
        ListsPopup.IsOpen = true;
        SourceBox.Focus();
        SourceBox.SelectAll();
    }

    private void LoadButton_Click(object sender, RoutedEventArgs e)
    {
        ListsPopup.IsOpen = false;
        _ = OpenSourceAsync(SourceBox.Text);
    }

    private void SourceBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        ListsPopup.IsOpen = false;
        _ = OpenSourceAsync(SourceBox.Text);
    }

    private void RefreshButton_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_settings.LastSource)) return;
        _ = OpenSourceAsync(_settings.LastSource, forceRefresh: true);
    }

    private void BrowseButton_Click(object sender, RoutedEventArgs e) => BrowseForPlaylist();

    private void BrowseForPlaylist()
    {
        ListsPopup.IsOpen = false;
        var dialog = new OpenFileDialog
        {
            Title = "Abrir lista M3U",
            Filter = "Listas M3U (*.m3u;*.m3u8)|*.m3u;*.m3u8|Todos os arquivos (*.*)|*.*"
        };
        if (dialog.ShowDialog(this) == true) _ = OpenSourceAsync(dialog.FileName);
    }

    private void SavedSource_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not SavedSourceView view) return;
        ListsPopup.IsOpen = false;
        _ = OpenSourceAsync(view.Source);
    }

    private void ForgetSource_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not SavedSourceView view) return;
        _settings.ForgetSource(view.Source);
        RefreshSavedSources();
    }

    /// <summary>Botões "Alterar": Tag "series" escolhe a pasta das séries; os outros, a dos filmes e canais.</summary>
    private void ChooseFolder_Click(object sender, RoutedEventArgs e)
    {
        ListsPopup.IsOpen = false;
        var series = (sender as FrameworkElement)?.Tag is "series";
        var current = series ? _settings.SeriesFolderOrDefault : _settings.DownloadFolder;
        var dialog = new OpenFolderDialog
        {
            Title = series ? "Escolha a pasta das séries" : "Escolha a pasta dos filmes e canais",
            InitialDirectory = Directory.Exists(current) ? current : null
        };
        if (dialog.ShowDialog(this) != true) return;
        if (series) _settings.SeriesFolder = dialog.FolderName;
        else _settings.DownloadFolder = dialog.FolderName;
        _settings.Save();
        UpdateFolderText();
        RefreshDownloadedFiles();
        ShowToast((series ? "Os próximos episódios vão para " : "Os próximos filmes e canais vão para ") + dialog.FolderName);
    }

    private void UpdateFolderText()
    {
        FolderText.Text = _settings.DownloadFolder;
        SeriesFolderText.Text = _settings.SeriesFolderOrDefault;
        DownloadFolderText.Text = _settings.DownloadFolder;
        DownloadSeriesFolderText.Text = Path.Combine(_settings.SeriesFolderOrDefault, "<nome da série>");
    }

    // ───────────────────────── Navegação ─────────────────────────

    private void ShowPage(Page page)
    {
        ClosePreview();
        if (page == Page.Downloads && _page != Page.Downloads) RefreshDownloadedFiles();
        _page = page;
        var player = page == Page.Player;
        Shell.Visibility = player ? Visibility.Collapsed : Visibility.Visible;
        PlayerPage.Visibility = player ? Visibility.Visible : Visibility.Collapsed;
        LibraryPage.Visibility = page == Page.Library ? Visibility.Visible : Visibility.Collapsed;
        SeriesPage.Visibility = page == Page.Series ? Visibility.Visible : Visibility.Collapsed;
        DownloadsPage.Visibility = page == Page.Downloads ? Visibility.Visible : Visibility.Collapsed;
        if (page == Page.Downloads) UpdateDownloadBadge();
        if (!player) Title = "M3U Player";
        SyncPlayerOverlay();
    }

    /// <summary>
    /// Os controles do player ficam numa janela transparente separada, sobre o vídeo (o LibVLCSharp
    /// faz isso porque o vídeo é desenhado num HWND nativo). Esconder a página do player não esconde
    /// essa janela, então ela é mostrada/escondida aqui — senão ficaria por cima da biblioteca,
    /// bloqueando os cliques.
    /// </summary>
    private void SyncPlayerOverlay()
    {
        var visible = _page == Page.Player;
        Overlay.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        if (Window.GetWindow(Overlay) is { } overlayWindow && overlayWindow != this)
        {
            if (visible && !overlayWindow.IsVisible) overlayWindow.Show();
            else if (!visible && overlayWindow.IsVisible) overlayWindow.Hide();
        }
    }

    private void ShowLoading(string title, string text)
    {
        ShowMessage("", title, text, showButton: false);
        MessageProgress.Visibility = Visibility.Visible;
    }

    private void ShowMessage(string icon, string title, string text, bool showButton)
    {
        MessageIcon.Text = icon;
        MessageTitle.Text = title;
        MessageText.Text = text;
        MessageProgress.Visibility = Visibility.Collapsed;
        MessageButton.Visibility = showButton ? Visibility.Visible : Visibility.Collapsed;
        MessagePanel.Visibility = Visibility.Visible;
    }

    private void HideMessage() => MessagePanel.Visibility = Visibility.Collapsed;

    /// <summary>Aviso rápido no rodapé (ou dentro do player, quando ele está aberto).</summary>
    private void ShowToast(string text, double seconds = 3.5)
    {
        if (_page == Page.Player)
        {
            PlayerToastText.Text = text;
            PlayerToast.Visibility = Visibility.Visible;
        }
        else
        {
            ToastText.Text = text;
            Toast.Visibility = Visibility.Visible;
        }
        _toastTimer.Stop();
        _toastTimer.Interval = TimeSpan.FromSeconds(seconds);
        _toastTimer.Start();
    }

    // ───────────────────────── Downloads ─────────────────────────

    private void Enqueue(PlaylistEntry entry, bool quiet = false)
    {
        _downloads.Enqueue(entry, _settings.FolderFor(entry));
        if (!quiet) ShowToast($"Baixando: {entry.Title}  ·  acompanhe em Downloads");
    }

    private void UpdateDownloadBadge()
    {
        var active = _downloads.Items.Count(i => i.IsActive);
        DownloadBadge.Visibility = active > 0 ? Visibility.Visible : Visibility.Collapsed;
        DownloadBadgeText.Text = active.ToString();
        DownloadsEmpty.Visibility = _downloads.Items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void CancelDownload_Click(object sender, RoutedEventArgs e) => ItemOf(sender)?.Cancel();

    private void OpenDownload_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender)?.FilePath is not { } path || !File.Exists(path)) return;
        // Só abre (com o programa padrão do Windows) vídeos/áudios; qualquer outra coisa é mostrada na pasta.
        if (DownloadManager.IsMediaExtension(Path.GetExtension(path)))
            Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        else
            Process.Start("explorer.exe", $"/select,\"{path}\"");
    }

    private void RevealDownload_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender)?.FilePath is { } path && File.Exists(path))
            Process.Start("explorer.exe", $"/select,\"{path}\"");
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(_settings.DownloadFolder);
        Process.Start(new ProcessStartInfo(_settings.DownloadFolder) { UseShellExecute = true });
    }

    /// <summary>Tira da lista os downloads que falharam ou foram cancelados (os concluídos continuam: são arquivos na pasta).</summary>
    private void ClearFinished_Click(object sender, RoutedEventArgs e)
    {
        foreach (var item in _downloads.Items.Where(i => i.State is DownloadState.Failed or DownloadState.Canceled).ToList())
            _downloads.Items.Remove(item);
        UpdateDownloadBadge();
    }

    private int _scanVersion;

    /// <summary>
    /// Mostra na aba Downloads tudo o que já está nas pastas de download (filmes e séries), inclusive o
    /// baixado em outras vezes que o app foi aberto. A leitura das pastas é feita fora da interface.
    /// </summary>
    private async void RefreshDownloadedFiles()
    {
        var version = ++_scanVersion;
        string[] folders = [_settings.DownloadFolder, _settings.SeriesFolderOrDefault];
        var library = _library;
        var found = await Task.Run(() => DownloadScanner.Find(folders).Select(f => DownloadScanner.ItemFor(f, library)).ToList());
        if (version != _scanVersion) return; // uma leitura mais nova já começou
        _downloads.ShowExisting(found);
        UpdateDownloadBadge();
    }

    private static DownloadItem? ItemOf(object sender) => (sender as FrameworkElement)?.DataContext as DownloadItem;
}
