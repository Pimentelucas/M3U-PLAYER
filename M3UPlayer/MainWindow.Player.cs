using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using M3UPlayer.Models;
using M3UPlayer.Services;
using VlcMedia = LibVLCSharp.Shared.Media;

namespace M3UPlayer;

public partial class MainWindow
{
    private const string PlayGlyph = "";
    private const string PauseGlyph = "";
    private const string VolumeGlyph = "";
    private const string MuteGlyph = "";
    private static readonly TimeSpan ChromeTimeout = TimeSpan.FromSeconds(3);

    private PlaylistEntry? _current;
    private IReadOnlyList<PlaylistEntry> _siblings = [];
    private Page _returnPage = Page.Library;
    private bool _seeking;
    private bool _fullscreen;
    private bool _chromeVisible = true;
    private bool _errorShown;
    private WindowState _stateBeforeFullscreen;
    private Point _lastMouse;
    private long _lastPositionSave;
    private long _lastHistoryFlush;

    private DispatcherTimer _hideTimer = null!;
    private DispatcherTimer _clickTimer = null!;
    private DispatcherTimer _resumeTimer = null!;
    private DispatcherTimer _nextUpTimer = null!;
    private IntroSkipper _intro = null!;
    private PlaylistEntry? _nextUp;
    private int _nextUpSeconds;
    private const int NextUpSeconds = 5;

    private void InitPlayer()
    {
        _intro = new IntroSkipper(_settings.IntroEnds);
        _nextUpTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _nextUpTimer.Tick += (_, _) => TickNextUp();

        _hideTimer = new DispatcherTimer { Interval = ChromeTimeout };
        _hideTimer.Tick += (_, _) => HideChrome();
        // Clique simples pausa; duplo clique alterna tela cheia. O timer separa os dois.
        _clickTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(230) };
        _clickTimer.Tick += (_, _) =>
        {
            _clickTimer.Stop();
            TogglePlayPause();
        };
        _resumeTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };
        _resumeTimer.Tick += (_, _) =>
        {
            _resumeTimer.Stop();
            ResumeToast.Visibility = Visibility.Collapsed;
        };

        // Eventos do VLC chegam em threads próprias: sempre voltar para a UI com BeginInvoke
        // (e nunca chamar o player de volta de dentro do próprio evento).
        _player.Playing += (_, _) => Ui(() =>
        {
            PlayPauseButton.Content = PauseGlyph;
            _errorShown = false;
            HideCenter();
            var seekable = _player.IsSeekable && _player.Length > 0;
            SeekSlider.IsEnabled = seekable;
            BackTenButton.IsEnabled = ForwardTenButton.IsEnabled = seekable;
            LiveBadge.Visibility = seekable ? Visibility.Collapsed : Visibility.Visible;
            _player.Volume = (int)VolumeSlider.Value;
            ShowChrome();
        });
        _player.Paused += (_, _) => Ui(() =>
        {
            PlayPauseButton.Content = PlayGlyph;
            ShowChrome(keepVisible: true);
            SaveCurrentPosition();
        });
        _player.Stopped += (_, _) => Ui(() => PlayPauseButton.Content = PlayGlyph);
        _player.EndReached += (_, _) => Ui(OnEndReached);
        _player.EncounteredError += (_, _) => Ui(() =>
        {
            _errorShown = true;
            PlayPauseButton.Content = PlayGlyph;
            ShowCenter("", "Não foi possível reproduzir.\nO stream pode estar offline ou o formato não é suportado.");
            ShowChrome(keepVisible: true);
        });
        _player.Buffering += (_, e) =>
        {
            var cache = e.Cache;
            Ui(() =>
            {
                if (_errorShown) return;
                if (cache < 100) ShowCenter("", $"Carregando... {cache:0}%");
                else HideCenter();
            });
        };
        _player.LengthChanged += (_, e) =>
        {
            var length = e.Length;
            Ui(() =>
            {
                SeekSlider.Maximum = Math.Max(1, length);
                DurationText.Text = length > 0 ? Format.Duration(TimeSpan.FromMilliseconds(length)) : "--:--";
            });
        };
        _player.TimeChanged += (_, e) =>
        {
            var time = e.Time;
            Ui(() => OnTimeChanged(time));
        };
    }

    private void Ui(Action action) => Dispatcher.BeginInvoke(action);

    // ───────────────────────── Reproduzir / fechar ─────────────────────────

    private void Play(PlaylistEntry entry, bool fromStart = false)
    {
        SaveCurrentPosition();
        HideNextUp();
        SkipIntroButton.Visibility = Visibility.Collapsed;

        var record = _history.Get(entry.Url);
        var resumeMs = !fromStart && record is { CanResume: true } ? record.PositionMs : 0;

        _current = entry;
        _siblings = SiblingsOf(entry);
        if (_page != Page.Player) _returnPage = _page;
        ShowPage(Page.Player);

        PlayerTitle.Text = entry.IsEpisode ? entry.SeriesName : entry.Title;
        PlayerSubtitle.Text = entry.IsEpisode ? $"{entry.EpisodeCode}  ·  {entry.EpisodeHeading}" : entry.DisplayGroup;
        Title = $"{entry.Title} — M3U Player";
        var index = IndexInSiblings(entry);
        PrevButton.IsEnabled = index > 0;
        NextButton.IsEnabled = index >= 0 && index < _siblings.Count - 1;
        PrevButton.ToolTip = entry.IsEpisode ? "Episódio anterior (P)" : "Anterior (P)";
        NextButton.ToolTip = entry.IsEpisode ? "Próximo episódio (N)" : "Próximo (N)";

        PlayPauseButton.Content = PlayGlyph;
        TimeText.Text = "00:00";
        DurationText.Text = "--:--";
        SeekSlider.Value = 0;
        SeekSlider.IsEnabled = false;
        LiveBadge.Visibility = Visibility.Collapsed;
        _errorShown = false;
        ShowCenter("", "Abrindo...");

        using var media = CreateMedia(entry);
        if (resumeMs > 0)
            media.AddOption(":start-time=" + (resumeMs / 1000.0).ToString("0.###", CultureInfo.InvariantCulture));
        _player.Play(media);

        var touched = _history.Touch(entry);
        if (fromStart && touched.DurationMs > 0)
        {
            touched.PositionMs = 0;
            touched.Completed = false;
        }
        _history.Save();
        ApplyProgress(entry.Url);

        if (resumeMs > 0)
        {
            ResumeText.Text = $"Continuando de {Format.Duration(TimeSpan.FromMilliseconds(resumeMs))}";
            ResumeToast.Visibility = Visibility.Visible;
            _resumeTimer.Stop();
            _resumeTimer.Start();
        }
        else
        {
            ResumeToast.Visibility = Visibility.Collapsed;
        }

        ShowChrome();
    }

    private VlcMedia CreateMedia(PlaylistEntry entry)
    {
        var media = Uri.TryCreate(entry.Url, UriKind.Absolute, out var uri)
            ? new VlcMedia(_libVLC, uri)
            : new VlcMedia(_libVLC, entry.Url, FromType.FromPath);
        foreach (var option in DownloadManager.VlcOptionsFor(entry)) media.AddOption(option);
        return media;
    }

    private void ClosePlayer()
    {
        SaveCurrentPosition();
        HideNextUp();
        SkipIntroButton.Visibility = Visibility.Collapsed;
        _player.Stop();
        _current = null;
        _hideTimer.Stop();
        ResumeToast.Visibility = Visibility.Collapsed;
        if (_fullscreen) ToggleFullscreen();
        ShowPage(_returnPage);
        UpdateContinueRow();
        if (_returnPage == Page.Series) UpdateSeriesPlayButton();
    }

    private void OnEndReached()
    {
        if (_current == null) return;
        var finished = _current;
        var wasVod = _player.Length > 0;

        if (wasVod)
        {
            _history.MarkCompleted(finished.Url);
            _history.Save();
            ApplyProgress(finished.Url);
        }

        if (finished.Series?.NextAfter(finished) is { } next)
        {
            ShowNextUp(next);
            return;
        }

        if (!wasVod)
        {
            // Stream ao vivo "terminou": normalmente é queda de conexão.
            _errorShown = true;
            ShowCenter("", "A transmissão foi interrompida.");
            ShowChrome(keepVisible: true);
            return;
        }
        ClosePlayer();
    }

    /// <summary>Itens para anterior/próximo: episódios da série ou itens do mesmo grupo.</summary>
    private IReadOnlyList<PlaylistEntry> SiblingsOf(PlaylistEntry entry)
    {
        if (entry.Series != null) return entry.Series.Episodes;
        var group = _library?.FindGroup(entry.DisplayGroup);
        return group != null && group.Entries.Contains(entry) ? group.Entries : [entry];
    }

    private int IndexInSiblings(PlaylistEntry entry)
    {
        for (int i = 0; i < _siblings.Count; i++)
            if (ReferenceEquals(_siblings[i], entry)) return i;
        return -1;
    }

    private void PlayRelative(int offset)
    {
        if (_current == null) return;
        var target = IndexInSiblings(_current) + offset;
        if (target >= 0 && target < _siblings.Count) Play(_siblings[target]);
    }

    // ───────────────────────── Histórico ─────────────────────────

    private void OnTimeChanged(long time)
    {
        TimeText.Text = Format.Duration(TimeSpan.FromMilliseconds(Math.Max(0, time)));
        if (!_seeking) SeekSlider.Value = time;
        SkipIntroButton.Visibility = _intro.ShouldShow(_current, time, _player.Length) && _nextUp == null
            ? Visibility.Visible : Visibility.Collapsed;

        // Salva a posição a cada 5 s (em memória) e grava o arquivo a cada 20 s.
        var now = Environment.TickCount64;
        if (_current == null || now - _lastPositionSave < 5000) return;
        _lastPositionSave = now;
        var length = _player.Length;
        if (length <= 0) return;
        _history.UpdatePosition(_current.Url, time, length);
        ApplyProgress(_current.Url);
        if (now - _lastHistoryFlush > 20_000)
        {
            _lastHistoryFlush = now;
            _history.Save();
        }
    }

    private void SaveCurrentPosition()
    {
        if (_current == null) return;
        var length = _player.Length;
        var time = _player.Time;
        if (length > 0 && time > 0) _history.UpdatePosition(_current.Url, time, length);
        _history.Save();
        ApplyProgress(_current.Url);
    }

    // ───────────────────────── Pular abertura / próximo episódio ─────────────────────────

    private void SkipIntro_Click(object sender, RoutedEventArgs e) => SkipIntro();

    private void SkipIntro()
    {
        if (_current == null || SkipIntroButton.Visibility != Visibility.Visible) return;
        var target = _intro.Skip(_current, Environment.TickCount64);
        _player.Time = target;
        SeekSlider.Value = target;
        SkipIntroButton.Visibility = Visibility.Collapsed;
        if (!_intro.IsLearned(_current))
            ShowToast("Pulou para 1:30. Se a abertura terminar em outro ponto, ajuste a barra: o app aprende para os próximos episódios.", 6);
    }

    /// <summary>Mudança manual de posição: pode ensinar onde a abertura desta série termina.</summary>
    private void LearnFromSeek(long fromMs, long toMs)
    {
        if (_intro.OnManualSeek(_current, fromMs, toMs, Environment.TickCount64) is not { } learned || _current == null) return;
        _settings.Save();
        ShowToast($"Abertura de {_current.SeriesName} termina em {Format.Duration(TimeSpan.FromMilliseconds(learned))} — vale para os próximos episódios.");
    }

    private void ShowNextUp(PlaylistEntry next)
    {
        _nextUp = next;
        _nextUpSeconds = NextUpSeconds;
        NextUpTitle.Text = $"{next.EpisodeCode}  ·  {next.EpisodeHeading}";
        Art.Show(NextUpImage, next.Logo, 320);
        UpdateNextUpCountdown();
        NextUpPanel.Visibility = Visibility.Visible;
        SkipIntroButton.Visibility = Visibility.Collapsed;
        ShowChrome(keepVisible: true);
        _nextUpTimer.Start();
    }

    private void TickNextUp()
    {
        _nextUpSeconds--;
        if (_nextUpSeconds <= 0)
        {
            PlayNextUp();
            return;
        }
        UpdateNextUpCountdown();
    }

    private void UpdateNextUpCountdown()
    {
        NextUpCountdown.Text = $"PRÓXIMO EPISÓDIO EM {_nextUpSeconds}";
        NextUpProgress.Value = NextUpSeconds - _nextUpSeconds;
    }

    private void PlayNextUp()
    {
        var next = _nextUp;
        HideNextUp();
        if (next != null) Play(next);
    }

    private void HideNextUp()
    {
        _nextUpTimer?.Stop();
        _nextUp = null;
        if (NextUpPanel != null) NextUpPanel.Visibility = Visibility.Collapsed;
    }

    private void NextUpPlay_Click(object sender, RoutedEventArgs e) => PlayNextUp();

    private void NextUpCancel_Click(object sender, RoutedEventArgs e)
    {
        HideNextUp();
        ShowChrome(keepVisible: true);
    }

    // ───────────────────────── Controles que se escondem ─────────────────────────

    private void ShowChrome(bool keepVisible = false)
    {
        _hideTimer.Stop();
        if (!_chromeVisible)
        {
            Chrome.BeginAnimation(OpacityProperty, null);
            Chrome.Opacity = 1;
            Chrome.IsHitTestVisible = true;
            Overlay.Cursor = null;
            _chromeVisible = true;
        }
        if (!keepVisible && _player.State == VLCState.Playing) _hideTimer.Start();
    }

    private void HideChrome()
    {
        _hideTimer.Stop();
        if (_page != Page.Player || _player.State != VLCState.Playing) return;
        // Não esconde enquanto o mouse está sobre os botões ou arrastando a barra de tempo.
        if (_seeking || BottomControls.IsMouseOver || TopControls.IsMouseOver || ResumeToast.IsMouseOver)
        {
            _hideTimer.Start();
            return;
        }
        _chromeVisible = false;
        Chrome.IsHitTestVisible = false;
        Chrome.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(350)));
        Overlay.Cursor = Cursors.None;
    }

    private void Overlay_MouseMove(object sender, MouseEventArgs e)
    {
        var position = e.GetPosition(Overlay);
        if (Math.Abs(position.X - _lastMouse.X) < 2 && Math.Abs(position.Y - _lastMouse.Y) < 2) return;
        _lastMouse = position;
        ShowChrome();
    }

    private void Overlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount >= 2)
        {
            _clickTimer.Stop();
            ToggleFullscreen();
        }
        else
        {
            _clickTimer.Stop();
            _clickTimer.Start();
        }
        ShowChrome();
    }

    private void ShowCenter(string icon, string text)
    {
        CenterIcon.Text = icon;
        CenterIcon.Visibility = icon.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        CenterText.Text = text;
        CenterPanel.Visibility = Visibility.Visible;
    }

    private void HideCenter() => CenterPanel.Visibility = Visibility.Collapsed;

    // ───────────────────────── Teclado ─────────────────────────

    private void HandlePlayerKey(KeyEventArgs e)
    {
        ShowChrome();
        var handled = true;
        switch (e.Key)
        {
            case Key.Space:
            case Key.K: TogglePlayPause(); break;
            case Key.F:
            case Key.F11: ToggleFullscreen(); break;
            case Key.Escape:
                if (_fullscreen) ToggleFullscreen();
                else ClosePlayer();
                break;
            case Key.Left: SeekBy(-10_000); break;
            case Key.Right: SeekBy(+10_000); break;
            case Key.Up: VolumeSlider.Value = Math.Min(100, VolumeSlider.Value + 5); break;
            case Key.Down: VolumeSlider.Value = Math.Max(0, VolumeSlider.Value - 5); break;
            case Key.M: SetMute(!_player.Mute); break;
            case Key.S: SkipIntro(); break;
            case Key.Enter when _nextUp != null: PlayNextUp(); break;
            case Key.N:
            case Key.MediaNextTrack: PlayRelative(+1); break;
            case Key.P:
            case Key.MediaPreviousTrack: PlayRelative(-1); break;
            default: handled = false; break;
        }
        e.Handled = handled;
    }

    // ───────────────────────── Botões ─────────────────────────

    private void TogglePlayPause()
    {
        switch (_player.State)
        {
            case VLCState.Playing: _player.SetPause(true); break;
            case VLCState.Paused: _player.SetPause(false); break;
            default:
                if (_current != null) Play(_current);
                break;
        }
    }

    private void SeekBy(long deltaMs)
    {
        if (!_player.IsSeekable || _player.Length <= 0) return;
        var from = _player.Time;
        var target = Math.Clamp(from + deltaMs, 0, _player.Length - 1000);
        _player.Time = target;
        LearnFromSeek(from, target);
        TimeText.Text = Format.Duration(TimeSpan.FromMilliseconds(target));
        SeekSlider.Value = target;
    }

    private void PlayPause_Click(object sender, RoutedEventArgs e) => TogglePlayPause();
    private void Next_Click(object sender, RoutedEventArgs e) => PlayRelative(+1);
    private void Previous_Click(object sender, RoutedEventArgs e) => PlayRelative(-1);
    private void BackTen_Click(object sender, RoutedEventArgs e) => SeekBy(-10_000);
    private void ForwardTen_Click(object sender, RoutedEventArgs e) => SeekBy(+10_000);
    private void PlayerBack_Click(object sender, RoutedEventArgs e) => ClosePlayer();

    private void StartOver_Click(object sender, RoutedEventArgs e)
    {
        _player.Time = 0;
        ResumeToast.Visibility = Visibility.Collapsed;
    }

    private void DownloadCurrent_Click(object sender, RoutedEventArgs e)
    {
        if (_current != null) Enqueue(_current);
    }

    private void SeekSlider_PreviewMouseDown(object sender, MouseButtonEventArgs e) => _seeking = true;

    private void SeekSlider_PreviewMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (_player.IsSeekable)
        {
            var from = _player.Time;
            var to = (long)SeekSlider.Value;
            _player.Time = to;
            LearnFromSeek(from, to);
        }
        _seeking = false;
    }

    private void VolumeSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_player == null) return; // disparado durante InitializeComponent
        _player.Volume = (int)e.NewValue;
        if (_player.Mute && e.NewValue > 0) SetMute(false);
    }

    private void Mute_Click(object sender, RoutedEventArgs e) => SetMute(!_player.Mute);

    private void SetMute(bool mute)
    {
        _player.Mute = mute;
        MuteButton.Content = mute ? MuteGlyph : VolumeGlyph;
    }

    private void Fullscreen_Click(object sender, RoutedEventArgs e) => ToggleFullscreen();

    private void ToggleFullscreen()
    {
        _fullscreen = !_fullscreen;
        if (_fullscreen)
        {
            _stateBeforeFullscreen = WindowState;
            WindowStyle = WindowStyle.None;
            WindowState = WindowState.Normal; // necessário para cobrir a barra de tarefas
            WindowState = WindowState.Maximized;
        }
        else
        {
            WindowStyle = WindowStyle.SingleBorderWindow;
            WindowState = _stateBeforeFullscreen;
        }
    }
}
