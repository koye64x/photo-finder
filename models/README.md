# models

ML-модели, общие для всех платформ (см. `docs/technical-design.md`). Рантайм — ONNX Runtime.

| Файл | Назначение | Источник (ревизия) | Лицензия | Размер | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| `yunet_2023mar.onnx` | Детекция лиц + 5 ключевых точек | [opencv/face_detection_yunet](https://huggingface.co/opencv/face_detection_yunet) `face_detection_yunet_2023mar.onnx` (`3cc26e7f`) | MIT, [licenses/yunet-MIT.txt](licenses/yunet-MIT.txt) | 0,2 МБ | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` |
| `sface_2021dec.onnx` | Эмбеддинги лиц, 128-d (кандидат) | [opencv/face_recognition_sface](https://huggingface.co/opencv/face_recognition_sface) `face_recognition_sface_2021dec.onnx` (`3d708243`) | Apache 2.0, [licenses/sface-Apache-2.0.txt](licenses/sface-Apache-2.0.txt) | 37 МБ | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` |
| `auraface_v1_glintr100.onnx` | Эмбеддинги лиц, 512-d (кандидат) | [fal/AuraFace-v1](https://huggingface.co/fal/AuraFace-v1) `glintr100.onnx` (`af6d057c`) | Apache 2.0, [licenses/auraface-Apache-2.0.md](licenses/auraface-Apache-2.0.md) | 249 МБ | `a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60` |

**AuraFace не хранится в репозитории** (249 МБ, квота Git LFS): файл в `.gitignore`, держится локально до выбора модели. Тесты с AuraFace (`OnnxModelsTest`, `AccuracyTest`) без файла пропускаются, в том числе в CI. Чтобы сравнить модели локально, скачайте файл вручную и сверьте контрольную сумму:

```bash
curl -L -o models/auraface_v1_glintr100.onnx \
  https://huggingface.co/fal/AuraFace-v1/resolve/af6d057c9b0ec4071d4c49c80e3539258798b609/glintr100.onnx
sha256sum models/auraface_v1_glintr100.onnx   # a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60
```

Если по тесту точности выбрана AuraFace, она добавляется в LFS отдельным PR.

## Контракты моделей

Пре- и постобработка — в `:core:ml` (commonMain), одинаково на всех платформах.

- **YuNet**: вход BGR 0..255, NCHW, квадрат 640×640 (картинка вписывается в левый верхний угол, остальное — чёрное). Выходы `cls_*`, `obj_*`, `bbox_*`, `kps_*` для шагов 8/16/32, декодирование как в OpenCV `FaceDetectorYN`. Код: `YuNetDetector`.
- **SFace / AuraFace**: вход — лицо 112×112, выровненное по 5 точкам к шаблону ArcFace, RGB, NCHW. SFace: значения 0..255 без нормализации. AuraFace: `(x − 127.5) / 127.5`. Код: `FaceAlignment.kt`, `ModelFaceEmbedder`, `EmbedderSpec`.

## Выбор модели эмбеддингов

Обе модели подключены, выбор — по тесту точности на `testdata/` (см. `docs/roadmap.md`, этап 1). Что известно заранее:

- AuraFace — ResNet100, ~24 GFLOPs на лицо, 249 МБ. Обучена на данных, допускающих коммерческое использование. Ожидается высокая точность и медленная индексация на телефонах.
- SFace — MobileFaceNet, в разы быстрее и меньше (есть int8-версия на 10 МБ). Лицензия модели Apache 2.0, но обучающий набор не указан явно: перед выпуском проверить происхождение данных.
- YuNet обучен на WIDER FACE; лицензия модели MIT.

Невыбранная модель удаляется из репозитория отдельным PR вместе с фиксацией порогов.

## Правила

- Модели лежат в репозитории (Git LFS) и не скачиваются при сборке или в рантайме: приложение работает без сети.
- Для каждой модели указать источник, ревизию, лицензию и SHA-256; текст лицензии — в `licenses/`.
- Замена модели меняет векторы и пороги, поэтому это отдельный PR с результатами теста точности до и после.
- Проверка контрольных сумм: `sha256sum models/*.onnx`.
- Модели в репозитории (LFS): `yunet_2023mar.onnx`, `sface_2021dec.onnx`.
