# NX Extract

App Android que extrai o **ExeFS** e o **RomFS** de jogos de Nintendo Switch nos formatos **NSP**, **XCI** e **NCA**, direto no celular, sem PC.

A saída fica no formato que emuladores e ferramentas de mod/recompilação esperam:

```
NXExtract/
└── 0100XXXXXXXXXXXX/      ← ID do programa
    ├── exefs/             ← main, main.npdm, rtld, sdk, subsdk*
    └── romfs/             ← arquivos do jogo
```

## Como usar

1. Instale o APK (aba **Actions** ou **Releases**).
2. Abra o app e toque em **Conceder acesso** (acesso a todos os arquivos — é por onde ele grava milhares de arquivos rápido).
3. **Importar prod.keys**: escolha o `prod.keys` tirado do **seu** console (Lockpick_RCM). O app guarda uma cópia interna.
4. **Escolher NSP/XCI**, marque ExeFS e/ou RomFS e toque em **Extrair**.

A tela fica acesa durante a extração. Dá para cancelar a qualquer momento.

## Tirar o update (NSP só com o jogo base)

Para um NSP ou XCI que traz o jogo base e o update juntos, **Tirar o update** grava `<nome> [base].nsp` na pasta de saída só com o jogo base
(marque **Manter DLC no NSP base** para levar as DLCs junto). Os NCAs são copiados como estão, sem decifrar nem cifrar de novo: o `prod.keys`
só serve para ler os metadados (`.cnmt`) e saber qual arquivo é do base e qual é do update. Os tickets e certificados do update ficam de fora.

Só funciona quando o update está em NCAs separados dentro do arquivo. Se o update já foi mesclado no base (como nos NSPs do NSP Box), os
arquivos originais do base não estão mais lá e o app avisa que não dá.

## Chaves usadas

| Situação | Chaves |
|---|---|
| Sempre | `header_key` |
| Jogos sem ticket (XCI, a maioria dos NCAs) | `key_area_key_application_XX` |
| NSP com ticket (`.tik`) | `titlekek_XX` |

`XX` é a geração de chave do jogo (aparece no log). Se faltar uma chave, a mensagem diz qual.

## Limitações

- **Atualizações (BKTR)** sozinhas não são extraídas: elas só têm as diferenças em relação ao jogo base.
- **NSZ/XCZ/NCZ** (comprimidos) não são lidos — descomprima no PC com `nsz -D`.
- Seções **esparsas** ou **comprimidas** (raras) e tickets **personalizados** não são suportados.
- DLC (sem NCA de programa) não tem ExeFS.

## Build

O APK é compilado pelo GitHub Actions a cada push (`gradle assembleRelease`, JDK 17, Gradle 8.11.1).
Para assinar com chave fixa (atualizar sem desinstalar), cadastre os secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Sem eles, o APK sai assinado com a chave de debug.
Uma tag `v*` publica o APK como release.

O núcleo de extração (`app/src/main/java/app/nxextract/core`) é Java puro, sem dependências do Android.

Este app não contém chaves, jogos nem nada da Nintendo; use apenas com dumps e chaves dos seus próprios jogos e console.
