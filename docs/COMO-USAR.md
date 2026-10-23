# Como usar o M3U Player

Guia das versões **Windows** e **Android**. As duas funcionam do mesmo jeito; quando algo muda, está indicado.
Para baixar e instalar, veja o [README](../README.md#baixar).

- [1. Abrir a sua lista](#1-abrir-a-sua-lista)
- [2. Navegar](#2-navegar)
- [3. Prévia e assistir](#3-prévia-e-assistir)
- [4. Séries](#4-séries)
- [5. O player](#5-o-player)
- [6. Continuar assistindo](#6-continuar-assistindo)
- [7. Downloads](#7-downloads)
- [8. Assistir na TV (Android)](#8-assistir-na-tv-android)
- [9. Atalhos de teclado (Windows)](#9-atalhos-de-teclado-windows)
- [10. Problemas comuns](#10-problemas-comuns)

---

## 1. Abrir a sua lista

1. Abra **Listas**:
   - **Windows:** botão **Listas** no canto superior direito.
   - **Android:** ícone de lista com **+** no topo da tela.
2. Cole a **URL** da lista (a que o seu provedor enviou, começando com `http://` ou `https://`) e clique em
   **Carregar**, ou use **Abrir arquivo...** para escolher um `.m3u`/`.m3u8` salvo no aparelho.
   - **Windows:** também dá para **arrastar o arquivo** para a janela.
   - **Android:** dá para abrir um `.m3u` direto pelo gerenciador de arquivos. Se você já tem uma lista em uso, o app
     pergunta antes de trocar.
3. Pronto: a lista fica **salva** e abre sozinha nas próximas vezes.

O que acontece por trás:

- O app guarda uma **cópia da lista** no aparelho. Nas próximas vezes ela abre na hora, até sem internet, e é
  **atualizada em segundo plano** a cada 12 horas. Para atualizar agora, use **Listas → Atualizar lista agora**.
- Se o servidor responder com uma página de erro em vez da lista, a cópia boa é mantida.
- Em **Listas** ficam as últimas listas usadas; clique numa para trocar, ou no **×** para removê-la.
- **Android — "certificado não reconhecido":** alguns provedores usam HTTPS com certificado inválido. O app mostra o
  aviso e o botão **Confiar neste servidor**; use só se você conhece o provedor.

## 2. Navegar

- **Início**: "Continuar assistindo" e uma linha para cada grupo da lista.
- **TV ao vivo / Filmes / Séries**: só os itens daquele tipo (a aba some se a lista não tiver nenhum).
  - **Windows:** as **categorias** (grupos da lista) ficam na **coluna da esquerda**. Em **Todos**, cada categoria é um
    **carrossel**: a seta **›** no fim da linha mostra os próximos títulos dela, e a **‹** volta.
    **Ver tudo** abre a categoria inteira em grade.
  - **Android:** as categorias ficam no topo; cada linha mostra todos os títulos da categoria ao deslizar para o lado.
- **Buscar** (Windows: campo no topo ou **Ctrl+F**; Android: lupa): procura em canais, filmes e séries.
- **Imagens:** cada card usa a imagem que a lista traz (`tvg-logo`). Quando a lista não tem imagem, aparecem as iniciais
  do título.

## 3. Prévia e assistir

1. **Clique** (Android: **toque**) numa mídia: abre um **card grande** que toca trechos dela **com som**.
2. No card:
   - **clique no card** (ou em **Assistir/Continuar**) para abrir a mídia;
   - **clique fora** do card (ou **Esc** no Windows) para voltar à lista;
   - 🔊 liga/desliga o som — a escolha vale para as próximas prévias;
   - ⬇ baixa a mídia; nas séries, **Ver episódios** abre a página da série;
   - **Android:** o menu **⋮** tem "Assistir do início", "Copiar URL" e "Remover de Continuar assistindo".
3. **Windows:** o **botão direito** num card abre essas mesmas opções (Reproduzir, Assistir do início, Baixar,
   Copiar URL...).

## 4. Séries

Episódios com o padrão `Nome S01E02` na lista viram **uma série só**. Na página da série:

- escolha a **temporada** e clique num episódio para assistir (o ✓ marca os que você já viu);
- o botão principal mostra **Continuar T1 E3** (ou o próximo episódio);
- ⬇ ao lado de um episódio baixa só ele; **Baixar temporada** baixa a temporada inteira (o app confirma antes quando
  são muitos episódios).

## 5. O player

- Ocupa a tela toda. **Os controles somem depois de 3 segundos** parado e voltam ao mexer o mouse/tocar na tela.
- Play/pausa, voltar/avançar 10 s, episódio/canal anterior e próximo, volume, tela cheia e baixar.
- **Pular abertura:** nos episódios, aparece o botão **Pular abertura** nos primeiros minutos. Ele pula até 1:30; se
  você ajustar o ponto logo depois (ou arrastar a barra no começo do episódio), o app **aprende** o tempo certo daquela
  série e usa nos próximos episódios.
- **Próximo episódio:** no fim de um episódio aparece **"Próximo episódio em 5…"**. Ele começa sozinho; use
  **Assistir agora** para não esperar ou **Cancelar** para ficar.
- **Android:** toque duplo na esquerda/direita da tela volta/avança 10 s; o botão de imagem alterna entre ajustar,
  preencher e esticar.

## 6. Continuar assistindo

O app lembra **onde você parou** em cada filme e episódio. Ao abrir de novo, ele continua dali (com a opção
**Começar do início**). Os cards mostram uma barra de progresso; o que você assistiu até o fim ganha um ✓.
Para tirar algo da linha "Continuar assistindo", use **Remover de "Continuar assistindo"** no menu do card.

## 7. Downloads

**Baixar:** botão ⬇ na prévia, ao lado de um episódio, **Baixar temporada**, ou o menu do card.

**Acompanhar:** aba **Downloads**. Ela mostra o progresso, a velocidade, o botão **Parar** e, quando termina,
**Abrir** (Windows também tem **Mostrar na pasta**). Episódios da mesma série ficam agrupados sob a imagem da série.
A aba mostra **tudo o que já está nas pastas de download**, inclusive o que foi baixado em outras vezes.
**Limpar falhas** tira da lista só os downloads que falharam ou foram cancelados.

**Onde ficam os arquivos:** no topo da aba Downloads há duas pastas, cada uma com **Alterar pasta**:

| | Padrão no Windows | Padrão no Android |
|---|---|---|
| Filmes e canais | `Vídeos\M3U Player` | `Downloads/M3U Player` |
| Séries | a mesma, com uma subpasta por série | a mesma, com uma subpasta por série |

O que dá para baixar:

- **arquivos comuns** (mp4, mkv, ts, mp3...): download direto;
- **HLS (`.m3u8`)**: o app escolhe a melhor qualidade, baixa os pedaços (inclusive com criptografia AES-128) e junta
  num arquivo só;
- **canais ao vivo**: são **gravados** até você clicar em **Parar** (o arquivo é mantido);
- **Windows:** outros protocolos (rtmp, rtsp, udp) são gravados pelo VLC.

No Android os downloads continuam com o app em segundo plano (com notificação de progresso).
**Não dá para baixar** conteúdo com DRM.

## 8. Assistir na TV (Android)

No player, toque no ícone de **TV** no topo:

- **Transmitir para a TV** — Chromecast, Google TV e TVs com Chromecast integrado, na **mesma rede Wi-Fi**. A TV busca
  o vídeo direto do servidor e o celular vira controle remoto (pode até bloquear a tela). Ao desconectar, o vídeo volta
  para o celular de onde a TV parou. Alguns canais `.ts` ao vivo não tocam no Chromecast; o app avisa.
- **Espelhar a tela** — abre o espelhamento do Android (Smart View, Miracast, "Transmitir tela"). Funciona com
  qualquer canal e com TVs sem Chromecast.

## 9. Atalhos de teclado (Windows)

| Tecla | Ação |
|---|---|
| Espaço / clique no vídeo | Reproduzir/pausar |
| F / F11 / duplo clique no vídeo | Tela cheia |
| Esc | Sai da tela cheia / fecha o player / fecha a prévia |
| ← / → | Voltar/avançar 10 s |
| ↑ / ↓ | Volume |
| M | Mudo (no player e na prévia) |
| N / P | Próximo / anterior |
| S | Pular abertura |
| Enter | Na prévia: assistir. Na contagem do próximo episódio: assistir já |
| Ctrl+F | Buscar |
| Ctrl+O | Abrir arquivo de lista |

## 10. Problemas comuns

**A lista não abre / "não foi possível carregar".** Confira a URL (copie de novo do provedor) e a internet. Se a lista
já tinha aberto antes, o app usa a cópia salva até o servidor voltar.

**Um canal ou filme não toca.** Normalmente é o servidor do provedor (fora do ar, link vencido ou bloqueado).
Teste outro item da mesma lista. No Android, links `rtmp://`, `rtsp://` e `udp://` não tocam (no Windows tocam).

**As imagens demoram ou não aparecem.** As imagens vêm dos servidores indicados na lista. O app guarda as que já
carregaram, então elas abrem na hora nas próximas vezes. As que não carregam mostram as iniciais do título.

**Windows: "O Windows protegeu o computador".** Clique em **Mais informações → Executar assim mesmo**.

**Windows: o vídeo não aparece e o app fala de "caminho longo".** Mova a pasta do app para um caminho curto, como
`C:\M3UPlayer`.

**Android: "problema ao analisar o pacote" ao instalar.** O APK chegou incompleto (comum pelo WhatsApp). Baixe de
novo pelo navegador, Google Drive ou cabo USB.

**Android: "certificado não reconhecido".** Veja o item [1](#1-abrir-a-sua-lista) — botão **Confiar neste servidor**.

**Onde ficam meus dados?** Windows: `%APPDATA%\M3UPlayer` (configurações, histórico, cópias das listas) e
`%LOCALAPPDATA%\M3UPlayer\images` (imagens). Android: dentro do próprio app (apagados ao desinstalar). A URL da lista
costuma conter seu usuário e senha do provedor — não compartilhe esses arquivos.
