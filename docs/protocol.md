# Protocolo BLE — DriveScreen

**Versão:** `1.0.0`

**Transporte:** BLE GATT, Android central/cliente e ESP32 peripheral/servidor

**Codificação:** mensagens JSON em UTF-8, enquadradas em fragmentos binários
**Estado:** contrato definido; a primeira implementação suporta handshake, keep-alive e diagnóstico. Navegação, multimédia e notificações estão reservadas para fases seguintes.

## Serviço GATT

| Elemento | UUID | Propriedades | Direção |
|---|---|---|---|
| Serviço DriveScreen | `4a9f5d80-6b13-4c28-9e40-7f6d2b830000` | — | — |
| Comandos e dados de entrada | `4a9f5d80-6b13-4c28-9e40-7f6d2b830001` | Write, Write Without Response | Android → ESP32 |
| Eventos e dados de saída | `4a9f5d80-6b13-4c28-9e40-7f6d2b830002` | Notify, Read | ESP32 → Android |

O nome anunciado é `DriveScreen`. A app deve filtrar pelo UUID do serviço, e não depender apenas do nome.

## Enquadramento e fragmentação

Cada valor GATT transporta um fragmento com este cabeçalho, em ordem de bytes:

| Offset | Tamanho | Campo | Definição |
|---|---:|---|---|
| 0 | 2 | Magic | `D5 53` (ASCII `DS`) |
| 2 | 2 | ID da mensagem | inteiro sem sinal, little-endian; incrementado por direção |
| 4 | 1 | Índice | começa em `0` |
| 5 | 1 | Número de fragmentos | entre `1` e `255` |
| 6 | 2 | Comprimento total | bytes UTF-8 do JSON, little-endian |
| 8 | variável | Dados | parte contígua da mensagem JSON |

- MTU BLE por omissão: 23 bytes. O fragmento usa no máximo `MTU - 3` bytes ATT, incluindo o cabeçalho de 8 bytes.
- O Android pode pedir um MTU maior; nenhum lado assume que o pedido foi aceite. Máximo suportado pela aplicação: 512 bytes por característica.
- Comprimento JSON máximo: 2048 bytes. O número de fragmentos, os índices, o comprimento total e o magic são validados antes de aceitar a mensagem.
- Fragmentos são enviados por ordem. O receptor mantém uma única mensagem parcial por ligação e descarta-a se chegar uma sequência, índice, comprimento ou magic inválido.
- Tempo máximo para receber a mensagem completa: 5 segundos. O limite de uma escrita BLE aplica-se a cada fragmento, não ao JSON completo.
- Escritas Android são serializadas: o fragmento seguinte só é escrito depois do callback de conclusão do anterior.
- As notificações são enviadas sequencialmente. Uma ligação nova descarta qualquer mensagem parcial da ligação anterior.

## Mensagens JSON

Todas as mensagens incluem `v`, `type` e `id`. `id` é um inteiro JSON entre `0` e `65535`, independente em cada direção.

### Handshake e conectividade

Android envia, logo após descobrir as características:

```json
{"v":"1.0.0","type":"HELLO","id":1,"payload":{"app_version":"0.1.0"}}
```

O ESP32 responde `ACK` com o ID da mensagem confirmada em `payload.ack_id`, a versão de firmware e o nome do dispositivo. Se `v` não corresponder, responde `ERROR` com `code:"VERSION_MISMATCH"` e fecha a sessão lógica.

O Android envia `PING` de 10 em 10 segundos; o ESP32 responde `PONG` com um novo `id` e `payload.echo_id` igual ao ID do ping recebido. Após 30 segundos sem mensagem válida, o Android considera a ligação inativa, fecha GATT e inicia nova descoberta após 5 segundos.

Depois do `ACK`, a app envia `DIAGNOSTIC` com texto até 128 bytes. O ESP32 apresenta o texto na interface e responde `DIAGNOSTIC_REPLY` com `payload.reply_to` e `payload.text`. A app confirma a resposta no ecrã de diagnóstico. Esta mensagem é também o teste funcional mínimo de ponta a ponta.

### Tipos e direção

| Tipo | Direção | Conteúdo principal |
|---|---|---|
| `HELLO` | Android → ESP32 | `payload.app_version` |
| `ACK` | ESP32 → Android | `payload.ack_id`, `payload.device`, `payload.firmware_version` |
| `PING` / `PONG` | Android ↔ ESP32 | keep-alive; `PONG.payload.echo_id` |
| `DIAGNOSTIC` | Android → ESP32 | `payload.text` (1–128 bytes UTF-8) |
| `DIAGNOSTIC_REPLY` | ESP32 → Android | `payload.reply_to`, `payload.text` |
| `NAV_UPDATE` | Android → ESP32 | estado, manobra, distância e origem |
| `MEDIA_STATE` | Android → ESP32 | sessão ativa, faixa, artista, álbum, reprodução e posição |
| `MEDIA_CMD` | ESP32 → Android | `payload.cmd`: `play`, `pause`, `previous` ou `next` |
| `NOTIF_LIST` | Android → ESP32 | lista limitada e modo de privacidade |
| `ERROR` | Android ↔ ESP32 | `payload.code`, `payload.message`; `payload.request_id` opcional |

### Navegação

`NAV_UPDATE.payload` exige `state` e `source`. Os estados são `idle`, `navigating`, `stale`, `arrived` e `error`; `source` é `REAL`, `DEMO` ou `NONE`. `maneuver` pode ser `none`, `straight`, `left`, `right`, `slight_left`, `slight_right`, `sharp_left`, `sharp_right`, `roundabout_left`, `roundabout_right`, `u_turn` ou `arrived`. `distance_m`, `remaining_m`, `speed_kmh`, `instruction`, `provider` e `received_at_ms` são opcionais; valores numéricos devem ser finitos e não negativos.

Uma instrução extraída de notificação declara um `provider` correspondente à aplicação observada (`google_maps_notification`, `osmand_notification` ou `waze_notification`) e `source:"REAL"` apenas para texto efetivamente analisado. A integração depende da estrutura e idioma da notificação, é best-effort e não equivale a uma API pública de navegação ou a instruções garantidas. Conteúdo ausente ou ambíguo produz `state:"idle"`/`"stale"` com `source:"NONE"` e não inventa uma manobra. OsmAnd é a primeira alternativa proposta para teste no telefone; a compatibilidade será medida antes de escolher um fornecedor.

### Multimédia

`MEDIA_STATE.payload` exige `active`, `playing` e `source`. `source` é `REAL` quando `active:true` e `NONE` quando `active:false`. `title`, `artist`, `album`, `position_ms`, `duration_ms` e `app` são opcionais; strings são UTF-8 e limitadas a 96 bytes (`title`, `artist`, `album`) ou 48 bytes (`app`), e os tempos são milissegundos não negativos. Sem sessão autorizada, a app envia `active:false`, `playing:false`, `source:"NONE"` e omite os metadados. O ecrã apresenta título/artista/álbum e estado de reprodução; os tempos são reservados para apresentação de progresso. `MEDIA_CMD.payload.cmd` aceita `play`, `pause`, `previous` e `next`; o Android verifica as ações suportadas pela sessão antes de executar o comando. Os controlos do ecrã ficam desativados sem sessão ativa e são limpos quando BLE se desliga.

Imagens de capa não fazem parte do protocolo 1.0.0. A característica e a fragmentação aceitam até 2048 bytes de JSON; não se enviam imagens codificadas em Base64. Uma transferência binária separada requer uma extensão versionada e testes de memória.

### Notificações e privacidade

`NOTIF_LIST.payload` exige `privacy_mode` e `items`. Cada item contém `id` e `app`; `title`, `text`, `time_ms` e `unread` são opcionais. A app só recolhe notificações depois de o utilizador conceder acesso a `NotificationListenerService`. No modo de privacidade, `title` e `text` devem ser removidos antes do envio. Limite: 10 itens e 256 bytes por campo textual; mensagens maiores são truncadas em UTF-8 válido.

## Validação, erros e privacidade

- Rejeitar UTF-8/JSON inválido, campos obrigatórios ausentes, versão incompatível, tipos desconhecidos, strings acima dos limites e valores fora do intervalo.
- `ERROR` usa códigos estáveis: `VERSION_MISMATCH`, `INVALID_FRAME`, `MESSAGE_TOO_LARGE`, `INVALID_JSON`, `INVALID_MESSAGE`, `UNSUPPORTED_TYPE`, `NOT_READY` e `TIMEOUT`.
- Não enviar notificações, posição, metadados de media ou instruções de navegação fictícios como dados reais.
- O modo de privacidade inicia ativo. A autorização do Android é sempre explícita; o firmware não solicita nem contorna permissões do telefone.
- BLE não fornece confidencialidade por si só. A versão 1.0.0 não envia segredos ou dados de autenticação; dados pessoais permanecem desativados até à aprovação explícita e ligação validada pelo utilizador.

## Reconexão

O Android é responsável pela ligação. Após desconexão, limpa a reassemblagem e os estados temporários, espera 5 segundos, volta a procurar o UUID do serviço e repete `HELLO`. A interface mostra o estado real `a procurar`, `a ligar`, `ligado` ou `desligado`; nunca mostra ligado antes de o handshake terminar.
