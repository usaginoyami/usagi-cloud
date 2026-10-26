# Usagi Cloud

Gerenciador de arquivos de **todas as suas nuvens num lugar só** (Google Drive, OneDrive, Dropbox, Mega, Box, pCloud, WebDAV, FTP, SFTP...). App Android nativo em Kotlin + Jetpack Compose, com identidade UsagiNoYami (vermelho/preto).

O app não implementa cada nuvem na mão: ele roda o [rclone](https://rclone.org) em segundo plano (`rclone rcd`, só em 127.0.0.1, com senha aleatória por execução) e conversa com ele por HTTP. Assim, qualquer nuvem que o rclone suporta (70+) pode ser adicionada com pouco código.

## O que já tem (v0.1)

- Tela inicial com todas as contas e barra de espaço usado/total
- Adicionar conta: OAuth pelo navegador (Drive, OneDrive, Dropbox, Box, pCloud) ou usuário/senha (Mega, WebDAV, FTP, SFTP)
- Navegar em pastas, criar pasta, renomear, excluir, baixar, enviar arquivo
- **Copiar/Mover entre nuvens** (ex.: Drive -> Mega) com "Colar aqui" em qualquer pasta
- **Busca unificada** em todas as contas ao mesmo tempo

## Como rodar

1. Abra a pasta no Android Studio (ele baixa o Gradle 8.9 sozinho).
2. **Coloque o rclone** em `app/src/main/jniLibs/arm64-v8a/librclone.so` (veja abaixo).
3. Rode em um celular arm64 (a maioria dos Androids atuais).

### Obtendo o binário do rclone para Android

O rclone é escrito em Go e é MIT. Ele precisa ser compilado para `android/arm64` (o binário normal de Linux pode falhar em DNS/certificados no Android). Caminho sugerido:

```bash
git clone https://github.com/rclone/rclone && cd rclone
# NDK instalado pelo Android Studio (ajuste o caminho/versão)
export NDK=$HOME/Android/Sdk/ndk/<versao>
export CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
CGO_ENABLED=1 GOOS=android GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o librclone.so .
```

Projetos open-source que já fazem isso e servem de referência: RCX e Round Sync (apps Android que embutem o rclone).

> Não consegui compilar nem testar o app no ambiente em que gerei o código (sem Android SDK). Espere ajustar um ou outro detalhe no primeiro build.

## Pontos de atenção

- **Login OAuth**: o rclone abre um servidor local na porta 53682 e o app abre o link no navegador. Para Google Drive, o ideal é criar seu próprio client ID/secret (campos opcionais na tela de adicionar conta) para não depender do limite compartilhado do rclone.
- **Downloads** vão para a pasta do app (`Android/data/com.usaginoyami.cloud/files/UsagiCloud`).
- **Busca unificada** lista tudo de cada conta; em nuvens enormes pode demorar.
- As credenciais ficam no `rclone.conf` dentro do armazenamento privado do app (`allowBackup=false`).

## Próximos passos possíveis

- Fila de transferências com progresso e cancelamento
- Visualizador de imagens/vídeo/PDF
- Detectar arquivos duplicados entre nuvens
- Sincronização/backup automático de pastas (ex.: gravações do OBS -> Mega)
- Ícone e splash com o mascote UsagiNoYami
