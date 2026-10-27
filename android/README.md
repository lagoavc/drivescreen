# DriveScreen Android App

Aplicação nativa em Kotlin para ligação BLE com ESP32-S3, gestão de multimédia, notificações e diagnóstico.

## Estado atual
- Cliente BLE GATT com filtro pelo UUID DriveScreen, permissões de execução e reconexão.
- Handshake `HELLO`/`ACK`, enquadramento fragmentado e keep-alive `PING`/`PONG`.
- Ecrã de diagnóstico que envia texto ao ESP32 e apresenta a resposta.
- Sessões multimédia Android com metadados BLE e comandos de reprodução, após consentimento explícito nas Definições do sistema.
- Notificações individuais e navegação continuam por implementar.

## Notas
- Requer Android Studio, JDK 17 e Android SDK 34 para build local; abrir esta pasta no Android Studio e sincronizar o Gradle.
- O build `:app:assembleDebug` passou no GitHub Actions (Gradle 8.2/JDK 17); o artefacto `drivescreen-debug-apk` fica disponível por 7 dias em cada execução.
- Este ambiente local não tem Java, Gradle, Android SDK ou `adb`; a instalação em telefone permanece por validar.
- As permissões de scan e ligação Bluetooth são pedidas apenas quando o utilizador inicia uma ligação.
- O acesso a `NotificationListenerService` é uma autorização especial do Android: a app explica o motivo e encaminha o utilizador para as Definições. Nesta versão é usado apenas para sessões multimédia; não se recolhem nem enviam notificações.
- A navegação será testada por notificações; não há garantia de que Google Maps, OsmAnd ou Waze exponham sempre campos estruturados.
