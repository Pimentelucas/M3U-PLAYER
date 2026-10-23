using M3UPlayer.Models;

namespace M3UPlayer.Services;

/// <summary>
/// Botão "Pular abertura". Listas M3U não dizem onde a abertura termina, então:
///  • sem informação, o botão pula para 1:30 (duração típica de abertura);
///  • o app aprende o fim real da abertura de cada série: se, logo depois de pular, o usuário
///    ajusta a posição, ou se ele mesmo arrasta a barra para frente no começo do episódio,
///    aquela posição passa a ser usada nos próximos episódios da série.
/// </summary>
public sealed class IntroSkipper(Dictionary<string, long> learned)
{
    public const long DefaultIntroEndMs = 90_000;
    private const long ShowFromMs = 3_000;
    private const long AdjustWindowMs = 30_000;
    private const long MinIntroEndMs = 20_000;
    private const long MaxIntroEndMs = 6 * 60_000;

    private string? _skippedUrl;
    private long _skippedAt;

    public static string KeyFor(PlaylistEntry e) => $"{e.Group}\u0001{e.SeriesName}";

    public bool IsLearned(PlaylistEntry e) => learned.ContainsKey(KeyFor(e));

    public long TargetFor(PlaylistEntry e) => learned.TryGetValue(KeyFor(e), out var ms) ? ms : DefaultIntroEndMs;

    /// <summary>Mostra o botão no começo de episódios (não em filmes, canais ou vídeos curtos).</summary>
    public bool ShouldShow(PlaylistEntry? entry, long positionMs, long lengthMs) =>
        entry is { IsEpisode: true } && lengthMs >= 5 * 60_000 &&
        positionMs >= ShowFromMs && positionMs < TargetFor(entry) - 3_000 && positionMs < lengthMs / 3;

    /// <summary>O usuário tocou em "Pular abertura": devolve para onde ir.</summary>
    public long Skip(PlaylistEntry entry, long nowMs)
    {
        _skippedUrl = entry.Url;
        _skippedAt = nowMs;
        return TargetFor(entry);
    }

    /// <summary>
    /// Avisa de uma mudança manual de posição. Devolve o novo fim de abertura aprendido
    /// (ou null se nada mudou).
    /// </summary>
    public long? OnManualSeek(PlaylistEntry? entry, long fromMs, long toMs, long nowMs)
    {
        if (entry is not { IsEpisode: true } || toMs < MinIntroEndMs || toMs > MaxIntroEndMs) return null;

        var adjustingSkip = _skippedUrl == entry.Url && nowMs - _skippedAt <= AdjustWindowMs;
        var skippedByHand = fromMs < 120_000 && toMs - fromMs >= 15_000;
        if (!adjustingSkip && !skippedByHand) return null;

        _skippedAt = nowMs; // permite ajustes finos seguidos (ex.: vários -10 s)
        _skippedUrl = entry.Url;
        var rounded = toMs / 1000 * 1000;
        learned[KeyFor(entry)] = rounded;
        return rounded;
    }
}
