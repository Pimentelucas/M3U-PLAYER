using System.Net;
using System.Security.Cryptography;
using System.Text;
using M3UPlayer.Models;
using M3UPlayer.Services;

// Modo opcional: `dotnet run -- --vlc <url>` testa a gravação via libVLC (precisa de internet).
if (args.Length >= 2 && args[0] == "--vlc")
{
    LibVLCSharp.Shared.Core.Initialize();
    using var vlc = new LibVLCSharp.Shared.LibVLC("--no-video-title-show");
    vlc.Log += (_, e) => { if (e.Level >= LibVLCSharp.Shared.LogLevel.Warning) Console.WriteLine($"[vlc {e.Level}] {e.Module}: {e.Message}"); };
    var dir = Path.Combine(Path.GetTempPath(), "m3u_dl_test");
    var manager = new DownloadManager(new HttpClient(), vlc, 1);
    var rec = manager.Enqueue(new PlaylistEntry { Title = "Gravação VLC: teste, ação", Url = args[1] }, dir);
    await Task.Delay(15000);
    Console.WriteLine($"Durante: {rec.State} · {rec.Detail}");
    rec.Cancel();
    while (rec.IsActive) await Task.Delay(100);
    Console.WriteLine($"Final:   {rec.State} · {rec.Detail}");
    return rec.State == DownloadState.Completed ? 0 : 1;
}

// Modo opcional: `dotnet run -- --bench <url ou arquivo>` mede o tempo de abrir e organizar uma lista grande.
if (args.Length >= 2 && args[0] == "--bench")
{
    var sw = System.Diagnostics.Stopwatch.StartNew();
    using var benchHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
    var loaded = PlaylistLoader.IsUrl(args[1]) && PlaylistLoader.CacheTime(args[1]) != null
        ? await PlaylistLoader.LoadFromCacheAsync(args[1], default)
        : await PlaylistLoader.LoadAsync(args[1], benchHttp, null, default);
    var tLoad = sw.Elapsed;
    var library = MediaLibrary.Build(loaded.Entries);
    var tBuild = sw.Elapsed - tLoad;
    GC.Collect();
    var series = library.Groups.SelectMany(g => g.Items).OfType<SeriesInfo>().ToList();
    var searchClock = System.Diagnostics.Stopwatch.StartNew();
    var hits = library.Search("amor", null, 3000).Count;
    Console.WriteLine($"origem={loaded.Origin} itens={loaded.Entries.Count:N0} grupos={library.Groups.Count} " +
                      $"ao_vivo={loaded.Entries.Count(e => e.Kind == MediaKind.Live):N0} filmes={loaded.Entries.Count(e => e.Kind == MediaKind.Movie):N0} " +
                      $"episódios={loaded.Entries.Count(e => e.Kind == MediaKind.Series):N0} séries={series.Count:N0}");
    Console.WriteLine($"ler={tLoad.TotalSeconds:0.0}s organizar={tBuild.TotalSeconds:0.0}s busca={searchClock.ElapsedMilliseconds}ms ({hits} resultados) " +
                      $"memória={GC.GetTotalMemory(true) / 1024 / 1024} MB");
    foreach (var kind in Enum.GetValues<MediaKind>())
        Console.WriteLine($"  abas {kind}: {library.Groups.Count(g => g.Kind == kind)} grupos");
    return 0;
}

int failures = 0;
void Check(bool ok, string name) { Console.WriteLine($"{(ok ? "PASS" : "FAIL")}  {name}"); if (!ok) failures++; }

// ---------- Parser ----------
var m3u = """
#EXTM3U x-tvg-url="http://epg"
#EXTINF:-1 tvg-id="globo.br" tvg-logo="http://logo/g.png" group-title="Abertos, HD",Globo SP, HD
#EXTVLCOPT:http-user-agent=MyAgent/1.0
#EXTVLCOPT:http-referrer=http://ref/
http://host/live/1.ts
#EXTINF:123.5,Artista - Música
musicas/faixa 01.mp3
#EXTGRP:Filmes
#EXTINF:-1,Filme X
http://host/movie/x.mp4

http://host/semtitulo/video.mkv
""";
var entries = M3UParser.Parse(m3u, new Uri(@"C:\listas\minha.m3u"));
Check(entries.Count == 4, "parser: 4 itens");
Check(entries[0].Title == "Globo SP, HD", "parser: título com vírgula");
Check(entries[0].Group == "Abertos, HD", "parser: group-title com vírgula entre aspas");
Check(entries[0].Logo == "http://logo/g.png" && entries[0].TvgId == "globo.br", "parser: logo/tvg-id");
Check(entries[0].UserAgent == "MyAgent/1.0" && entries[0].Referrer == "http://ref/", "parser: EXTVLCOPT");
Check(entries[1].Duration == 123.5 && entries[1].Url == @"C:\listas\musicas\faixa 01.mp3", "parser: duração + caminho relativo");
Check(entries[2].Group == "Filmes", "parser: EXTGRP");
Check(entries[3].Title == "video" && entries[3].Group == "", "parser: item sem EXTINF");
Check(M3UParser.IsHlsStream("#EXTM3U\n#EXT-X-TARGETDURATION:6\nseg0.ts"), "parser: detecta HLS");

// ---------- Servidor local ----------
var rng = new Random(42);
byte[] Bytes(int n) { var b = new byte[n]; rng.NextBytes(b); return b; }
var file = Bytes(3_000_000);
var segs = Enumerable.Range(0, 12).Select(_ => Bytes(50_000 + rng.Next(10_000))).ToArray();
var key = Bytes(16);
byte[] Encrypt(byte[] data, long seq)
{
    var iv = new byte[16];
    System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(iv.AsSpan(8), seq);
    using var aes = Aes.Create(); aes.Key = key;
    return aes.EncryptCbc(data, iv, PaddingMode.PKCS7);
}
const long firstSeq = 100;
var routes = new Dictionary<string, (string Type, byte[] Body)>();
routes["/files/clip.mp4"] = ("video/mp4", file);
routes["/live/canal"] = ("video/mp2t", file); // sem extensão -> deve virar .ts
var plain = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n");
var enc = new StringBuilder($"#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:{firstSeq}\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n");
for (int i = 0; i < segs.Length; i++)
{
    routes[$"/hls/seg{i}.ts"] = ("video/mp2t", segs[i]);
    routes[$"/enc/v/s{i}.ts"] = ("video/mp2t", Encrypt(segs[i], firstSeq + i));
    plain.Append($"#EXTINF:4.0,\nseg{i}.ts\n");
    enc.Append($"#EXTINF:4.0,\ns{i}.ts\n");
}
plain.Append("#EXT-X-ENDLIST\n"); enc.Append("#EXT-X-ENDLIST\n");
routes["/hls/index.m3u8"] = ("application/vnd.apple.mpegurl", Encoding.UTF8.GetBytes(plain.ToString()));
routes["/enc/v/index.m3u8"] = ("application/vnd.apple.mpegurl", Encoding.UTF8.GetBytes(enc.ToString()));
routes["/enc/key.bin"] = ("application/octet-stream", key);
routes["/master.m3u8"] = ("application/x-mpegurl", Encoding.UTF8.GetBytes(
    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nnope/low.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nhls/index.m3u8\n"));
routes["/stream?id=7"] = ("application/x-mpegURL", Encoding.UTF8.GetBytes(plain.ToString().Replace("seg", "hls/seg")));
string? lastUserAgent = null;

var listener = new HttpListener();
var port = 18000 + Random.Shared.Next(1000);
listener.Prefixes.Add($"http://127.0.0.1:{port}/");
listener.Start();
_ = Task.Run(async () =>
{
    while (listener.IsListening)
    {
        var ctx = await listener.GetContextAsync();
        var path = ctx.Request.Url!.PathAndQuery;
        if (path.StartsWith("/files")) lastUserAgent = ctx.Request.UserAgent;
        if (routes.TryGetValue(path, out var r))
        {
            ctx.Response.ContentType = r.Type;
            ctx.Response.ContentLength64 = r.Body.Length;
            await ctx.Response.OutputStream.WriteAsync(r.Body);
        }
        else ctx.Response.StatusCode = 404;
        ctx.Response.Close();
    }
});
var baseUrl = $"http://127.0.0.1:{port}";

// ---------- Downloads ----------
var outDir = Path.Combine(Path.GetTempPath(), "m3u-tests-" + Guid.NewGuid().ToString("N")[..8]);
var http = new HttpClient();
var dm = new DownloadManager(http, null!, 3);

async Task<DownloadItem> Run(string title, string url, string? ua = null)
{
    var item = dm.Enqueue(new PlaylistEntry { Title = title, Url = url, UserAgent = ua }, outDir);
    var deadline = DateTime.Now.AddSeconds(30);
    while (item.IsActive && DateTime.Now < deadline) await Task.Delay(50);
    Console.WriteLine($"      -> {item.State}: {item.Detail}");
    return item;
}
byte[] Concat(IEnumerable<byte[]> parts) => parts.SelectMany(p => p).ToArray();

var d1 = await Run("Clip: teste/1", $"{baseUrl}/files/clip.mp4", "Agent/9");
Check(d1.State == DownloadState.Completed && Path.GetFileName(d1.FilePath) == "Clip_ teste_1.mp4"
      && File.ReadAllBytes(d1.FilePath!).SequenceEqual(file), "download direto (nome saneado, conteúdo idêntico)");
Check(lastUserAgent == "Agent/9", "download direto envia user-agent da lista");

var d2 = await Run("Clip: teste/1", $"{baseUrl}/files/clip.mp4");
Check(Path.GetFileName(d2.FilePath) == "Clip_ teste_1 (2).mp4", "nome duplicado recebe sufixo (2)");

var d3 = await Run("Canal", $"{baseUrl}/live/canal");
Check(d3.State == DownloadState.Completed && d3.FilePath!.EndsWith(".ts"), "URL sem extensão vira .ts pelo content-type");

var d4 = await Run("HLS", $"{baseUrl}/hls/index.m3u8");
Check(d4.State == DownloadState.Completed && File.ReadAllBytes(d4.FilePath!).SequenceEqual(Concat(segs)), "HLS VOD: segmentos concatenados na ordem");

var d5 = await Run("HLS AES", $"{baseUrl}/enc/v/index.m3u8");
Check(d5.State == DownloadState.Completed && File.ReadAllBytes(d5.FilePath!).SequenceEqual(Concat(segs)), "HLS AES-128: descriptografado corretamente");

var d6 = await Run("Master", $"{baseUrl}/master.m3u8");
Check(d6.State == DownloadState.Completed && File.ReadAllBytes(d6.FilePath!).SequenceEqual(Concat(segs)), "HLS master: escolhe a maior variante");

var d7 = await Run("Detectado", $"{baseUrl}/stream?id=7");
Check(d7.State == DownloadState.Completed && File.ReadAllBytes(d7.FilePath!).SequenceEqual(Concat(segs)), "HLS detectado pelo content-type");

var d8 = await Run("Inexistente", $"{baseUrl}/nao-existe.mp4");
Check(d8.State == DownloadState.Failed && !File.Exists(d8.FilePath), "erro 404: falha e remove arquivo parcial");

// ---------- Carregamento de listas + cópia offline ----------
PlaylistLoader.CacheDirectory = Path.Combine(outDir, "cache");
routes["/listas/lista.m3u"] = ("audio/x-mpegurl", Encoding.UTF8.GetBytes("#EXTM3U\n#EXTINF:-1,Rel\n../files/clip.mp4\n#EXTINF:-1,Dois\nhttp://x/2.ts\n"));
var listUrl = $"{baseUrl}/listas/lista.m3u";
var remote = await PlaylistLoader.LoadAsync(listUrl, http, null, default);
Check(remote.Origin == PlaylistOrigin.Network && remote.Entries.Count == 2 && remote.Entries[0].Url == $"{baseUrl}/files/clip.mp4",
      "lista remota: baixada e URL relativa resolvida");
Check(PlaylistLoader.CacheTime(listUrl) != null, "lista remota: cópia offline salva");
routes.Remove("/listas/lista.m3u");
var failedOnline = false;
try { await PlaylistLoader.LoadAsync(listUrl, http, null, default); } catch (HttpRequestException) { failedOnline = true; }
var offline = await PlaylistLoader.LoadFromCacheAsync(listUrl, default);
Check(failedOnline && offline.Origin == PlaylistOrigin.Cache && offline.Entries.Count == 2, "lista offline: servidor fora, cópia salva funciona");

routes["/listas/lista.m3u"] = ("text/html", Encoding.UTF8.GetBytes("<html><head><title>Debug Mode</title></head><body>erro</body></html>"));
var rejected = false;
try { await PlaylistLoader.LoadAsync(listUrl, http, null, default); } catch (InvalidDataException) { rejected = true; }
var stillCached = await PlaylistLoader.LoadFromCacheAsync(listUrl, default);
Check(rejected && stillCached.Entries.Count == 2, "lista: página HTML de erro é rejeitada e não apaga a cópia salva");

var latinFile = Path.Combine(outDir, "latin.m3u");
File.WriteAllBytes(latinFile, Encoding.Latin1.GetBytes("#EXTM3U\n#EXTINF:-1 group-title=\"Músicas\",Canção do Ano\nhttp://x/a.mp3\n"));
var latin = await PlaylistLoader.LoadAsync(latinFile, http, null, default);
Check(latin.Entries[0].Title == "Canção do Ano" && latin.Entries[0].Group == "Músicas", "lista Latin-1 decodificada");

// ---------- Biblioteca: TV / filmes / séries ----------
var libText = """
#EXTM3U
#EXTINF:-1 tvg-logo="http://l/rec.png" group-title="CANAIS",RECORD SP HD
http://srv/user/pass/101
#EXTINF:-1 group-title="CANAIS",GLOBO RJ
http://srv/user/pass/102
#EXTINF:-1 group-title="Ação",Missão Impossível (2023)
http://srv/AAAAAAAAAAAAAAAA1
#EXTINF:-1 group-title="Ação",Velozes 10 (2023)
http://srv/AAAAAAAAAAAAAAAA2
#EXTINF:-1 group-title="Legendados",Filme Sem Ano [L]
http://srv/AAAAAAAAAAAAAAAA3
#EXTINF:-1 tvg-logo="http://l/show.jpg" group-title="Netflix",Meu Show S01E02
http://srv/AAAAAAAAAAAAAAAA5
#EXTINF:-1 group-title="Netflix",Meu Show S01E01
http://srv/AAAAAAAAAAAAAAAA4
#EXTINF:-1 group-title="Netflix",Meu Show S02E01 - A Volta
http://srv/AAAAAAAAAAAAAAAA6
#EXTINF:-1 group-title="Netflix",Outra Série (2026) S01E01
http://srv/AAAAAAAAAAAAAAAA7
""";
var lib = MediaLibrary.Build(M3UParser.Parse(libText, null));
var byTitle = lib.Entries.ToDictionary(e => e.Title);
Check(byTitle["RECORD SP HD"].Kind == MediaKind.Live && byTitle["GLOBO RJ"].Kind == MediaKind.Live, "biblioteca: canais ao vivo");
Check(byTitle["Missão Impossível (2023)"].Kind == MediaKind.Movie, "biblioteca: filme pelo ano no título");
Check(byTitle["Filme Sem Ano [L]"].Kind == MediaKind.Movie, "biblioteca: filme sem ano pelo formato da URL");
var netflix = lib.FindGroup("Netflix")!;
var show = netflix.Items.OfType<SeriesInfo>().First(s => s.Title == "Meu Show");
Check(netflix.Kind == MediaKind.Series && netflix.Items.Count == 2, "biblioteca: episódios agrupados em 2 séries");
Check(show.Episodes.Select(e => e.EpisodeCode).SequenceEqual(["T1 E1", "T1 E2", "T2 E1"]) && show.Seasons.SequenceEqual([1, 2]),
      "biblioteca: episódios ordenados por temporada");
Check(show.Episodes[2].EpisodeTitle == "A Volta" && show.Logo == "http://l/show.jpg", "biblioteca: título do episódio e pôster da série");
Check(netflix.Items.OfType<SeriesInfo>().Any(s => s.Title == "Outra Série (2026)"), "biblioteca: série com ano no nome");
Check(lib.Groups.Select(g => g.Name).SequenceEqual(["CANAIS", "Ação", "Legendados", "Netflix"]), "biblioteca: grupos na ordem da lista");
Check(lib.Search("show", null, 10).Count == 1 && lib.Search("velozes", MediaKind.Movie, 10).Count == 1
      && lib.Search("velozes", MediaKind.Live, 10).Count == 0, "biblioteca: busca por título e tipo");
Check(show.NextAfter(show.Episodes[0]) == show.Episodes[1] && show.NextAfter(show.Episodes[2]) == null, "biblioteca: próximo episódio");

// ---------- Histórico / continuar de onde parou ----------
WatchHistory.FilePath = Path.Combine(outDir, "history.json");
var history = new WatchHistory();
var movie = byTitle["Missão Impossível (2023)"];
history.Touch(movie);
history.UpdatePosition(movie.Url, 30 * 60_000, 120 * 60_000);
Check(history.Get(movie.Url) is { CanResume: true, PositionMs: 1_800_000 } r1 && Math.Abs(r1.Progress - 0.25) < 0.001,
      "histórico: posição salva (30 de 120 min)");
history.Touch(byTitle["RECORD SP HD"]);
Check(history.Items[0].Url == byTitle["RECORD SP HD"].Url && history.Items.Count == 2, "histórico: mais recente primeiro");
history.Save();
var reloaded = WatchHistory.Load();
Check(reloaded.Get(movie.Url)?.PositionMs == 1_800_000 && reloaded.Get(movie.Url)?.Kind == MediaKind.Movie, "histórico: salvo e lido do disco");
history.UpdatePosition(movie.Url, 118 * 60_000, 120 * 60_000);
Check(history.Get(movie.Url) is { Completed: true, PositionMs: 0, CanResume: false }, "histórico: perto do fim conta como assistido");
Check(!WatchHistory.IsFinished(60_000, 120 * 60_000) && WatchHistory.IsFinished(112 * 60_000, 120 * 60_000), "histórico: regra de 'terminou'");
history.Touch(show.Episodes[0]);
history.UpdatePosition(show.Episodes[0].Url, 5_000, 40 * 60_000);
Check(history.Get(show.Episodes[0].Url) is { CanResume: false, SeriesName: "Meu Show", Season: 1 }, "histórico: menos de 10 s não oferece retomar");

// ---------- Pular abertura ----------
var learned = new Dictionary<string, long>();
var intro = new IntroSkipper(learned);
var ep1 = show.Episodes[0];
Check(!intro.ShouldShow(ep1, 1_000, 40 * 60_000) && intro.ShouldShow(ep1, 10_000, 40 * 60_000) && !intro.ShouldShow(ep1, 88_000, 40 * 60_000),
      "abertura: botão aparece entre 3 s e o fim da abertura (padrão 1:30)");
Check(!intro.ShouldShow(movie, 10_000, 120 * 60_000) && !intro.ShouldShow(ep1, 10_000, 2 * 60_000), "abertura: não aparece em filmes nem em vídeos curtos");
Check(intro.Skip(ep1, 1_000) == 90_000, "abertura: sem aprendizado pula para 1:30");
Check(intro.OnManualSeek(ep1, 95_000, 85_000, 10_000) == 85_000 && learned[IntroSkipper.KeyFor(ep1)] == 85_000,
      "abertura: ajuste logo depois de pular ensina o fim da abertura");
Check(intro.Skip(show.Episodes[1], 100_000) == 85_000, "abertura: próximo episódio usa o ponto aprendido");
var intro2 = new IntroSkipper(new Dictionary<string, long>());
Check(intro2.OnManualSeek(ep1, 5_000, 72_000, 0) == 72_000, "abertura: arrastar a barra no começo do episódio também ensina");
Check(intro2.OnManualSeek(ep1, 30 * 60_000, 31 * 60_000, 999_999) == null, "abertura: mudar de posição no meio do episódio não ensina");

// ---------- Descrição da prévia ----------
var desc = MediaDescriber.Describe(byTitle["Missão Impossível (2023)"], null);
Check(desc.Meta == "2023  ·  Filme" && desc.Text == "Filme de Ação, de 2023.", $"prévia: filme ({desc.Meta} | {desc.Text})");
var channel = new PlaylistEntry { Title = "RECORD SP FHD", Group = "♦️ CANAIS | FHD H265 ⭐", Kind = MediaKind.Live };
var descLive = MediaDescriber.Describe(channel, null);
Check(descLive.Meta == "Ao vivo  ·  Full HD" && descLive.Text == "Canal ao vivo em Full HD, do grupo Canais · FHD H265.", $"prévia: canal ({descLive.Meta} | {descLive.Text})");
var resumeRecord = new WatchRecord { PositionMs = 600_000, DurationMs = 7_200_000 };
Check(MediaDescriber.Describe(byTitle["Velozes 10 (2023)"], resumeRecord) is { PlayLabel: "Continuar" } dResume && dResume.Text.Contains("Você parou em 10:00 de 2:00:00"),
      "prévia: mostra onde parou e o botão Continuar");
var descShow = MediaDescriber.Describe(show, null, show.Episodes[0]);
Check(descShow.Text.StartsWith("Série com 2 temporadas e 3 episódios") && descShow.PlayLabel == "Assistir T1 E1", $"prévia: série ({descShow.Text})");
Check(MediaDescriber.CleanGroup("♦️ Ação ✔️") == "Ação" && MediaDescriber.CleanGroup("♦️  REDE RECORD") == "Rede Record", "prévia: limpa o nome do grupo");

// ---------- Imagens ----------
ImageCache.Folder = Path.Combine(outDir, "images"); // não mexe no cache de imagens do usuário
Check(Artwork.Optimize("https://image.tmdb.org/t/p/w1280/abc.jpg", 370) == "https://image.tmdb.org/t/p/w500/abc.jpg"
      && Artwork.Optimize("https://image.tmdb.org/t/p/w600_and_h900_bestv2/abc.jpg", 260) == "https://image.tmdb.org/t/p/w342/abc.jpg"
      && Artwork.Optimize("http://outro/x.jpg", 370) == "http://outro/x.jpg", "imagens: TMDB no tamanho do card, outros sites intactos");
var posterLib = MediaLibrary.Build(M3UParser.Parse("""
    #EXTM3U
    #EXTINF:-1 tvg-logo="https://image.tmdb.org/t/p/w1280/cena1.jpg" group-title="S",Show S01E01
    http://srv/e1
    #EXTINF:-1 tvg-logo="https://image.tmdb.org/t/p/w600_and_h900_bestv2/poster.jpg" group-title="S",Show S01E02
    http://srv/e2
    """, null));
Check(posterLib.Groups.SelectMany(g => g.Items).OfType<SeriesInfo>().Single().Logo!.Contains("cena1.jpg"),
      "imagens: a série usa a imagem da própria lista (a do 1º episódio), como antes");

// ---------- Carrossel ----------
var carousel = new LibraryRow("Ação", Enumerable.Range(1, 10).Select(i => (MediaItem)new PlaylistEntry { Title = "F" + i }).ToList(), "Ação", false);
carousel.SetPageSize(4);
Check(carousel.Items.Count == 4 && !carousel.CanBack && carousel.CanForward, "carrossel: primeira página");
carousel.Forward(); carousel.Forward();
Check(carousel.Items.Select(i => i.Title).SequenceEqual(["F9", "F10"]) && carousel.CanBack && !carousel.CanForward, "carrossel: última página e seta de voltar");
carousel.Back();
Check(carousel.Items[0].Title == "F5", "carrossel: volta para a página anterior");

// ---------- Pastas de download ----------
var folders = new AppSettings { DownloadFolder = @"C:\Filmes" };
Check(folders.FolderFor(movie) == @"C:\Filmes" && folders.FolderFor(show.Episodes[0]) == @"C:\Filmes\Meu Show", "pastas: episódio numa subpasta da série");
folders.SeriesFolder = @"D:\Series";
Check(folders.FolderFor(show.Episodes[0]) == @"D:\Series\Meu Show" && folders.FolderFor(movie) == @"C:\Filmes", "pastas: pasta própria para as séries");

// ---------- Downloads já existentes nas pastas ----------
var scanDir = Path.Combine(outDir, "scan");
Directory.CreateDirectory(Path.Combine(scanDir, "Meu Show"));
File.WriteAllText(Path.Combine(scanDir, "Dexter S02E03.mp4"), "x");
File.WriteAllText(Path.Combine(scanDir, "Dexter S02E03 (2).mp4"), "x");
File.WriteAllText(Path.Combine(scanDir, "Meu Show", "Meu Show S01E01.mkv"), "x");
File.WriteAllText(Path.Combine(scanDir, "anotacoes.txt"), "x");
var scanned = DownloadScanner.Find([scanDir, scanDir]).Select(f => DownloadScanner.ItemFor(f, lib)).ToList();
Check(scanned.Count == 3 && scanned.All(i => i.IsCompleted && i.FromDisk), "downloads antigos: acha os vídeos das pastas e subpastas (sem repetir)");
var fromList = scanned.Single(i => i.FilePath!.EndsWith(".mkv"));
Check(fromList.Entry.Series?.Logo == "http://l/show.jpg" && fromList.Entry.EpisodeCode == "T1 E1", "downloads antigos: arquivo da lista ganha a imagem da série e o episódio");
Check(scanned.Where(i => i.Entry.SeriesName == "Dexter").Count() == 2 && DownloadScanner.TitleOf("Dexter S02E03 (2).mp4") == "Dexter S02E03" && DownloadScanner.TitleOf("Filme (2008).mp4") == "Filme (2008)",
      "downloads antigos: episódio fora da lista é reconhecido pelo nome");
var scanGroups = DownloadGroup.Build(scanned);
Check(scanGroups.Count == 2 && scanGroups.All(g => g.IsSeries), "downloads antigos: agrupados por série");

// ---------- Downloads agrupados ----------
var dlItems = new[] { show.Episodes[2], movie, show.Episodes[0], byTitle["RECORD SP HD"] }.Select(e => new DownloadItem(e)).ToList();
var groups = DownloadGroup.Build(dlItems);
Check(groups.Count == 3 && groups[0].IsSeries && groups[0].Title == "Meu Show" && groups[0].Logo == "http://l/show.jpg",
      "downloads: episódios da mesma série num grupo, com o pôster da série");
Check(groups[0].Items.Select(i => i.Entry.EpisodeCode).SequenceEqual(["T1 E1", "T2 E1"]) && groups[0].Summary.StartsWith("2 episódios"),
      "downloads: episódios ordenados e resumo do grupo");
Check(groups[1].Title == movie.Title && !groups[1].IsSeries && groups[2].IsSquare, "downloads: filmes e canais em blocos próprios");

listener.Stop();
Directory.Delete(outDir, true);
Console.WriteLine(failures == 0 ? "\nTODOS OS TESTES PASSARAM" : $"\n{failures} FALHA(S)");
return failures;

