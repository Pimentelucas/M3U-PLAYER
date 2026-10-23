using System.Globalization;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using M3UPlayer.Models;
using M3UPlayer.Services;
using VlcMedia = LibVLCSharp.Shared.Media;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace M3UPlayer;

/// <summary>
/// Prévia estilo Netflix: clicar numa mídia abre um card grande (≈ metade da janela) que toca trechos
/// dela com som. Clicar de novo no card abre a mídia; clicar fora (ou Esc) volta para a lista.
/// Se o usuário desligar o som numa prévia, as próximas abrem sem som (fica salvo nas configurações).
///
/// O vídeo da prévia não usa o VideoView: o VLC desenha os quadros num buffer e eles são copiados
/// para um WriteableBitmap. Assim o vídeo fica dentro do card (com cantos arredondados e animação),
/// sem a janela nativa por cima que o VideoView precisa.
/// </summary>
public partial class MainWindow
{
    private const int PreviewWidth = 960;
    private const int PreviewHeight = 540;
    private static readonly TimeSpan SnippetLength = TimeSpan.FromSeconds(7);
    private static readonly float[] SnippetPositions = [0.12f, 0.33f, 0.55f, 0.75f];

    private DispatcherTimer? _snippetTimer;
    private MediaItem? _previewItem;
    private PlaylistEntry? _previewEntry;
    private MediaPlayer? _previewPlayer;
    private int _snippetIndex;
    private bool _previewHasFrame;

    // Buffer onde o VLC escreve os quadros (alocado uma vez) e as callbacks (guardadas para o GC não coletar).
    private IntPtr _frameBuffer;
    private WriteableBitmap? _previewBitmap;
    private MediaPlayer.LibVLCVideoLockCb? _lockCb;
    private MediaPlayer.LibVLCVideoDisplayCb? _displayCb;
    private int _framePending;

    private bool IsPreviewOpen => PreviewLayer.Visibility == Visibility.Visible;

    // ───────────────────────── Abrir / fechar ─────────────────────────

    private void OpenPreview(FrameworkElement card)
    {
        if (card.DataContext is not MediaItem item) return;
        ClosePreview();

        _previewItem = item;
        _previewEntry = item switch
        {
            SeriesInfo series => SeriesResumeTarget(series).Episode,
            PlaylistEntry entry => entry,
            _ => null,
        };

        var record = _previewEntry != null ? _history.Get(_previewEntry.Url) : null;
        var description = MediaDescriber.Describe(item, record, item is SeriesInfo ? _previewEntry : null);
        PreviewTitle.Text = item.Title;
        PreviewMeta.Text = description.Meta;
        PreviewMeta.Visibility = description.Meta.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        PreviewPlayText.Text = description.PlayLabel;
        PreviewInitials.Text = item.Initials;
        Art.Show(PreviewArt, item.Logo, 780, fit: item.IsSquare);
        PreviewLive.Visibility = item.Kind == MediaKind.Live ? Visibility.Visible : Visibility.Collapsed;
        PreviewDownloadButton.Visibility = item is SeriesInfo ? Visibility.Collapsed : Visibility.Visible;
        PreviewEpisodesButton.Visibility = item is SeriesInfo ? Visibility.Visible : Visibility.Collapsed;
        var progress = record is { CanResume: true } ? record.Progress : 0;
        PreviewProgress.Value = progress;
        PreviewProgress.Visibility = progress > 0 ? Visibility.Visible : Visibility.Collapsed;
        PreviewVideoHost.BeginAnimation(OpacityProperty, null);
        PreviewVideoHost.Opacity = 0;
        _previewHasFrame = false;
        UpdateMuteGlyph();

        SizePreviewCard();
        PreviewLayer.Visibility = Visibility.Visible;
        Keyboard.Focus(PreviewCard);

        // Cresce a partir do tamanho do card clicado
        var from = Math.Clamp(card.ActualWidth / PreviewCard.Width, 0.3, 0.9);
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var grow = new DoubleAnimation(from, 1, TimeSpan.FromMilliseconds(240)) { EasingFunction = ease };
        PreviewScale.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
        PreviewScale.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
        PreviewLayer.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180)));

        if (_previewEntry != null) StartPreviewVideo(_previewEntry);
    }

    private void ClosePreview()
    {
        _snippetTimer?.Stop();
        PreviewLayer.Visibility = Visibility.Collapsed;
        _previewItem = null;
        _previewEntry = null;
        StopPreviewVideo();
    }

    /// <summary>Card com cerca de metade da largura da janela (vídeo 16:9 + informações), sem passar da altura.</summary>
    private void SizePreviewCard()
    {
        var width = Math.Max(520, ActualWidth * 0.5);
        var available = PreviewLayer.ActualHeight > 0 ? PreviewLayer.ActualHeight : ActualHeight - 40;
        var maxHeight = Math.Max(360, available - 60);
        const double infoHeight = 150;
        if (width * 9 / 16 + infoHeight > maxHeight) width = (maxHeight - infoHeight) * 16 / 9;
        PreviewCard.Width = width;
        PreviewMediaArea.Height = width * 9 / 16;
    }

    private void PreviewLayer_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (IsPreviewOpen) SizePreviewCard();
    }

    /// <summary>Clique de novo no card (fora dos botões dele) → abre a mídia.</summary>
    private void PreviewCard_Click(object sender, RoutedEventArgs e)
    {
        if (!ReferenceEquals(e.OriginalSource, PreviewCardButton)) return; // clique num botão de dentro
        PlayFromPreview();
    }

    private void HandlePreviewKey(KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Escape: ClosePreview(); break;
            case Key.Enter or Key.Space: PlayFromPreview(); break;
            case Key.M: ToggleMutePreview(); break;
            default: return;
        }
        e.Handled = true;
    }

    // ───────────────────────── Botões ─────────────────────────

    private void PreviewPlay_Click(object sender, RoutedEventArgs e) => PlayFromPreview();

    private void PreviewClose_Click(object sender, RoutedEventArgs e) => ClosePreview();

    private void PlayFromPreview()
    {
        // Para séries, o episódio já é o "continuar" (ou o próximo / o primeiro).
        var entry = _previewEntry;
        ClosePreview();
        if (entry != null) Play(entry);
    }

    private void PreviewEpisodes_Click(object sender, RoutedEventArgs e)
    {
        var series = _previewItem as SeriesInfo;
        ClosePreview();
        if (series != null) OpenSeries(series);
    }

    private void PreviewDownload_Click(object sender, RoutedEventArgs e)
    {
        if (_previewEntry != null) Enqueue(_previewEntry);
    }

    private void PreviewMute_Click(object sender, RoutedEventArgs e) => ToggleMutePreview();

    private void ToggleMutePreview()
    {
        _settings.PreviewMuted = !_settings.PreviewMuted;
        _settings.Save();
        if (_previewPlayer != null) _previewPlayer.Mute = _settings.PreviewMuted;
        UpdateMuteGlyph();
    }

    private void UpdateMuteGlyph()
    {
        PreviewMuteButton.Content = _settings.PreviewMuted ? "" : "";
        PreviewMuteButton.ToolTip = _settings.PreviewMuted ? "Ligar o som (M)" : "Desligar o som (M)";
    }

    private static FrameworkElement? FindCard(DependencyObject root, string title)
    {
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is System.Windows.Controls.Button { Name: "CardBtn", DataContext: MediaItem item } card && item.Title == title) return card;
            if (FindCard(child, title) is { } found) return found;
        }
        return null;
    }

    // ───────────────────────── Vídeo da prévia ─────────────────────────

    private void StartPreviewVideo(PlaylistEntry entry)
    {
        EnsureFrameTarget();
        var player = new MediaPlayer(_libVLC) { Mute = _settings.PreviewMuted, Volume = (int)VolumeSlider.Value };
        player.SetVideoFormat("RV32", PreviewWidth, PreviewHeight, PreviewWidth * 4);
        player.SetVideoCallbacks(_lockCb!, null, _displayCb);
        player.Playing += (_, _) => Dispatcher.BeginInvoke(() =>
        {
            if (!ReferenceEquals(player, _previewPlayer)) return;
            player.Mute = _settings.PreviewMuted;
            player.Volume = (int)VolumeSlider.Value;
            BeginSnippets(player);
        });

        using var media = Uri.TryCreate(entry.Url, UriKind.Absolute, out var uri)
            ? new VlcMedia(_libVLC, uri)
            : new VlcMedia(_libVLC, entry.Url, FromType.FromPath);
        foreach (var option in DownloadManager.VlcOptionsFor(entry)) media.AddOption(option);
        _previewPlayer = player;
        _snippetIndex = 0;
        _revealFrames = false;
        _holdFrames = false;
        player.Play(media);
    }

    /// <summary>
    /// Trechos: pula por pontos do vídeo a cada 7 s. Canais ao vivo (e vídeos curtos) tocam direto.
    /// A duração às vezes só chega alguns segundos depois de começar a tocar (servidores lentos):
    /// o timer espera por ela, e o vídeo só aparece depois do primeiro pulo (não mostra a abertura).
    /// </summary>
    private void BeginSnippets(MediaPlayer player)
    {
        _snippetStarted = false;
        _snippetPlayedMs = 0;
        _snippetLastTime = -1;
        _snippetTimer ??= new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _snippetTimer.Tick -= NextSnippet;
        _snippetTimer.Tick += NextSnippet;
        _snippetTimer.Start();
        NextSnippet(null, EventArgs.Empty);
    }

    // Só conta o tempo em que o vídeo está de fato andando: em conexão lenta cada trecho ainda
    // aparece por 7 s, em vez de pular de novo antes de carregar.
    private long _snippetPlayedMs;
    private long _snippetLastTime = -1;
    private bool _snippetStarted;
    private volatile bool _revealFrames;
    private volatile bool _holdFrames;

    private void NextSnippet(object? sender, EventArgs e)
    {
        if (_previewPlayer is not { } player) return;
        var time = player.Time;
        var advancing = _snippetLastTime >= 0 && time > _snippetLastTime;
        if (advancing) _snippetPlayedMs += 500;
        _snippetLastTime = time;
        // Depois de um pulo, só volta a mostrar quadros quando o vídeo anda de novo no novo ponto
        // (enquanto carrega, o VLC entrega quadros pretos; fica o último quadro bom na tela).
        if (advancing && _holdFrames)
        {
            _holdFrames = false;
            _revealFrames = true;
        }

        if (!_snippetStarted)
        {
            if (player.IsSeekable && player.Length > 60_000)
            {
                _snippetStarted = true;
                _snippetPlayedMs = 0;
                _snippetLastTime = -1;
                SeekSnippet(player);
            }
            else if (_snippetPlayedMs >= 2_000)
            {
                _revealFrames = true; // ao vivo ou curto: mostra como está
            }
            return;
        }

        if (_snippetPlayedMs < SnippetLength.TotalMilliseconds) return;
        _snippetPlayedMs = 0;
        _snippetLastTime = -1;
        _snippetIndex = (_snippetIndex + 1) % SnippetPositions.Length;
        SeekSnippet(player);
    }

    private void SeekSnippet(MediaPlayer player)
    {
        _holdFrames = true;
        _snippetPlayedMs = 0;
        _snippetLastTime = -1;
        player.Position = SnippetPositions[_snippetIndex];
    }

    private void StopPreviewVideo()
    {
        var player = _previewPlayer;
        _previewPlayer = null;
        if (player == null) return;
        // Stop() pode demorar alguns instantes com streams de rede: fora da thread da interface.
        Task.Run(() =>
        {
            player.Stop();
            player.Dispose();
        });
    }

    private void EnsureFrameTarget()
    {
        if (_previewBitmap != null) return;
        _frameBuffer = Marshal.AllocHGlobal(PreviewWidth * PreviewHeight * 4);
        _previewBitmap = new WriteableBitmap(PreviewWidth, PreviewHeight, 96, 96, PixelFormats.Bgr32, null);
        PreviewVideoBrush.ImageSource = _previewBitmap;

        _lockCb = (_, planes) =>
        {
            Marshal.WriteIntPtr(planes, _frameBuffer);
            return IntPtr.Zero;
        };
        _displayCb = (_, _) =>
        {
            // Copia no máximo um quadro por vez para a interface (descarta se a UI estiver atrasada).
            if (_holdFrames || Interlocked.Exchange(ref _framePending, 1) == 1) return;
            Dispatcher.BeginInvoke(DispatcherPriority.Render, () =>
            {
                Interlocked.Exchange(ref _framePending, 0);
                if (_previewPlayer == null || _previewBitmap == null) return;
                _previewBitmap.WritePixels(new Int32Rect(0, 0, PreviewWidth, PreviewHeight), _frameBuffer,
                    PreviewWidth * PreviewHeight * 4, PreviewWidth * 4);
                if (!_previewHasFrame && _revealFrames)
                {
                    _previewHasFrame = true;
                    PreviewVideoHost.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(400)));
                }
            });
        };
    }
}
