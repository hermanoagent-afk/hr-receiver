"""Хранилище сандбокса приёмника пульса.

Схема (SQLite):
- devices   — приёмники (id, имя, ключ, приоритет)
- raw_hr    — сырые записи каждого приёмника (idempotent: PK = device+ts)
- alive     — отметки «телефон жив» (heartbeat / любая пачка)
- clock_offsets — оценки расхождения часов телефона и сервера
"""
import sqlite3
import threading
import time

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT,
    api_key TEXT UNIQUE,
    priority INTEGER DEFAULT 100,
    created_at INTEGER
);
CREATE TABLE IF NOT EXISTS raw_hr (
    device TEXT NOT NULL,
    ts INTEGER NOT NULL,
    bpm INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    PRIMARY KEY (device, ts)
);
CREATE TABLE IF NOT EXISTS alive (
    device TEXT NOT NULL,
    ts INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    PRIMARY KEY (device, ts)
);
CREATE TABLE IF NOT EXISTS clock_offsets (
    device TEXT NOT NULL,
    offset_ms INTEGER NOT NULL,
    at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
    device TEXT NOT NULL,
    ts INTEGER NOT NULL,
    type TEXT NOT NULL,
    detail TEXT DEFAULT '',
    received_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_raw_ts ON raw_hr (ts);
CREATE INDEX IF NOT EXISTS idx_alive_ts ON alive (ts);
CREATE INDEX IF NOT EXISTS idx_events_ts ON events (ts);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


class Store:
    def __init__(self, path: str):
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.Lock()
        self.conn.executescript(SCHEMA)
        # миграция: имя оператора (Сева / папа / мама) у каждого приёмника
        try:
            self.conn.execute("ALTER TABLE devices ADD COLUMN operator TEXT DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        self.conn.commit()

    # --- devices ---
    def add_device(self, device_id: str, name: str, api_key: str, priority: int = 100):
        with self.lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO devices (id, name, api_key, priority, created_at) VALUES (?,?,?,?,?)",
                (device_id, name, api_key, priority, now_ms()),
            )
            self.conn.commit()

    def device_by_key(self, api_key: str):
        row = self.conn.execute(
            "SELECT id, name, priority FROM devices WHERE api_key = ?", (api_key,)
        ).fetchone()
        if not row:
            return None
        return {"id": row[0], "name": row[1], "priority": row[2]}

    def set_operator(self, device_id: str, operator: str):
        """Имя того, кто держит телефон (Сева / папа / мама)."""
        with self.lock:
            self.conn.execute("UPDATE devices SET operator = ? WHERE id = ?", (operator, device_id))
            self.conn.commit()

    def operator_map(self):
        return {r[0]: (r[1] or "") for r in self.conn.execute("SELECT id, operator FROM devices")}

    def devices(self):
        rows = self.conn.execute(
            "SELECT id, name, priority, operator FROM devices ORDER BY priority"
        ).fetchall()
        return [{"id": r[0], "name": r[1], "priority": r[2], "operator": r[3] or ""} for r in rows]

    def priority_map(self):
        return {r[0]: r[1] for r in self.conn.execute("SELECT id, priority FROM devices")}

    # --- data ---
    def insert_hr(self, device: str, rows, received_at: int) -> int:
        """rows: [(ts, bpm), ...]. Идемпотентно: повторы не задваивают."""
        with self.lock:
            cur = self.conn.executemany(
                "INSERT OR IGNORE INTO raw_hr (device, ts, bpm, received_at) VALUES (?,?,?,?)",
                [(device, int(ts), int(bpm), received_at) for ts, bpm in rows],
            )
            self.conn.commit()
            return cur.rowcount

    def insert_alive(self, device: str, ts: int, received_at: int):
        with self.lock:
            self.conn.execute(
                "INSERT OR IGNORE INTO alive (device, ts, received_at) VALUES (?,?,?)",
                (device, int(ts), received_at),
            )
            self.conn.commit()

    def log_offset(self, device: str, offset_ms: int, at: int):
        with self.lock:
            self.conn.execute(
                "INSERT INTO clock_offsets (device, offset_ms, at) VALUES (?,?,?)",
                (device, int(offset_ms), at),
            )
            self.conn.commit()

    def insert_events(self, device: str, rows, received_at: int) -> int:
        """rows: [(ts, type, detail), ...]. Причины разрывов — требование Витала."""
        with self.lock:
            cur = self.conn.executemany(
                "INSERT INTO events (device, ts, type, detail, received_at) VALUES (?,?,?,?,?)",
                [(device, int(ts), str(t), str(d or ""), received_at) for ts, t, d in rows],
            )
            self.conn.commit()
            return cur.rowcount

    def events_range(self, from_ts: int, to_ts: int):
        return self.conn.execute(
            "SELECT device, ts, type, detail FROM events WHERE ts >= ? AND ts <= ? ORDER BY ts",
            (from_ts, to_ts),
        ).fetchall()

    def min_offset(self, device: str):
        """Минимальное наблюдаемое расхождение = часы + минимальная задержка сети."""
        row = self.conn.execute(
            "SELECT MIN(offset_ms) FROM clock_offsets WHERE device = ?", (device,)
        ).fetchone()
        return row[0] if row and row[0] is not None else 0

    def raw_range(self, from_ts: int, to_ts: int):
        return self.conn.execute(
            "SELECT device, ts, bpm FROM raw_hr WHERE ts >= ? AND ts <= ? ORDER BY ts",
            (from_ts, to_ts),
        ).fetchall()

    def count_raw(self, device: str = None):
        if device:
            row = self.conn.execute("SELECT COUNT(*) FROM raw_hr WHERE device = ?", (device,)).fetchone()
        else:
            row = self.conn.execute("SELECT COUNT(*) FROM raw_hr").fetchone()
        return row[0]

    def last_alive(self, device: str):
        row = self.conn.execute(
            "SELECT MAX(ts) FROM alive WHERE device = ?", (device,)
        ).fetchone()
        return row[0] if row else None

    def last_hr(self, device: str):
        row = self.conn.execute(
            "SELECT MAX(ts), COUNT(*) FROM raw_hr WHERE device = ?", (device,)
        ).fetchone()
        return row[0], row[1]

    def last_hr_any(self):
        """Последнее значение пульса с любого приёмника."""
        row = self.conn.execute(
            "SELECT ts, bpm, device FROM raw_hr ORDER BY ts DESC LIMIT 1"
        ).fetchone()
        return row if row else None
