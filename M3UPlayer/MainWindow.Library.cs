using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using M3UPlayer.Models;
using M3UPlayer.Services;

namespace M3UPlayer;

/// <summary>Uma linha da grade: os cards que cabem na largura da janela.</summary>
public sealed class GridRow(IReadOnlyList<MediaItem> items)
{
    public IReadOnlyList<MediaItem> Items { get; } = items;
}

public partial class MainWindow
{
    private const string AllChip = "Todos";
    private const string ContinueChip = "Continuar assistindo";
    private const int RowSize = 20;
    private const int SearchLimit = 3000;
    private const double CardSlot = 203; // largura do card (185) + margem (18)

    private MediaKind? _tabKind;          // null = Início
    private string? _chipGroup;           // grupo aberto em grade
    private bool _showingContinue;        // grade de "Continuar assistindo"
    private string _query = "";
    private object? _chipsKey;
    private bool _updatingChips;
    private bool _updatingTabs;

    private IReadOnlyList<MediaItem> _gridItems = [];
    private int _gridColumns;
    private ObservableCollection<LibraryRow> _homeRows = [];
    private SeriesInfo? _openSeries;

    // ───────────────────────── Montagem das telas ─────────────────────────

    private void RefreshLibraryView()
    {
        if (_library == null) return;
        UpdateChips();

        if (_query.Length > 0)
        {
            var results = _library.Search(_query, _tabKind, SearchLimit);
            ShowGrid($"Resultados para “{_query}”", results,
                results.Count >= SearchLimit ? $"mostrando os {SearchLimit:N0} primeiros" : $"{results.Count:N0} itens");
            if (results.Count == 0) ShowMessage("", "Nada encontrado", $"Nenhum título com “{_query}”.", showButton: false);
            else HideMessage();
            return;
        }

        HideMessage();
        if (_showingContinue)
        {
            var items = BuildContinueItems(_tabKind);
            ShowGrid(ContinueChip, items, $"{items.Count} itens");
        }
        else if (_chipGroup != null && _library.FindGroup(_chipGroup) is { } group)
        {
            ShowGrid(group.Name, group.Items, $"{group.Items.Count:N0} itens");
        }
        else
        {
            ShowHome();
        }
    }

    private void ShowHome()
    {
        GridHeader.Visibility = Visibility.Collapsed;
        GridList.Visibility = Visibility.Collapsed;
        HomeList.Visibility = Visibility.Visible;

        var rows = new ObservableCollection<LibraryRow>();
        if (ContinueRow() is { } continueRow) rows.Add(continueRow);
        foreach (var group in _library!.Groups)
        {
            if (_tabKind != null && group.Kind != _tabKind) continue;
            rows.Add(new LibraryRow(group.Name, group.Items, group.Name, false));
        }

        var pageSize = RowPageSize();
        foreach (var row in rows) row.SetPageSize(pageSize);
        _homeRows = rows;
        HomeList.ItemsSource = rows;
        ScrollToTop(HomeList);
        if (rows.Count == 0) ShowMessage("", "Nada por aqui", "Esta lista não tem itens deste tipo.", showButton: false);
    }

    private LibraryRow? ContinueRow()
    {
        var items = BuildContinueItems(_tabKind);
        return items.Count == 0 ? null : new LibraryRow(ContinueChip, items, null, true)
        {
            ShowSeeAll = items.Count > RowSize
        };
    }

    /// <summary>Quantos cards cabem numa linha do carrossel.</summary>
    private int RowPageSize()
    {
        var width = HomeList.ActualWidth > 0 ? HomeList.ActualWidth : ActualWidth - (SidePanel.IsVisible ? 274 : 0);
        // 60 px reservados para a seta da direita não cobrir o último card
        return Math.Max(1, (int)((width - 32 - 16 - 14 - 60) / CardSlot));
    }

    private void HomeList_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var pageSize = RowPageSize();
        foreach (var row in _homeRows) row.SetPageSize(pageSize);
    }

    private void RowForward_Click(object sender, RoutedEventArgs e) => ((sender as FrameworkElement)?.DataContext as LibraryRow)?.Forward();

    private void RowBack_Click(object sender, RoutedEventArgs e) => ((sender as FrameworkElement)?.DataContext as LibraryRow)?.Back();

    private void ShowGrid(string title, IReadOnlyList<MediaItem> items, string count)
    {
        HomeList.Visibility = Visibility.Collapsed;
        GridList.Visibility = Visibility.Visible;
        GridHeader.Visibility = Visibility.Visible;
        GridTitle.Text = title;
        GridCount.Text = count;
        _gridItems = items;
        RebuildGridRows(force: true);
        ScrollToTop(GridList);
    }

    private void RebuildGridRows(bool force)
    {
        var width = GridList.ActualWidth > 0 ? GridList.ActualWidth : ActualWidth;
        var columns = Math.Max(1, (int)((width - 32 - 16 - 14) / CardSlot));
        if (!force && columns == _gridColumns) return;
        _gridColumns = columns;
        GridList.ItemsSource = _gridItems.Chunk(columns).Select(chunk => new GridRow(chunk)).ToList();
    }

    private void GridList_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (GridList.Visibility == Visibility.Visible && e.WidthChanged) RebuildGridRows(force: false);
    }

    /// <summary>Atualiza só a linha "Continuar assistindo" (mantém a rolagem da página).</summary>
    private void UpdateContinueRow()
    {
        _chipsKey = null;
        if (_library == null) return;
        if (_showingContinue && GridList.Visibility == Visibility.Visible)
        {
            RefreshLibraryView();
            return;
        }
        if (HomeList.Visibility != Visibility.Visible) return;

        var row = ContinueRow();
        var hasRow = _homeRows.Count > 0 && _homeRows[0].IsContinue;
        if (row == null && hasRow) _homeRows.RemoveAt(0);
        else if (row != null && hasRow) _homeRows[0] = row;
        else if (row != null) _homeRows.Insert(0, row);
        if (_tabKind != null) UpdateChips();
    }

    // ───────────────────────── Abas e chips ─────────────────────────

    private void Tab_Checked(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded || _updatingTabs) return;
        if (sender == TabDownloads)
        {
            ShowPage(Page.Downloads);
            return;
        }

        _tabKind = sender == TabLive ? MediaKind.Live
            : sender == TabMovies ? MediaKind.Movie
            : sender == TabSeries ? MediaKind.Series
            : null;
        _chipGroup = null;
        _showingContinue = false;
        ShowPage(Page.Library);
        RefreshLibraryView();
    }

    private void SelectTab(MediaKind? kind)
    {
        _updatingTabs = true;
        (kind switch
        {
            MediaKind.Live => TabLive,
            MediaKind.Movie => TabMovies,
            MediaKind.Series => TabSeries,
            _ => TabHome
        }).IsChecked = true;
        _updatingTabs = false;
        _tabKind = kind;
    }

    private void UpdateChips()
    {
        if (_tabKind == null || _library == null)
        {
            SidePanel.Visibility = Visibility.Collapsed;
            return;
        }

        var key = (_library, _tabKind);
        if (!Equals(_chipsKey, key))
        {
            _chipsKey = key;
            var chips = new List<string> { AllChip };
            if (BuildContinueItems(_tabKind).Count > 0) chips.Add(ContinueChip);
            chips.AddRange(_library.Groups.Where(g => g.Kind == _tabKind).Select(g => g.Name));
            _updatingChips = true;
            ChipList.ItemsSource = chips;
            _updatingChips = false;
        }

        _updatingChips = true;
        ChipList.SelectedItem = _showingContinue ? ContinueChip : _chipGroup ?? AllChip;
        if (ChipList.SelectedItem != null) ChipList.ScrollIntoView(ChipList.SelectedItem);
        _updatingChips = false;
        SidePanel.Visibility = Visibility.Visible;
    }

    private void ChipList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updatingChips || ChipList.SelectedItem is not string chip) return;
        _showingContinue = chip == ContinueChip;
        _chipGroup = chip is AllChip or ContinueChip ? null : chip;
        RefreshLibraryView();
    }

    private void GridBack_Click(object sender, RoutedEventArgs e)
    {
        if (_query.Length > 0)
        {
            SearchBox.Text = "";
            return;
        }
        _chipGroup = null;
        _showingContinue = false;
        RefreshLibraryView();
    }

    private void SeeAll_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not LibraryRow row) return;
        if (row.IsContinue)
        {
            _showingContinue = true;
            _chipGroup = null;
        }
        else if (row.GroupName != null && _library?.FindGroup(row.GroupName) is { } group)
        {
            SelectTab(group.Kind);
            _chipGroup = group.Name;
            _showingContinue = false;
        }
        RefreshLibraryView();
    }

    // Rolagem horizontal das temporadas com a roda do mouse.
    private void ChipList_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (FindChild<ScrollViewer>((DependencyObject)sender) is not { } viewer) return;
        for (int i = 0; i < 3; i++)
        {
            if (e.Delta > 0) viewer.LineLeft();
            else viewer.LineRight();
        }
        e.Handled = true;
    }

    // Rolagem vertical mais rápida nas listas de cards.
    private void FastScroll_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (sender is not ScrollViewer viewer) return;
        viewer.ScrollToVerticalOffset(viewer.VerticalOffset - e.Delta * 1.6);
        e.Handled = true;
    }

    private static void ScrollToTop(ItemsControl list)
    {
        if (FindChild<ScrollViewer>(list) is { } viewer) viewer.ScrollToTop();
    }

    private static T? FindChild<T>(DependencyObject parent) where T : DependencyObject
    {
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T found) return found;
            if (FindChild<T>(child) is { } deeper) return deeper;
        }
        return null;
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        _searchTimer.Stop();
        _searchTimer.Start();
        if (_page != Page.Library && SearchBox.Text.Length > 0) ShowPage(Page.Library);
    }

    // ───────────────────────── Cards ─────────────────────────

    private void Card_Click(object sender, RoutedEventArgs e)
    {
        // Clique abre o card grande da prévia; de lá, outro clique abre a mídia.
        if (sender is FrameworkElement { DataContext: MediaItem } card) OpenPreview(card);
    }

    private void CardMenu_Opened(object sender, RoutedEventArgs e)
    {
        if (sender is not ContextMenu menu) return;
        var item = menu.DataContext as MediaItem;
        var entry = item as PlaylistEntry;
        var series = item as SeriesInfo;
        var inHistory = entry != null ? _history.Get(entry.Url) != null
            : series != null && _history.Items.Any(r => r.SeriesName == series.Title && r.Group == series.Group);

        foreach (var child in menu.Items)
        {
            switch (child)
            {
                case MenuItem { Tag: "play" } mi: mi.Header = series != null ? "Abrir série" : "Reproduzir"; break;
                case MenuItem { Tag: "restart" } mi: mi.Visibility = Vis(entry is { HasProgress: true }); break;
                case MenuItem { Tag: "download" } mi:
                    mi.Header = series != null ? $"Baixar todos os episódios ({series.Episodes.Count})" : "Baixar";
                    break;
                case MenuItem { Tag: "copy" } mi: mi.Visibility = Vis(entry != null); break;
                case MenuItem { Tag: "forget" } mi: mi.Visibility = Vis(inHistory); break;
                case Separator sep: sep.Visibility = Vis(inHistory); break;
            }
        }
    }

    private void CardMenu_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not MenuItem { Tag: string action } mi) return;
        var item = mi.DataContext as MediaItem;
        var entry = item as PlaylistEntry;
        var series = item as SeriesInfo;

        switch (action)
        {
            case "play" when series != null: OpenSeries(series); break;
            case "play" when entry != null: Play(entry); break;
            case "restart" when entry != null: Play(entry, fromStart: true); break;
            case "download" when entry != null: Enqueue(entry); break;
            case "download" when series != null: EnqueueMany(series.Episodes, series.Title); break;
            case "copy" when entry != null: Clipboard.SetText(entry.Url); ShowToast("URL copiada"); break;
            case "forget" when entry != null:
                _history.Remove(entry.Url);
                _history.Save();
                ApplyProgress(entry.Url);
                AfterHistoryChanged();
                break;
            case "forget" when series != null:
                _history.RemoveSeries(series.Title, series.Group);
                _history.Save();
                foreach (var ep in series.Episodes) ApplyProgress(ep.Url);
                AfterHistoryChanged();
                break;
        }
    }

    private void AfterHistoryChanged()
    {
        UpdateContinueRow();
        if (_page == Page.Series) UpdateSeriesPlayButton();
    }

    private void EnqueueMany(IReadOnlyCollection<PlaylistEntry> entries, string name)
    {
        if (entries.Count > 20 && MessageBox.Show(this, $"Baixar {entries.Count} episódios de {name}?", "M3U Player",
                MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
            return;
        foreach (var entry in entries) Enqueue(entry, quiet: true);
        ShowToast($"{entries.Count} episódios adicionados aos downloads");
    }

    private static Visibility Vis(bool visible) => visible ? Visibility.Visible : Visibility.Collapsed;

    // ───────────────────────── Continuar assistindo ─────────────────────────

    /// <summary>
    /// Itens do histórico: filmes pela metade, canais vistos recentemente e, para séries,
    /// o episódio em andamento (ou o próximo, se o último foi terminado).
    /// </summary>
    private List<MediaItem> BuildContinueItems(MediaKind? kind)
    {
        var result = new List<MediaItem>();
        var seenSeries = new HashSet<string>();
        foreach (var record in _history.Items)
        {
            if (kind != null && record.Kind != kind) continue;
            var entry = FindEntry(record);

            if (record.SeriesName != null)
            {
                if (!seenSeries.Add(record.Group + "\u0001" + record.SeriesName)) continue;
                if (record.Completed)
                {
                    var next = entry?.Series?.NextAfter(entry);
                    if (next == null) continue;
                    entry = next;
                }
            }
            else if (record.Completed)
            {
                continue;
            }

            result.Add(entry ?? record.ToEntry());
            if (result.Count >= 60) break;
        }
        return result;
    }

    private PlaylistEntry? FindEntry(WatchRecord record)
    {
        var matches = _library?.FindByUrl(record.Url);
        return matches is { Count: > 0 } ? matches.FirstOrDefault(m => m.Group == record.Group) ?? matches[0] : null;
    }

    private void ApplyHistoryToLibrary()
    {
        if (_library == null) return;
        foreach (var record in _history.Items)
            foreach (var entry in _library.FindByUrl(record.Url))
            {
                entry.Progress = record.Progress;
                entry.Watched = record.Completed;
            }
    }

    private void ApplyProgress(string url)
    {
        var record = _history.Get(url);
        var targets = _library?.FindByUrl(url).ToList() ?? [];
        if (_current?.Url == url && !targets.Contains(_current)) targets.Add(_current);
        foreach (var entry in targets)
        {
            entry.Progress = record?.Progress ?? 0;
            entry.Watched = record?.Completed ?? false;
        }
    }

    // ───────────────────────── Página da série ─────────────────────────

    private void OpenSeries(SeriesInfo series)
    {
        _openSeries = series;
        SeriesTitle.Text = series.Title;
        SeriesInfoText.Text = $"{series.DisplayGroup}  ·  {series.Subtitle}";
        SeriesInitials.Text = series.Initials;
        Art.Show(SeriesPoster, series.Logo, 400);

        var (target, _) = SeriesResumeTarget(series);
        SeasonList.ItemsSource = series.Seasons.Select(s => $"Temporada {s}").ToList();
        SeasonList.SelectedIndex = Math.Max(0, series.Seasons.IndexOf(target.Season));
        UpdateSeriesPlayButton();
        ShowPage(Page.Series);
    }

    private void SeasonList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_openSeries == null || SeasonList.SelectedIndex < 0) return;
        var season = _openSeries.Seasons[SeasonList.SelectedIndex];
        EpisodeList.ItemsSource = _openSeries.Episodes.Where(ep => ep.Season == season).ToList();
        ScrollToTop(EpisodeList);
    }

    /// <summary>Qual episódio o botão principal deve tocar: o em andamento, o próximo, ou o primeiro.</summary>
    private (PlaylistEntry Episode, string Label) SeriesResumeTarget(SeriesInfo series)
    {
        // Listas às vezes repetem a mesma URL em dois episódios: fica o primeiro.
        var urls = new Dictionary<string, PlaylistEntry>(StringComparer.Ordinal);
        foreach (var ep in series.Episodes) urls.TryAdd(ep.Url, ep);
        var last = _history.Items.FirstOrDefault(r => urls.ContainsKey(r.Url));
        if (last != null)
        {
            var episode = urls[last.Url];
            if (!last.Completed) return (episode, $"Continuar {episode.EpisodeCode}");
            if (series.NextAfter(episode) is { } next) return (next, $"Assistir {next.EpisodeCode}");
        }
        var first = series.Episodes[0];
        return (first, $"Assistir {first.EpisodeCode}");
    }

    private void UpdateSeriesPlayButton()
    {
        if (_openSeries == null) return;
        SeriesPlayText.Text = SeriesResumeTarget(_openSeries).Label;
    }

    private void SeriesPlay_Click(object sender, RoutedEventArgs e)
    {
        if (_openSeries != null) Play(SeriesResumeTarget(_openSeries).Episode);
    }

    private void SeriesDownloadSeason_Click(object sender, RoutedEventArgs e)
    {
        if (_openSeries == null || SeasonList.SelectedIndex < 0) return;
        var season = _openSeries.Seasons[SeasonList.SelectedIndex];
        EnqueueMany(_openSeries.Episodes.Where(ep => ep.Season == season).ToList(), $"{_openSeries.Title} T{season}");
    }

    private void SeriesBack_Click(object sender, RoutedEventArgs e) => ShowPage(Page.Library);

    private void Episode_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is PlaylistEntry entry) Play(entry);
    }

    private void EpisodeDownload_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true; // não deixa o clique chegar ao botão da linha (que tocaria o episódio)
        if ((sender as FrameworkElement)?.DataContext is PlaylistEntry entry) Enqueue(entry);
    }
}
