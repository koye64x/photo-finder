# AGENTS.md

Инструкции для AI-агентов (Claude Code, Codex, Cursor и др.), работающих в этом репозитории.

## Проект

**photo-finder** («Фото по лицу») — офлайн-приложение для планшетов и смартфонов на мероприятиях. Гость смотрит во фронтальную камеру, приложение находит снимки с ним среди фото мероприятия на внешнем носителе, гость выбирает фото и печатает их или забирает по QR-коду.

- Продукт и требования: [docs/product-vision.md](docs/product-vision.md)
- Архитектура, стек, конвейеры, хранение: [docs/technical-design.md](docs/technical-design.md)
- Этапы разработки и порядок задач: [docs/roadmap.md](docs/roadmap.md)

Прочитай оба документа перед первой задачей. Если код расходится с документами, сообщи об этом и не меняй документы молча.

## Статус

Каркас Kotlin Multiplatform: модули, convention-плагины, линтеры, CI. Реализованы и покрыты тестами чистые части ядра: косинусный поиск с двумя порогами и расширением запроса, инкрементальная индексация, усреднение кадров, лимит печати, а также ML-конвейер в `:core:ml` (EXIF-поворот, детекция YuNet, выравнивание, эмбеддинги, фильтр качества) с JVM-адаптерами ONNX Runtime и ImageIO. Тест точности написан, но эталонного набора ещё нет. Платформенные адаптеры Android/iOS (CameraX, SAF, SQLCipher, ONNX Runtime, печать, Ktor) пока не написаны: в коде есть только интерфейсы и in-memory/fake-реализации. iOS-приложения (Xcode-проект `iosApp/`) ещё нет, есть только iOS-фреймворк `ComposeApp` и точка входа `MainViewController`.

## Жёсткие правила

Нарушение любого из них — блокер для PR. Часть правил проверяет `scripts/check-forbidden.sh` (запускается в CI).

1. **Никакой сети наружу.** Нет аналитики, краш-репортов, загрузки моделей, обращений к API. Исключение одно: локальный HTTP-сервер Ktor для раздачи фото по QR в локальной сети. Новая зависимость не должна тянуть телеметрию (Firebase, Crashlytics, Play Services и т. п.).
2. **Данные только на внешнем носителе.** Фото, индекс лиц и списки печати хранятся в папке события, выбранной через SAF (`ACTION_OPEN_DOCUMENT_TREE`) / `UIDocumentPicker`. Во внутреннюю память не пишутся ни фото, ни биометрия. Без носителя приложение не работает.
3. **Биометрия.** Индекс лиц — только в SQLCipher с ключом события из Android Keystore / Keychain. Вектор пользователя живёт только в памяти и удаляется после «Готово». «Завершить мероприятие» удаляет индекс и уничтожает ключ. `android:allowBackup="false"`. Векторы, кропы лиц и кадры камеры не пишутся в логи и на диск.
4. **Разрешения.** Не запрашивать `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, геолокацию, контакты. Разрешения камеры и локальной сети добавляются вместе с соответствующими функциями.
5. **Единый ML-рантайм.** Детекция (YuNet) и эмбеддинги (AuraFace или SFace, выбор по тесту точности) работают на одних моделях и одном рантайме (ONNX Runtime) на всех платформах. Не использовать ML Kit и Apple Vision: векторы и пороги должны совпадать на всех платформах и в JVM-тестах.
6. **Логика в `commonMain`.** В `androidMain` / `iosMain` / `jvmMain` только тонкие адаптеры к системным API за интерфейсами из `commonMain`.

## Структура

```
:core:ml           FaceEmbedding, FaceMatcher (поиск, пороги, расширение запроса), FaceAnalyzer, YuNetDetector,
                   ModelFaceEmbedder, выравнивание, EXIF; jvmMain: OrtInferenceSession, ImageIoPhotoDecoder
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
models/            ML-модели (Git LFS), см. models/README.md
testdata/          эталонный набор: клон приватного репозитория, в .gitignore, см. docs/testdata.md
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

Окружение: Windows 11, Eclipse Temurin 21 (см. «JDK» ниже), Gradle 9.9 через wrapper. В Git Bash используй `./gradlew`, в PowerShell — `.\gradlew.bat`.

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

**JDK.** Gradle-демон и тесты запускаются на Eclipse Temurin 21: это задано в `gradle/gradle-daemon-jvm.properties`, Gradle находит JDK сам (в том числе в `~/.jdks`). Причина: ONNX Runtime требует `msvcp140.dll` версии 14.40 и выше, а Oracle JDK 17/21 кладут в `bin` свою версию 14.36, и JVM падает с `EXCEPTION_ACCESS_VIOLATION` в `msvcp140.dll` (тесты `OnnxModelsTest`, `AccuracyTest`). В Temurin 21.0.13 — версия 14.40. Если Gradle пишет, что не нашёл подходящий JDK, установи Temurin 21 (например, zip в `~/.jdks`). На Linux и macOS (CI) проблемы с DLL нет.

**iOS** на Windows не собирается: iOS-таргеты пропускаются. Фреймворк и iOS-тесты собирает CI на macOS. Изменения в `iosMain` держи минимальными и симметричными Android-адаптеру.

## Как проверять изменения

- Перед сдачей работы: `./gradlew build` (или хотя бы `jvmTest ktlintCheck detekt`) и `scripts/check-forbidden.sh`. Не сдавай работу с красными тестами и не отключай тесты, чтобы они стали зелёными.
- Новая логика — с тестами в `commonTest` (`kotlin.test`, `kotlinx-coroutines-test` / `runTest`).
- **Изменения ML-кода** (`:core:ml`, пороги, пре-/постобработка) принимаются только по тесту точности (`AccuracyTest`) на эталонном наборе (`testdata/`, см. `docs/testdata.md`): recall/precision не ниже зафиксированных порогов. В PR укажи значения до и после и версию набора из отчёта.
- Логику сессии тестируй через `ListFrameSource` или видео-источник, а не через реальную камеру.
- UI проверяй запуском `:desktopApp:run`. Скриншот-тестов пока нет.

## Правила работы

- Маленькие изменения: одна задача — один модуль, если возможно. Параллельные агенты работают в отдельных git worktree.
- Версии библиотек — только в `gradle/libs.versions.toml`. Не обновляй версии попутно; обновление — отдельная задача.
- Новые зависимости добавляй, только если это необходимо, и проверь их на правило 1 (нет сети, нет телеметрии).
- Модели (`.onnx`/`.tflite`) и эталонные данные не генерируются кодом и не скачиваются при сборке. Модели лежат в `models/` (Git LFS) с указанием источника и лицензии. Эталонный набор — биометрия реальных людей — лежит только в приватном репозитории `photo-finder-testdata`, локально клонируется в `testdata/` (в `.gitignore`); в основной, публичный репозиторий фото людей не коммитить (см. `docs/testdata.md`).
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
