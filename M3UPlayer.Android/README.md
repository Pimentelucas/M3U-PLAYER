# M3U Player para Android

Versão para celular e tablet (Android 8 ou mais novo) do M3U Player: abre listas M3U/M3U8, reproduz e baixa as mídias,
com o mesmo visual no estilo Stremio da versão Windows. Feito em Kotlin + Jetpack Compose + Media3 (ExoPlayer).

Guia de uso: [docs/COMO-USAR.md](../docs/COMO-USAR.md).

## Instalar no celular

1. Baixe `M3UPlayer.apk` (página Releases do repositório) direto no celular, ou copie pelo cabo USB / Google Drive
   (pelo WhatsApp o arquivo às vezes chega corrompido).
2. Toque no arquivo. O Android vai pedir para **permitir a instalação de apps desta fonte** — permita para o app
   que você usou para abrir o arquivo (Arquivos, Drive, Chrome...).
3. Se o Google Play Protect avisar que o app não é conhecido, toque em **Instalar mesmo assim** (o app não está na
   Play Store; ele é assinado com uma chave de desenvolvimento).

## Funcionalidades

- **Listas salvas e abertas automaticamente**: cole a URL ou escolha um arquivo em **Listas** (ícone no topo).
  A lista fica salva, abre sozinha nas próximas vezes e ganha uma cópia no aparelho — abre na hora e funciona sem
  internet; é atualizada em segundo plano a cada 12 h (ou em "Atualizar lista agora"). Páginas de erro do servidor
  não apagam a cópia boa. Também dá para abrir um `.m3u` direto do gerenciador de arquivos.
- **Visual no estilo Stremio**: abas **Início / TV / Filmes / Séries / Downloads** na barra de baixo, linhas por grupo,
  cards com pôster (filmes/séries) ou logo (canais), filtros por grupo, busca.
- **Séries agrupadas**: episódios `Nome S01E02` viram um card só; a página da série tem temporadas, episódios, o que
  já foi visto e o botão "Continuar T1 E3".
- **Pular abertura**: botão no começo dos episódios. Pula até 1:30 por padrão e aprende o tempo certo de cada série
  quando você ajusta o ponto logo depois.
- **Próximo episódio**: no fim do episódio aparece a contagem "Próximo episódio em 5…" com **Assistir agora** e **Cancelar**.
- **Continuar assistindo**: o app guarda onde você parou e continua dali ("Continuando de 12:34" com a opção
  "Começar do início"). Os cards mostram a barra de progresso.
- **Player** em tela cheia (paisagem): **os controles somem após 3 s**; toque para mostrar/esconder, toque duplo nas
  laterais para voltar/avançar 10 s, anterior/próximo (episódio ou canal), ajuste da imagem (ajustar/preencher/esticar).
- **Assistir na TV** (botão de TV no topo do player):
  - **Transmitir para a TV** — Chromecast, Google TV e TVs com Chromecast integrado, na mesma rede Wi-Fi.
    A TV busca o vídeo direto do servidor, continua de onde o celular estava e o celular vira controle remoto
    (pode até bloquear a tela). Ao desconectar, o vídeo volta para o celular de onde a TV parou.
  - **Espelhar a tela** — abre o espelhamento do Android (Smart View, Miracast, "Transmitir tela") para TVs sem
    Chromecast; funciona com qualquer canal, pois a TV só recebe a imagem do celular.
- **Downloads** em segundo plano, com notificação de progresso: arquivos comuns, HLS (inclusive AES-128) e gravação de
  canais ao vivo até você tocar em "Parar". A aba mostra o pôster de cada mídia e agrupa os episódios da mesma série.
  No topo dela, **Alterar pasta** escolhe onde salvar os filmes e canais e onde salvar as séries (cada série numa
  subpasta); sem escolha, vai para **Downloads/M3U Player**. A aba mostra tudo o que o app já baixou nessas pastas,
  inclusive em outras vezes que ele foi aberto.
- **Prévia ao tocar**: tocar numa mídia abre um card grande (cerca de metade da tela) que toca trechos dela **com som**,
  com o progresso e os botões Assistir/Baixar ("Episódios" nas séries; o menu ⋮ tem assistir do início, copiar URL e
  remover de "Continuar assistindo"). Toque no card para abrir a mídia ou fora dele para voltar. Se você desligar o som,
  as próximas prévias abrem sem som.
- **Imagens da própria lista, mais rápidas**: imagens do TMDB pedidas no tamanho do card (a mesma imagem, 4 a 7 vezes
  menor) e cache em disco. Cada linha da página inicial mostra todos os títulos da categoria ao deslizar para o lado.

## Compilar

Requer o Android SDK (vem com o Android Studio) e JDK 17+ (o do Android Studio serve). Abra esta pasta no Android
Studio, ou pela linha de comando:

```bash
./gradlew :app:assembleRelease      # Windows: gradlew.bat :app:assembleRelease
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`. O `local.properties` (criado pelo Android Studio, fora do
Git) diz onde está o Android SDK.

**Windows e caminhos longos:** o Windows limita caminhos a 260 caracteres e as pastas de build do Android são profundas.
Se o build falhar por isso, coloque no seu `local.properties` uma pasta curta para as saídas:

```properties
m3u.buildRoot=C:/M3UBuild
```

e o APK passa a sair em `C:/M3UBuild/app/outputs/apk/release/app-release.apk`.

**Assinatura:** o release usa a chave de depuração do Android Studio (serve para instalar direto no celular). Para a
Play Store, crie uma chave própria e configure em `app/build.gradle.kts`; nunca suba a chave para o Git.

## Testes

```bash
gradlew.bat :app:testDebugUnitTest
```

50 testes: leitura de listas, separação TV/filmes/séries, agrupamento de episódios, histórico, pular abertura,
downloads já existentes, pastas de download, e downloads (direto, HLS, HLS AES-128, playlist master, 404) e listas
(cópia offline, página de erro, Latin-1) contra um servidor HTTP local.

## Limitações

- Diferente da versão Windows (que usa o VLC), o player do Android é o ExoPlayer: toca HLS, MPEG-TS, MP4, MKV, MP3 e
  afins, mas **não toca RTMP/RTSP/UDP**. Vídeos H.265 dependem do aparelho suportar.
- Download não é possível em streams com DRM nem em HLS com áudio separado do vídeo.
- Transmitir para o Chromecast usa o receptor padrão do Google, que toca HLS (.m3u8) e MP4, mas **não toca canais
  MPEG-TS (.ts) ao vivo**, nem servidores que exigem user-agent/referer ou têm certificado HTTPS inválido.
  Nesses casos o app avisa e a saída é "Espelhar a tela".
- O Android só deixa o app ver os arquivos que ele mesmo baixou: depois de desinstalar e instalar de novo, downloads
  antigos da pasta padrão não aparecem na aba (os arquivos continuam no aparelho).

## Uso responsável

Baixe apenas conteúdo que você tem direito de copiar. Muitas listas IPTV distribuem canais sem autorização.

## Estrutura

```
app/src/main/java/com/m3uplayer/app/
  M3UApp.kt                     objetos compartilhados (HTTP, histórico, listas, downloads)
  MainActivity.kt               navegação, menu dos cards, permissão de notificação
  model/Models.kt               canal/filme/episódio e série
  data/M3UParser.kt             leitura de M3U linha a linha
  data/PlaylistLoader.kt        download da lista, cópia local, validação
  data/MediaLibrary.kt          classificação TV/filme/série e agrupamento de episódios
  data/WatchHistory.kt          histórico e posição de cada item
  data/AppSettings.kt           listas salvas, pastas de download, servidores confiáveis
  download/                     downloads (direto, HLS + AES-128), serviço em segundo plano, destino (MediaStore ou
                                pasta escolhida) e leitura dos downloads já existentes
  player/                       player (ExoPlayer) e controles que se escondem
  ui/                           tema, ViewModel, telas (início, série, listas, downloads) e cards
app/src/test/                   testes
```
