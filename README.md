# Voice Dataset Collector

Android-приложение для локального сбора голосового датасета.

Приложение работает в фоне, использует локальный Silero VAD для определения речи и автоматически сохраняет речевые сегменты в WAV-файлы.

## Features

- запись с микрофона локально на устройстве;
- 16 kHz;
- mono;
- PCM16;
- Silero VAD через ONNX Runtime;
- автоматическое определение начала речи;
- автоматическое завершение сегмента после периода тишины;
- pre-buffer около 1 секунды;
- foreground service;
- работа при выключенном экране;
- partial wake lock для повышения стабильности фоновой записи;
- локальное хранение WAV-файлов;
- сохранение `metadata.jsonl`;
- без отправки аудио в облако;
- без обязательного интернет-соединения во время работы;
- возможность менять время тишины;
- просмотр и экспорт записей.

## Audio parameters

```text
Sample rate:        16000 Hz
Channels:           1 (mono)
Encoding:           PCM16
Frame size:         512 samples
Frame duration:     ~32 ms
VAD threshold:      0.5
Min speech:         250 ms
Internal silence:   100 ms
Segment silence:    5000 ms
Pre-buffer:         ~1 second
```

## Audio pipeline

```text
Microphone
    ↓
AudioRecord
    ↓
PCM16 / 16 kHz / mono
    ↓
512-sample frames
    ↓
Normalization
short → float32
[-1, 1]
    ↓
Silero VAD
    ↓
P(speech)
    ↓
Threshold
P(speech) >= 0.5
    ↓
Speech detected
    ↓
Start / continue WAV recording
    ↓
5 seconds of silence
    ↓
Close WAV
    ↓
Save metadata
```

## Architecture diagram

![Схема архитектуры Silero VAD](sheme.png)

## Silero VAD pipeline

Упрощённая архитектура используемой модели:

```text
PCM audio frame
    ↓
Context + current frame
    ↓
STFT
    ↓
Conv1D Encoder
    ↓
Conv1D Encoder
    ↓
Conv1D Encoder
    ↓
Conv1D Encoder
    ↓
LSTM
    ↓
Classifier head
    ↓
Sigmoid
    ↓
P(speech)
```

Модель работает потоково. LSTM хранит состояние между соседними аудиофреймами, поэтому решение зависит не только от текущих 32 ms аудио, но и от предыдущего контекста.

## Background recording

Приложение использует Android Foreground Service.

После нажатия `START`:

```text
START
    ↓
Foreground Service
    ↓
AudioRecord
    ↓
Silero VAD
    ↓
WAV segmentation
```

После запуска приложение можно свернуть, а экран телефона можно выключить.

Во время работы Android показывает постоянное уведомление о записи.

## Recording logic

Silero VAD определяет наличие речи для каждого аудиофрейма.

Если:

```text
P(speech) >= threshold
```

сегмент считается речевым.

Если речь обнаружена:

```text
speech
→ start / continue WAV
```

Если после речи в течение 5 секунд новая речь не обнаружена:

```text
silence >= 5 seconds
→ close WAV
```

После этого приложение снова ждёт следующий речевой сегмент.

## Storage

Записи сохраняются локально на телефоне.

Пример структуры:

```text
recordings/
└── 2026-10-01/
    ├── 18-29-33_239.wav
    ├── 18-29-40_469.wav
    ├── 18-29-45_192.wav
    ├── 18-30-20_195.wav
    └── metadata.jsonl
```

Каждый день создаётся отдельная папка.

## Metadata

Пример записи в `metadata.jsonl`:

```json
{
  "file": "18-29-33_239.wav",
  "start": "2026-10-01T18:29:33.239+03:00",
  "duration_ms": 5632,
  "sample_rate": 16000,
  "channels": 1,
  "format": "PCM16_WAV"
}
```

## Export recordings

Датасет можно экспортировать из приложения или скопировать через ADB.

Пример:

```powershell
adb pull /sdcard/Android/data/com.klim.voicedatasetcollector/files/Music/VoiceDatasetCollector/recordings .\recordings
```

## Project structure

```text
app/
└── src/
    └── main/
        ├── java/
        │   ├── com/klim/voicedatasetcollector/
        │   │   ├── MainActivity.kt
        │   │   └── recording/
        │   │       ├── RecordingService.kt
        │   │       ├── AudioConfig.kt
        │   │       ├── PcmRingBuffer.kt
        │   │       ├── RecordingPaths.kt
        │   │       ├── WavFileWriter.kt
        │   │       └── MetadataStore.kt
        │   │
        │   └── cn/enaium/silero/vad/
        │       ├── SileroVad.kt
        │       ├── SileroVadModel.kt
        │       └── config/
        │           └── SampleRate.kt
        │
        ├── assets/
        │   └── silero_vad.onnx
        │
        └── res/
```

## App icon

Иконку приложения можно заменить файлом:

```text
app/src/main/res/drawable/app_icon.png
```

Рекомендуемый размер:

```text
512x512
```

или:

```text
1024x1024
```

Имя файла должно быть:

```text
app_icon.png
```

## Current MVP status

Работает:

- запись с микрофона;
- локальный VAD;
- автоматическая нарезка аудио;
- pre-buffer;
- foreground service;
- работа при выключенном экране;
- сохранение WAV;
- сохранение metadata;
- настройка времени тишины;
- локальный просмотр файлов;
- экспорт записей.

## Planned improvements

Следующие возможные этапы:

- speaker diarization;
- speaker verification;
- автоматическое выделение только собственного голоса;
- FLAC вместо WAV;
- статистика накопленных часов;
- автоматическая синхронизация с ПК;
- ASR;
- построение очищенного voice dataset.
