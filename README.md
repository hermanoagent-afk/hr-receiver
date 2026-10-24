# hr-receiver — свой приёмник пульса WHOOP вместо Pulsoid

Сандбокс на сервере. Основной журнал PulseTrack и сторожи НЕ трогаются.

## Статус (24.10.2026)

- **Сервер сандбокса: готов, работает.** 14/14 сценариев проходят (`server/test_sandbox.py`).
- **Android: скелет написан, НЕ компилирован** (на VPS нет Android SDK; собирается
  GitHub Actions по `.github/workflows/build-apk.yml` или локально: `cd android && gradle assembleDebug`).
- Дальше: Е0 (чеклист Грини, nRF Connect) -> компиляция APK -> Е1.

## Структура

```
server/        сервер приёма (Python, Flask, SQLite) — сандбокс, порт 8091
  app.py         POST /ingest, GET /status /series /journal /gaps /raw/<device>
  store.py       SQLite: devices, raw_hr (идемпотентность PK device+ts), alive, clock_offsets
  merge.py       итоговый ряд: 1с-ячейки, ведущий приёмник, эстафета после 10 с молчания
  simulator.py   симулятор приёмников (пачки, буфер, дубли, уход часов)
  test_sandbox.py прогон 14 сценариев на живом сервере
android/       приложение (Kotlin, minSdk 26)
  BleHrClient    BLE HRS 0x180D/0x2A37, авто-reconnect, сторож 15 с, фильтр скана
  HrsParser      разбор 0x2A37: 8/16 бит, RR-интервалы (1/1024 с)
  LocalBuffer    SQLite-буфер 7 дней, удаление по acked_to
  Sender         пачки 5 с, heartbeat 60 с, retry 5с->5мин, JSON/HTTPS
  HrForegroundService  foreground connectedDevice (НЕ dataSync: в Android 15 лимит 6ч)
  Prefs          EncryptedSharedPreferences (Android Keystore)
  BootReceiver   автозапуск после перезагрузки
.github/workflows/build-apk.yml  сборка APK в CI
```

## Запуск сервера сандбокса

```bash
cd ~/hr-receiver/server && ../venv/bin/python app.py   # 127.0.0.1:8091
../venv/bin/python test_sandbox.py                     # прогон сценариев
```

Устройства сандбокса: phone-1 (sbx-key-1, приоритет 10), phone-2 (sbx-key-2, 20), phone-3 (sbx-key-3, 30).

## Протокол (согласован с Гриней, 24.10)

```json
{"device": "phone-1", "sent_at": 1792855231419,
 "status": {"ble": "connected", "rssi": -64, "queue": 0, "app": "0.1.0"},
 "hr": [[1792855230419, 72], [1792855231419, 73]]}
```

Ответ: `{"ok": true, "acked_to": 1792855231419}` — телефон стирает из буфера всё до acked_to.

## Проверено тестами

1. Авторизация: чужой ключ 401, подмена device 403
2. Идемпотентность: повтор пачки (даже двойной) ничего не задваивает
3. Дыру ведущего заполняет второй приёмник
4. Эстафета: молчание >= 10 с -> ведущим становится следующий (с сохранением
   состояния между окнами пересчёта — найдено и починено тестом 4b)
5. Калибровка часов: уход +5 с обнаружен, метки перенесены на часы сервера
6. Офлайн-буфер: досылка старого с дублями -> ровно по одной записи
7. Heartbeat: «браслет молчит» (alive) отличим от «телефон умер» (!alive)

## Что ещё НЕ сделано

- Е0: живая проверка браслета (nRF Connect) — сколько подключений, RR, адрес, батарея
- компиляция и установка APK на телефон (CI или Android Studio)
- endpoint /register (коды приглашений) — сделает Гриня к Е2 для боевого сервера
- RR-интервалы в пачке — расширим формат после Е0
- служба и UI не тестировались на живом Android
