# Luma Agent 0.8.0 · отчёт о сборке

Package `dev.luma.agent`, versionCode 15, versionName 0.8.0, minSdk 26, target/compileSdk 35.
Изменения версии — в [CHANGELOG.md](CHANGELOG.md).

## Итог

**APK 0.8.0 не собран, Android Lint не запускался.** В облачном окружении, где готовилась
версия, сетевая политика запрещала `dl.google.com`, а значит и репозиторий Google Maven: плагин
Android для Gradle (AGP 8.9.2), AndroidX и Compose оттуда не скачать. Без них
`./gradlew :app:assembleRelease` и `:app:lintDebug` не выполняются. Сборку нужно повторить
командой ниже там, где Google Maven доступен (локально или в GitHub Actions — workflow уже
запускает тесты, Lint и debug-сборку).

```
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
LUMA_SIGN_PASS=... python3 tools/package.py --sdk /path/to/sdk --keystore /outside/project/luma.p12
```

## Что проверено

Отдельная проверочная сборка (вне репозитория): Kotlin 2.1.20 (JVM) + `android.jar` платформы 35
(platform-35 r02 и build-tools 35.0.0 из образа CircleCI `cimg/android`, потому что
`dl.google.com` был недоступен) + OkHttp, coroutines, org.json и Robolectric 4.14.1 из Maven
Central. Две маленькие тестовые зависимости Robolectric с Google Maven (`androidx.test:monitor`,
`espresso-idling-resource`) и `androidx.documentfile` заменены в ней точными по сигнатурам
заглушками; в приложение они не попадают.

- Скомпилирован весь код без Compose: `core/*`, служба Accessibility, панель, оверлей,
  сервис задачи, плитка — без ошибок.
- **66 юнит-тестов, все проходят** (Robolectric, Android 15 и Android 9):
  33 прежних (`CoreTest`, `AgentContextTest`, `ProviderRecoveryTest`, `VaultAgentTest`) и 33 новых:
  - `DirectActionsTest` (14): текстовая локальная модель (vision=false) открывает шторку и делает
    свайп от края без снимка; переход локального сервера без поддержки инструментов на
    JSON-команды в той же задаче; громкость одним вызовом без чтения экрана; Wi‑Fi, Bluetooth,
    геолокация, авиарежим, точка доступа, яркость без разрешения возвращают панель или настройки,
    а не «выполнено»; «Не беспокоить» только с доступом пользователя; прямое переключение Wi‑Fi на
    Android 9; медиа и буфер; будильник, таймер, событие и напоминания с временем, названием,
    часовым поясом и повтором; часовые пояса будильника и переход на летнее время; подтверждения,
    «Стоп» во время ожидания, запрет молчаливого повтора действия с неизвестным результатом,
    проверка аргументов до выполнения; возможности устройства и компактный каталог в промпте.
  - `WorkspaceFilesTest` (7): TXT создаётся сразу в workspace через протокол документов (запросы и
    вызовы `android:createDocument` и т. п.), без `list_files`; защита от перезаписи и выхода из
    папки; описание папки без выдуманного пути; отсутствие папки и потерянный доступ завершают
    задачу сразу, без обхода через файловый менеджер.
  - `NativeToolsTest` (8): вызов функций для OpenAI-совместимых API, Claude и Gemini (подпись
    мысли возвращается без изменений), сборка потоковых вызовов, реестр и геометрия жестов.
  - `OutputLimitsTest` (4): «Авто» не отправляет лимит (кроме обязательного у Claude), ручной лимит
    и его ограничение моделью, реальные пределы каталогов, миграция старого 8192.
- Обновлён `SettingsEffectsTest`: ручной лимит 100 000 больше не обрезается до 8192.

## Что не проверено

- Сборка APK, R8, подпись и Android Lint (см. выше).
- Тесты, которым нужны Compose или AndroidX: `UiTest`, `SettingsEffectsTest`, `FeaturesTest`,
  `MarkdownTest`, `OverlayTest`, `OrbPhysicsTest`, `StreamingTest`, `AttachmentTest`, `HubTest`,
  `HubDownloadTest`. Изменённые экраны «Доступ» и «Настройки» не компилировались; их код сверен
  вручную с API Material 3, которые уже используются в этих файлах.
- Реальный телефон. Все системные вызовы проверены только в Robolectric: системные действия
  Accessibility, инъекция жестов, фонарик (в Robolectric нет вспышки), панели Android, приложения
  «Часы» и «Календарь», точные будильники, буфер обмена в фоне, разные DocumentsProvider
  (Google Диск, SD-карта) и запросы к настоящим провайдерам с ключом.
- Сервер модели в тестах заменён транспортом; Keystore — тестовым провайдером.

## Изменения в тестовой конфигурации

`app/build.gradle.kts`: для юнит-тестов добавлены `--add-opens=java.base/java.io=ALL-UNNAMED`
(Robolectric `ParcelFileDescriptor` на JDK 17+) и `LC_ALL=C.UTF-8` (кириллические имена файлов в
тестах рабочей папки на Linux).
