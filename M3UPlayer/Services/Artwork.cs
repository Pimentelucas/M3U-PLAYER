namespace M3UPlayer.Services;

/// <summary>
/// Regras para as imagens das mídias. A maioria das listas usa imagens do TMDB (a base oficial de
/// pôsteres), mas em tamanhos enormes (w1280, 600x900) para cards pequenos; aqui a URL é trocada pelo
/// tamanho que o card precisa, o que deixa cada imagem de 4 a 7 vezes menor.
/// </summary>
public static class Artwork
{
    private const string Tmdb = "image.tmdb.org/t/p/";

    /// <summary>Mesma imagem no tamanho mais próximo de <paramref name="width"/> (só para o TMDB).</summary>
    public static string Optimize(string url, int width)
    {
        var start = url.IndexOf(Tmdb, StringComparison.OrdinalIgnoreCase);
        if (start < 0) return url;
        start += Tmdb.Length;
        var end = url.IndexOf('/', start);
        if (end < 0) return url;
        var size = width <= 185 ? "w185" : width <= 342 ? "w342" : width <= 500 ? "w500" : "w780";
        return string.Concat(url.AsSpan(0, start), size, url.AsSpan(end));
    }
}
