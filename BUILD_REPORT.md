# Luma Agent 0.5.2 · отчёт о сборке

Package `dev.luma.agent`, versionCode 11, versionName 0.5.2, minSdk 26, target/compileSdk 35.
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
- Юнит-тесты: 108, из них 104 проходят. В том числе 6 новых тестов 0.5.2: запрещённые
  слова, локальные адреса, блокировка HTTP к внешним хостам, тексты лимитов,
  картинки ссылками, ограничение истории.
- 4 теста не проходят на Windows-хосте, и все они касаются кода, который в 0.5.2
  не менялся:
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
