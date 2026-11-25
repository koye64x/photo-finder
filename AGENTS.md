# AGENTS.md

Инструкции для AI-агентов (Claude Code, Codex, Cursor и др.), работающих в этом репозитории.

## Проект

**photo-finder** («Фото по лицу») — офлайн-приложение для планшетов и смартфонов на мероприятиях. Гость смотрит во фронтальную камеру, приложение находит снимки с ним среди фото мероприятия на внешнем носителе, гость выбирает фото и печатает их или забирает по QR-коду.

- Продукт и требования: [docs/product-vision.md](docs/product-vision.md)
- Архитектура, стек, конвейеры, хранение: [docs/technical-design.md](docs/technical-design.md)
- Этапы разработки и порядок задач: [docs/roadmap.md](docs/roadmap.md)

Прочитай оба документа перед первой задачей. Если код расходится с документами, сообщи об этом и не меняй документы молча.

## Статус

Каркас Kotlin Multiplatform: модули, convention-плагины, линтеры, CI. Реализованы и покрыты тестами только чистые части ядра: косинусный поиск с двумя порогами и расширением запроса, инкрементальная индексация, усреднение кадров, лимит печати. Платформенные адаптеры (CameraX, SAF, SQLCipher, ONNX Runtime, печать, Ktor) пока не написаны: в коде есть только интерфейсы и in-memory/fake-реализации. iOS-приложения (Xcode-проект `iosApp/`) ещё нет, есть только iOS-фреймворк `ComposeApp` и точка входа `MainViewController`.

## Жёсткие правила

Нарушение любого из них — блокер для PR. Часть правил проверяет `scripts/check-forbidden.sh` (запускается в CI).

1. **Никакой сети наружу.** Нет аналитики, краш-репортов, загрузки моделей, обращений к API. Исключение одно: локальный HTTP-сервер Ktor для раздачи фото по QR в локальной сети. Новая зависимость не должна тянуть телеметрию (Firebase, Crashlytics, Play Services и т. п.).
2. **Данные только на внешнем носителе.** Фото, индекс лиц и списки печати хранятся в папке события, выбранной через SAF (`ACTION_OPEN_DOCUMENT_TREE`) / `UIDocumentPicker`. Во внутреннюю память не пишутся ни фото, ни биометрия. Без носителя приложение не работает.
3. **Биометрия.** Индекс лиц — только в SQLCipher с ключом события из Android Keystore / Keychain. Вектор пользователя живёт только в памяти и удаляется после «Готово». «Завершить мероприятие» удаляет индекс и уничтожает ключ. `android:allowBackup="false"`. Векторы, кропы лиц и кадры камеры не пишутся в логи и на диск.
4. **Разрешения.** Не запрашивать `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, геолокацию, контакты. Разрешения камеры и локальной сети добавляются вместе с соответствующими функциями.
5. **Единый ML-рантайм.** Детекция (YuNet) и эмбеддинги (AuraFace) работают на одних моделях и одном рантайме на всех платформах. Не использовать ML Kit и Apple Vision: векторы и пороги должны совпадать на всех платформах и в JVM-тестах.
6. **Логика в `commonMain`.** В `androidMain` / `iosMain` / `jvmMain` только тонкие адаптеры к системным API за интерфейсами из `commonMain`.

## Структура

```
:core:ml           FaceEmbedding, FaceMatcher (поиск, пороги, расширение запроса), FaceDetector/FaceEmbedder
:core:storage      EventStorage, PhotoId, PhotoRef — папка события на носителе
:core:camera       FrameSource, CameraFrame, ListFrameSource (fake)
:core:db           FaceIndex, IndexedFace, InMemoryFaceIndex (до SQLCipher)
:feature:indexing  IndexingPipeline — конвейер A, порции, инкремент по имени и mtime
:feature:session   EmbeddingAccumulator, FindMyPhotos — конвейер B
:feature:print     PrintSelection — выбор и лимит печати
:feature:share     ShareServer — интерфейс Ktor-сервера для QR
:composeApp        общий Compose UI (App), iOS-фреймворк ComposeApp
:androidApp        Android-приложение (MainActivity); подключается только при наличии Android SDK
:desktopApp        JVM-оболочка для запуска UI на Windows без эмулятора
build-logic/       convention-плагины: вся Gradle-конфигурация здесь
config/detekt/     конфиг detekt
models/, testdata/ ML-модели и эталонный набор (Git LFS), см. README в каждой папке
```

Пакеты: `app.photofinder.<module>` (например, `app.photofinder.core.ml`). Таргеты KMP-модулей: `jvm`, `android` (при наличии SDK), `iosArm64`, `iosSimulatorArm64`.

Зависимости: `core:db → core:ml, core:storage`; `feature:* → core:*`; `composeApp → feature:*`. Feature-модули не зависят друг от друга.

### Новый модуль

1. Создай `<group>/<name>/build.gradle.kts`:
   ```kotlin
   plugins {
       id("photofinder.kmp.library") // для UI-модуля: photofinder.kmp.compose
   }

   kotlin {
       sourceSets.commonMain.dependencies {
           api(projects.core.ml)
       }
   }
   ```
2. Добавь `include(":<group>:<name>")` в `settings.gradle.kts`.
3. Исходники: `src/commonMain/kotlin/app/photofinder/<group>/<name>/`, тесты: `src/commonTest/kotlin/...`.

Не настраивай таргеты, `jvmTarget`, Android namespace или линтеры в модуле: это делают convention-плагины в `build-logic/convention`. Если чего-то не хватает, меняй плагин, а не отдельный модуль.

## Команды

Окружение: Windows 11, JDK 21, Gradle 9.9 через wrapper. В Git Bash используй `./gradlew`, в PowerShell — `.\gradlew.bat`.

```bash
./gradlew jvmTest                       # юнит-тесты всех модулей на JVM: основной цикл проверки
./gradlew :core:ml:jvmTest              # тесты одного модуля
./gradlew ktlintFormat                  # автоформатирование (запускай перед ktlintCheck)
./gradlew ktlintCheck detekt            # линтеры
./gradlew build                         # полная сборка + тесты + линтеры (то же, что в CI)
./gradlew :desktopApp:run               # запустить UI на Windows (открывает окно, блокирует терминал)
bash scripts/check-forbidden.sh         # проверка запрещённых SDK и разрешений
./gradlew connectedAndroidTest          # инструментальные тесты: нужны Android SDK и эмулятор
```

**Android SDK.** Без SDK (нет `ANDROID_HOME` / `sdk.dir` в `local.properties`) сборка выводит предупреждение, отключает Android-таргеты и не подключает `:androidApp`. JVM-часть при этом собирается и тестируется. Android-сборку проверяет CI. Если меняешь Android-код без локального SDK, прямо скажи, что он не проверен.

**iOS** на Windows не собирается: iOS-таргеты пропускаются. Фреймворк и iOS-тесты собирает CI на macOS. Изменения в `iosMain` держи минимальными и симметричными Android-адаптеру.

## Как проверять изменения

- Перед сдачей работы: `./gradlew build` (или хотя бы `jvmTest ktlintCheck detekt`) и `scripts/check-forbidden.sh`. Не сдавай работу с красными тестами и не отключай тесты, чтобы они стали зелёными.
- Новая логика — с тестами в `commonTest` (`kotlin.test`, `kotlinx-coroutines-test` / `runTest`).
- **Изменения ML-кода** (`:core:ml`, пороги, пре-/постобработка) принимаются только по тесту точности на эталонном наборе (`testdata/`): recall/precision не ниже зафиксированных порогов. В PR укажи значения до и после. Тест точности появится вместе с первой реальной моделью.
- Логику сессии тестируй через `ListFrameSource` или видео-источник, а не через реальную камеру.
- UI проверяй запуском `:desktopApp:run`. Скриншот-тестов пока нет.

## Правила работы

- Маленькие изменения: одна задача — один модуль, если возможно. Параллельные агенты работают в отдельных git worktree.
- Версии библиотек — только в `gradle/libs.versions.toml`. Не обновляй версии попутно; обновление — отдельная задача.
- Новые зависимости добавляй, только если это необходимо, и проверь их на правило 1 (нет сети, нет телеметрии).
- Модели (`.onnx`/`.tflite`) и эталонные данные не генерируются кодом и не скачиваются при сборке: они лежат в `models/` и `testdata/` (Git LFS) с указанием источника и лицензии.
- В эталонном наборе — только лица с явным разрешением или синтетические.
- Коммиты: в повелительном наклонении, по-английски, кратко (`Add cosine search with two thresholds`). Не коммить, пока об этом не попросили.

## Стиль кода

- Kotlin, `ktlint_official` (`.editorconfig`, строка до 120 символов) + detekt (`config/detekt/detekt.yml`). Не подавляй правила без причины; если подавляешь, оставь комментарий, почему.
- `@Composable`-функции именуются с заглавной буквы (это разрешено настройками ktlint и detekt).
- Корутины и `Flow` для асинхронности; без блокирующих вызовов на главном потоке.
- Тяжёлые операции (декодирование, инференс) — на `Dispatchers.Default`, порциями, с возможностью отмены (индексация останавливается при старте сессии).
- Публичные интерфейсы модулей — с KDoc. Комментарии в коде и KDoc — по-английски; документация в `docs/` и README — по-русски.

## Известные риски

- По KMP и Compose Multiplatform для iOS мало примеров, поэтому агенты чаще ошибаются в Gradle-конфигурации и `expect/actual`. Перед правкой Gradle посмотри на `build-logic/convention` и существующие модули и повторяй их паттерн.
- AGP 9: KMP-библиотеки используют плагин `com.android.kotlin.multiplatform.library` (блок `kotlin { android { } }`), а не `com.android.library`. Android-приложение — отдельный модуль `:androidApp`, а не `composeApp`.
- `material3` (1.9.0) выпускается отдельно от Compose MP (1.12.2), поэтому при сборке iOS-метаданных бывают предупреждения `KLIB loader: The same 'unique_name=...'`. Они не мешают сборке.
