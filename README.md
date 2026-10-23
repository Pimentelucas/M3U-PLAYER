# M3U Player

Player de listas **IPTV / M3U / M3U8** para **Windows** e **Android**, com visual no estilo Stremio: abre a sua
lista, organiza tudo em **TV ao vivo, Filmes e Séries**, reproduz e **baixa** filmes, episódios e gravações de canais.

| | Windows | Android |
|---|---|---|
| Tecnologia | C# / WPF + motor do VLC (libVLC) | Kotlin + Jetpack Compose + Media3 (ExoPlayer) |
| Requisitos | Windows 10 ou 11 (64 bits) | Android 8.0 ou mais novo (celular e tablet) |

## Destaques

- **Lista sempre pronta**: a lista (arquivo ou URL) fica salva, abre sozinha, ganha uma cópia no aparelho (abre na hora e
  funciona sem internet) e é atualizada em segundo plano a cada 12 h. Testado com listas de 316 mil itens.
- **Organização automática**: separa canais, filmes e séries; episódios `Nome S01E02` viram uma série com temporadas.
- **Prévia estilo Netflix**: clicar numa mídia abre um card grande que toca trechos dela; clique de novo para assistir.
- **Continuar assistindo**: retoma de onde você parou; **pular abertura** (aprende o tempo de cada série) e
  **próximo episódio** com contagem regressiva.
- **Downloads**: arquivos comuns, HLS (inclusive com criptografia AES-128) e gravação de canais ao vivo; pastas separadas
  para filmes e séries; a aba mostra tudo o que já foi baixado.
- **Android**: transmitir para a TV (Chromecast) ou espelhar a tela.

## Baixar

Baixe a versão mais recente na página **[Releases](../../releases)** do repositório:

| Arquivo | Para |
|---|---|
| `M3UPlayer-Windows-x64.zip` | Windows 10/11 64 bits (não precisa instalar nada além dele) |
| `M3UPlayer.apk` | Celulares e tablets Android |

> Prefere compilar você mesmo? Veja [Compilar a partir do código](#compilar-a-partir-do-código).

## Instalar

### Windows

1. Extraia o `M3UPlayer-Windows-x64.zip` numa pasta de **caminho curto**, por exemplo `C:\M3UPlayer`
   (o motor do VLC não carrega de pastas com caminho muito longo; o app avisa se isso acontecer).
2. Abra **`M3UPlayer.exe`**.
3. Se o Windows mostrar **"O Windows protegeu o computador"** (SmartScreen), clique em **Mais informações → Executar
   assim mesmo**. O aviso aparece porque o app não tem assinatura digital paga.
4. (Opcional) Clique com o botão direito no `M3UPlayer.exe` → **Enviar para → Área de trabalho (criar atalho)**.

Para atualizar, feche o app e substitua os arquivos da pasta pelos da versão nova. Suas listas, histórico e
configurações ficam em `%APPDATA%\M3UPlayer` e são mantidos.

### Android

1. Baixe o `M3UPlayer.apk` direto no celular (ou copie do computador pelo cabo, Google Drive etc.).
2. Toque no arquivo. O Android vai pedir para **permitir a instalação de apps desta fonte** — permita para o app que
   abriu o arquivo (Arquivos, Chrome, Drive...).
3. Se o **Google Play Protect** avisar que o app não é conhecido, toque em **Mais detalhes → Instalar mesmo assim**
   (o app não está na Play Store).
4. Na primeira vez que você baixar algo, o app pede permissão para mostrar **notificações** (é onde aparece o progresso).

> Ao enviar o APK pelo WhatsApp, ele às vezes chega renomeado ou corrompido ("problema ao analisar o pacote").
> Prefira baixar pelo navegador, Google Drive ou cabo USB.

## Começo rápido

1. Abra o app e clique em **Listas** (Windows: botão no canto superior direito; Android: ícone ☰+ no topo).
2. Cole a **URL da sua lista** (`http://...` ou `https://...`) ou escolha um **arquivo `.m3u`** e confirme.
   No Windows também dá para **arrastar o arquivo** para a janela.
3. Navegue pelas abas **Início, TV ao vivo, Filmes e Séries**. Clique (ou toque) numa mídia para ver a prévia e clique
   de novo para assistir.
4. Para baixar, use o botão de **download** da prévia ou da página da série. Acompanhe na aba **Downloads**.

O guia completo, com todas as telas, atalhos e dicas, está em **[docs/COMO-USAR.md](docs/COMO-USAR.md)**.

## Privacidade e segurança

- A URL da sua lista (que normalmente contém **usuário e senha** do seu provedor) fica **só no seu aparelho**:
  Windows em `%APPDATA%\M3UPlayer\settings.json`; Android nas configurações do app (fora do backup na nuvem).
  Não compartilhe esses arquivos nem prints da URL.
- O app não envia nada para servidores próprios: ele só acessa a lista, as imagens e os vídeos que a lista indica.
- Downloads só gravam arquivos de **vídeo, áudio e legenda**; o botão "Abrir" nunca executa programas.
- Se o servidor da lista tiver um certificado HTTPS inválido, o Android pergunta antes de **confiar nele** (e dá para
  desfazer depois em **Listas**).

## Uso responsável

Este projeto é um **player**: ele não fornece nenhuma lista nem conteúdo. Use somente listas e conteúdos que você tem
direito de acessar e baixe apenas o que tem permissão para copiar. Muitas listas IPTV distribuem canais sem
autorização dos detentores dos direitos.

## Compilar a partir do código

### Windows

Requer o [.NET 8 SDK](https://dotnet.microsoft.com/download) (ou mais novo).

```bash
dotnet run --project M3UPlayer
```

Pacote para distribuir, **sem precisar do .NET instalado** (o que vai para o Releases):

```bash
dotnet publish M3UPlayer -c Release --self-contained true -o dist/M3UPlayer
```

Versão menor, que exige o [.NET 8 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/8.0) no PC:

```bash
dotnet publish M3UPlayer -c Release -o dist/M3UPlayer
```

### Android

Requer o Android SDK (vem com o [Android Studio](https://developer.android.com/studio)) e JDK 17+ (o do Android Studio
serve). Abra a pasta `M3UPlayer.Android` no Android Studio, ou pela linha de comando:

```bash
cd M3UPlayer.Android
./gradlew :app:assembleRelease
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`. Detalhes em
[M3UPlayer.Android/README.md](M3UPlayer.Android/README.md).

> O APK de release usa a chave de **depuração** do seu Android Studio (serve para instalar direto no celular).
> Para publicar na Play Store, crie uma chave própria e configure em `app/build.gradle.kts` — **nunca** suba a chave
> (`.jks`/`.keystore`) para o GitHub; o `.gitignore` já as ignora.

### Testes

```bash
dotnet run --project M3UPlayer.Tests                   # Windows
cd M3UPlayer.Android && ./gradlew :app:testDebugUnitTest  # Android
```

Os testes cobrem a leitura de listas, a separação TV/filmes/séries, histórico, pular abertura, carrossel, pastas de
download e os modos de download (direto, HLS, HLS AES-128, playlist master, erro 404) contra um servidor HTTP local.

Medir o tempo de abrir uma lista grande (Windows):

```bash
dotnet run -c Release --project M3UPlayer.Tests -- --bench <url ou arquivo .m3u>
```

### Diagnóstico (Windows)

- Erros inesperados vão para `%APPDATA%\M3UPlayer\error.log`.
- `M3UPLAYER_VLC_LOG=C:\caminho\vlc.log` grava o log do VLC.
- `M3UPLAYER_DATA=C:\pasta` usa outra pasta de dados (útil para testes ou para usar o app em modo portátil).

## Estrutura do repositório

```
M3UPlayer/                  app Windows (WPF)
  MainWindow*.cs / .xaml    telas: início, abas, série, downloads, prévia e player
  Art.cs                    carregamento das imagens dos cards em segundo plano
  Models/                   item da lista, série, linha do carrossel
  Services/                 leitura da lista, organização, histórico, downloads, cache de imagens, configurações
  Themes/Dark.xaml          tema escuro
M3UPlayer.Tests/            testes automatizados da versão Windows
M3UPlayer.Android/          app Android (Kotlin/Compose) — ver o README da pasta
docs/COMO-USAR.md           guia de uso
```

## Limitações conhecidas

- Streams com DRM (Widevine/PlayReady, HLS `SAMPLE-AES`) não podem ser baixados.
- Listas M3U não dizem se um item é canal, filme ou episódio; o app deduz (título `S01E02`, ano, formato da URL).
  Em listas muito incomuns algum item pode cair na aba errada — ele continua aparecendo na busca e no grupo.
- O Android não toca RTMP/RTSP/UDP (o Windows, com o VLC, toca).
