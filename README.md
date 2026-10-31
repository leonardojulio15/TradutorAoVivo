# Tradutor ao Vivo (EN → PT-BR) — Android

Traduz em tempo real o inglês que você está ouvindo:

- **🎙️ Microfone** — conversas, aulas, palestras, reuniões ao seu redor.
- **📱 Áudio do celular** — vídeos, podcasts, lives e aulas online tocando no próprio aparelho (não precisa de fone/microfone; captura o som direto do sistema).

O inglês aparece enquanto a pessoa fala (cinza) e a tradução em português (negrito) sai logo que ela termina a frase.

## Como funciona (por que é preciso)
1. **Transcrição:** áudio enviado em streaming (PCM 24 kHz) para o modelo `gpt-4o-transcribe` da OpenAI (Realtime API), com detecção de pausas e redução de ruído.
2. **Tradução:** cada frase é traduzida por um LLM (`gpt-4.1` por padrão) que recebe as **8 frases anteriores como contexto** — isso resolve pronomes, mantém a terminologia consistente, corrige palavras mal reconhecidas e evita tradução literal de expressões.

Os modelos podem ser trocados na tela inicial (ex.: `gpt-4o-mini-transcribe` e `gpt-4.1-mini` para ficar mais rápido e barato).

## Requisitos
- Android 10 ou superior.
- Uma chave da API OpenAI (platform.openai.com → API keys) com créditos. Custo aproximado: alguns centavos de dólar por minuto de fala — confira a tabela de preços atual da OpenAI.
- Internet durante o uso.

## Como gerar o APK

### Opção A — pelo GitHub, sem instalar nada (dá pra fazer pelo celular)
1. Crie um repositório no GitHub e envie todos estes arquivos (pode usar "Add file → Upload files" e arrastar o conteúdo da pasta, mantendo as subpastas).
2. Vá na aba **Actions** → "Gerar APK" → **Run workflow** (ele também roda sozinho a cada envio).
3. Em ~5 min, abra a execução concluída e baixe o artefato **TradutorAoVivo-apk** (vem em .zip; dentro está o `app-debug.apk`).
4. No celular, abra o APK e permita "instalar apps de fontes desconhecidas".

### Opção B — Android Studio
Abra a pasta do projeto, aguarde o Gradle sincronizar e use **Build → Build APK(s)**, ou conecte o celular e clique em ▶ Run.

## Uso
1. Cole a chave da API, escolha a fonte e toque em **Iniciar**.
2. Microfone: aceite a permissão de áudio. Áudio do celular: aceite também o aviso de captura de tela/áudio do Android (só o **som** é usado).
3. A tradução continua com a tela desligada ou com outro app aberto; pare pela notificação ou pelo botão.
4. **A− / A+** ajustam o tamanho da letra; o texto pode ser selecionado e copiado.

## Limitações conhecidas
- Alguns apps bloqueiam a captura de áudio interno (ex.: ligações do WhatsApp, alguns apps de streaming com DRM). Para esses, use o modo Microfone com o som no alto-falante.
- A tradução de cada frase sai ao fim dela (após ~0,5 s de pausa). Falas muito longas sem pausa demoram mais para aparecer em português.
- A chave fica salva só no aparelho, em armazenamento privado do app.
