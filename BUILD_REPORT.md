# Luma Agent 0.6.0 · отчёт о сборке

Package `dev.luma.agent`, versionCode 13, versionName 0.6.0, minSdk 26, target/compileSdk 35.
Изменения версии — в [CHANGELOG.md](CHANGELOG.md).

## Окружение

Windows 11, Temurin (OpenJDK) 21.0.12.1+1, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20,
Compose BOM 2025.04.01, SDK 35, build-tools 35.0.0, Robolectric 4.14.1 (Native Graphics).

```
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

## Результаты

- Release собран с R8 и сокращением ресурсов, выровнен на 16 КБ, подписан v2/v3
  новым ключом (хранится вне репозитория).
- Android Lint: 0 ошибок, 23 предупреждения.
- Юнит-тесты: 121, из них 117 проходят на Windows; на GitHub CI (Linux) проходят все.
- 0.6.0 проверена на эмуляторе Android 15 с фейковым потоковым OpenAI-совместимым
  сервером: чат (рассуждения, таблица, метр т/с, копирование) и агент (курсор с потоком
  частиц, «Стоп» с таймером и т/с, панель «✦» в одном ряду, итог с рассуждениями).
  Новые тесты 0.5.2–0.5.3: запрещённые слова, локальные адреса, блокировка HTTP
  к внешним хостам, тексты лимитов, картинки ссылками, ограничение истории,
  разбор команд реальных моделей (план из нескольких команд, синонимы, `<think>`),
  долгий таймаут только для локального сервера.
- Агент проверен на эмуляторе Android 15 с локальной Qwen3-8B через Ollama: главный
  экран, чтение экрана, нажатие на строку поиска Google.
- 4 теста не проходят только на Windows-хосте, и все они касаются кода, который
  в 0.5.2–0.5.3 не менялся:
  - `VaultAgentTest.keysAreEncryptedAndSelectedConfigurationSurvivesRestart`,
    `UiTest.settingsSectionsPersistEffectsAndIndependentModelInstructions`,
    `UiTest.chatAccessModesPersistAndSystemPromptLivesOnlyInModelSettings` — после
    повторного открытия настроек теряется последняя запись. Вероятная причина —
    замена файла переименованием в `AtomicFile`, которое на Windows не перезаписывает
    существующий файл. На Android такого нет.
  - `HubDownloadTest.downloadsPersistAcrossStoreRecreationAndOnlyVerifiedGgufCanBeShared` —
    FileProvider не распознаёт путь во временной папке Windows.
- На Oracle JDK падает большинство тестов с Keystore: Oracle JDK не принимает
  неподписанный тестовый криптопровайдер. Используйте OpenJDK.

## Границы проверки

API в тестах подменены транспортом, Keystore — тестовым провайдером. Реальный
телефон, частота кадров, инъекция Accessibility-жестов и запросы с настоящим ключом
не проверялись. Ответ проявляется после обычного ответа API, сетевого SSE нет.
Уже инъецированный жест или операция DocumentsProvider могут завершиться после «Стоп».
