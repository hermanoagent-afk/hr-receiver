"""Симулятор приёмников для прогонки сценариев сандбокса.

Имитирует поведение Android-приложения: пачки каждые 5 с, буфер офлайн,
повторная отправка с дублями, heartbeat, расхождение часов.
"""
import random
import time

import requests

BASE = "http://127.0.0.1:8091"


class FakeDevice:
    def __init__(self, device_id, key, clock_skew_ms=0, base_bpm=70, seed=1):
        self.device_id = device_id
        self.key = key
        self.skew = clock_skew_ms
        self.base_bpm = base_bpm
        self.rng = random.Random(seed)
        self.queue = []  # [(phone_ts, bpm), ...] — «буфер на телефоне»

    def phone_now(self):
        return int(time.time() * 1000) + self.skew

    def measure(self, at_phone_ts, bpm=None):
        if bpm is None:
            bpm = self.base_bpm + self.rng.randint(-3, 3)
        self.queue.append((at_phone_ts, bpm))

    def measure_span(self, start_phone_ts, seconds, step=1000, bpm=None):
        for i in range(0, seconds * 1000, step):
            self.measure(start_phone_ts + i, bpm)

    def flush(self, dup=False, keepalive=True):
        """Отправить буфер пачкой. dup=True — отправить всё дважды (проверка идемпотентности)."""
        sent_at = self.phone_now()
        batch = [[ts, bpm] for ts, bpm in self.queue]
        payload = {
            "device": self.device_id,
            "sent_at": sent_at,
            "status": {"ble": "connected", "rssi": -60 + self.rng.randint(-10, 10),
                       "phone_batt": 80, "queue": len(batch), "app": "0.1.0-sbx"},
            "hr": batch,
        }
        headers = {"X-Device-Key": self.key}
        r = requests.post(BASE + "/ingest", json=payload, headers=headers, timeout=10)
        if dup:
            requests.post(BASE + "/ingest", json=payload, headers=headers, timeout=10)
        self.queue = []
        return r.json()

    def heartbeat(self):
        """Жив без пульса: браслет молчит, телефон работает."""
        payload = {
            "device": self.device_id,
            "sent_at": self.phone_now(),
            "status": {"ble": "connected", "queue": 0, "app": "0.1.0-sbx"},
            "hr": [],
        }
        r = requests.post(BASE + "/ingest", json=payload, headers={"X-Device-Key": self.key}, timeout=10)
        return r.json()
