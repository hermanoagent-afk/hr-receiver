"""Сандбокс-сервер приёма пульса (Flask).

Эндпоинты:
- POST /ingest        — приём пачки от приёмника (авторизация X-Device-Key)
- GET  /status        — состояние: устройства, «жив», последний пульс
- GET  /series        — итоговый ряд (from, to в мс)
- GET  /journal       — итоговый ряд в формате журнала {t, bpm}
- GET  /gaps          — слепые окна > 60 с
- GET  /raw/<device>  — сырые записи приёмника (для сверки)

Особенности (предложение, п. 3.2):
- идемпотентность: ключ (device, ts), повтор пачки ничего не задваивает;
- ответ: {"ok": true, "acked_to": <мс по часам телефона>};
- калибровка часов: offset = server_now - sent_at; при |offset| > 2 с
  метки времени пачки переносятся на часы сервера, offset пишется в лог;
- heartbeat: пачка без hr или со status -> отметка alive.
"""
import time

from flask import Flask, jsonify, request

from merge import build_merged, gaps
from store import Store, now_ms

CLOCK_SKEW_THRESHOLD_MS = 2_000


def create_app(store: Store, seed_devices=None, api_token: str = "") -> Flask:
    app = Flask(__name__)

    if seed_devices:
        for d in seed_devices:
            store.add_device(**d)

    # CORS: дашборд на Vercel и внешние виджеты ходят с других доменов
    @app.after_request
    def cors(resp):
        resp.headers["Access-Control-Allow-Origin"] = "*"
        resp.headers["Access-Control-Allow-Headers"] = "Content-Type, X-Device-Key, X-Api-Token"
        resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        return resp

    @app.route("/ingest", methods=["OPTIONS"])
    @app.route("/events", methods=["OPTIONS"])
    @app.route("/api/<path:_>", methods=["OPTIONS"])
    def cors_preflight(_=None):
        return "", 204

    def auth_device():
        key = request.headers.get("X-Device-Key", "")
        return store.device_by_key(key)

    def auth_api():
        """Публичный API по токену — как access token у Pulsoid."""
        if not api_token:
            return False
        tok = request.headers.get("X-Api-Token", "") or request.args.get("token", "")
        return tok == api_token

    def check_api():
        """Чтение данных закрыто токеном (если токен задан). Без токена (тесты) — открыто."""
        if api_token and not auth_api():
            return jsonify({"error": "bad api token"}), 401
        return None

    @app.post("/ingest")
    def ingest():
        dev = auth_device()
        if not dev:
            return jsonify({"error": "bad device key"}), 401
        data = request.get_json(force=True, silent=True) or {}
        if data.get("device") != dev["id"]:
            return jsonify({"error": "device mismatch"}), 403

        server_now = now_ms()
        sent_at = int(data.get("sent_at", server_now))
        offset = server_now - sent_at  # часы + задержка сети
        correct = abs(offset) > CLOCK_SKEW_THRESHOLD_MS

        hr = [(int(t), int(b)) for t, b in data.get("hr", [])]
        if correct:
            hr = [(t + offset, b) for t, b in hr]

        inserted = store.insert_hr(dev["id"], hr, server_now)

        has_status = isinstance(data.get("status"), dict)
        if has_status:
            # имя того, кто держит телефон (Сева / папа / мама)
            op = str((data.get("status") or {}).get("operator") or "").strip()
            if op:
                store.set_operator(dev["id"], op)
        if has_status or not hr:
            alive_ts = sent_at + (offset if correct else 0)
            store.insert_alive(dev["id"], alive_ts, server_now)

        # события (причины разрывов) — приходят в той же пачке
        ev = [(int(t) + (offset if correct else 0), str(ty), str(det))
              for t, ty, det in data.get("events", [])]
        ev_inserted = store.insert_events(dev["id"], ev, server_now) if ev else 0

        store.log_offset(dev["id"], offset, server_now)

        # acked_to — по часам ТЕЛЕФОНА: телефон помечает отправленным всё до T
        return jsonify({
            "ok": True,
            "acked_to": sent_at,
            "inserted": inserted,
            "events": ev_inserted,
            "clock_offset_ms": offset,
            "corrected": correct,
        })

    @app.post("/events")
    def events_post():
        """Отдельный канал событий: [[ts, type, detail], ...]."""
        dev = auth_device()
        if not dev:
            return jsonify({"error": "bad device key"}), 401
        data = request.get_json(force=True, silent=True) or {}
        if data.get("device") != dev["id"]:
            return jsonify({"error": "device mismatch"}), 403
        server_now = now_ms()
        sent_at = int(data.get("sent_at", server_now))
        offset = server_now - sent_at
        correct = abs(offset) > CLOCK_SKEW_THRESHOLD_MS
        ev = [(int(t) + (offset if correct else 0), str(ty), str(det))
              for t, ty, det in data.get("events", [])]
        n = store.insert_events(dev["id"], ev, server_now)
        store.insert_alive(dev["id"], sent_at + (offset if correct else 0), server_now)
        return jsonify({"ok": True, "acked_to": sent_at, "events": n})

    @app.get("/events")
    def events_get():
        err = check_api()
        if err:
            return err
        from_ts, to_ts = _range()
        rows = store.events_range(from_ts, to_ts)
        return jsonify({"events": [
            {"device": d, "t": t, "type": ty, "detail": det} for d, t, ty, det in rows
        ]})

    @app.get("/status")
    def status():
        err = check_api()
        if err:
            return err
        out = []
        now = now_ms()
        for d in store.devices():
            last_alive = store.last_alive(d["id"])
            last_hr_ts, hr_count = store.last_hr(d["id"])
            out.append({
                "id": d["id"],
                "name": d["name"],
                "priority": d["priority"],
                "operator": d.get("operator", ""),
                "alive": last_alive is not None and now - last_alive < 5 * 60_000,
                "last_alive_ms": last_alive,
                "last_hr_ms": last_hr_ts,
                "hr_count": hr_count,
                "clock_offset_ms": store.min_offset(d["id"]),
            })
        return jsonify({"server_time": now, "devices": out})

    def _range():
        to_ts = int(request.args.get("to", now_ms()))
        from_ts = int(request.args.get("from", 0))
        return from_ts, to_ts

    def _merged(from_ts, to_ts):
        # прогрев 30 с до окна: восстанавливает состояние ведущего (эстафета)
        warm = store.raw_range(from_ts - 30_000, from_ts - 1) if from_ts > 0 else []
        raw = store.raw_range(from_ts, to_ts)
        return build_merged(raw, store.priority_map(), warmup_rows=warm)

    @app.get("/series")
    def series():
        err = check_api()
        if err:
            return err
        from_ts, to_ts = _range()
        merged = _merged(from_ts, to_ts)
        ops = store.operator_map()
        return jsonify({"from": from_ts, "to": to_ts,
                        "rows": [{"t": t, "bpm": b, "source": s,
                                  "source_name": ops.get(s, "")} for t, b, s in merged]})

    @app.get("/journal")
    def journal():
        """Формат текущего журнала сборщика: {t: <мс>, bpm} — по одной записи на строку."""
        from_ts, to_ts = _range()
        merged = _merged(from_ts, to_ts)
        lines = ['{"t": %d, "bpm": %d}' % (t, b) for t, b, _ in merged]
        return "\n".join(lines) + "\n", 200, {"Content-Type": "text/plain; charset=utf-8"}

    @app.get("/gaps")
    def gaps_api():
        """Дыры с объяснениями из событий — требование Витала (>=90% дыр объяснены)."""
        err = check_api()
        if err:
            return err
        from_ts, to_ts = _range()
        merged = _merged(from_ts, to_ts)
        out = []
        for g in gaps(merged):
            ev = store.events_range(g["from"] - 60_000, g["to"])
            g["explained_by"] = [
                {"device": d, "t": t, "type": ty, "detail": det} for d, t, ty, det in ev
            ]
            g["explained"] = bool(ev)
            out.append(g)
        explained_pct = round(100 * sum(1 for g in out if g["explained"]) / len(out), 1) if out else 100.0
        return jsonify({"gaps": out, "explained_pct": explained_pct})

    @app.get("/raw/<device>")
    def raw(device):
        from_ts, to_ts = _range()
        rows = store.raw_range(from_ts, to_ts)
        return jsonify({"rows": [{"t": t, "bpm": b} for d, t, b in rows if d == device]})

    # === Публичный API по токену (аналог access token у Pulsoid) ===

    @app.get("/api/live")
    def api_live():
        """Живой пульс — формат близок к Pulsoid: measured_at + data.heart_rate."""
        if not auth_api():
            return jsonify({"error": "bad api token"}), 401
        row = store.last_hr_any()
        if not row:
            return jsonify({"measured_at": None, "data": {"heart_rate": None}})
        ts, bpm, dev = row
        return jsonify({
            "measured_at": ts,
            "data": {"heart_rate": bpm},
            "source": dev,
            "source_name": store.operator_map().get(dev, ""),
        })

    @app.get("/api/hr")
    def api_hr():
        """История пульса: ?from=&to= (мс)."""
        if not auth_api():
            return jsonify({"error": "bad api token"}), 401
        from_ts, to_ts = _range()
        merged = _merged(from_ts, to_ts)
        ops = store.operator_map()
        return jsonify({
            "from": from_ts, "to": to_ts,
            "rows": [{"t": t, "bpm": b, "source_name": ops.get(s, "")} for t, b, s in merged],
        })

    return app


def load_or_create_token() -> str:
    """Один постоянный API-токен — хранится рядом с БД."""
    import os
    import uuid
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "api_token.txt")
    if os.path.exists(p):
        return open(p).read().strip()
    tok = "hrt_" + uuid.uuid4().hex
    with open(p, "w") as f:
        f.write(tok)
    return tok


def main():
    store = Store("sandbox.db")
    token = load_or_create_token()
    app = create_app(store, seed_devices=[
        {"device_id": "phone-1", "name": "телефон рядом", "api_key": "sbx-key-1", "priority": 10},
        {"device_id": "phone-2", "name": "телефон запасной", "api_key": "sbx-key-2", "priority": 20},
        {"device_id": "phone-3", "name": "pi-кухня", "api_key": "sbx-key-3", "priority": 30},
    ], api_token=token)
    app.run(host="127.0.0.1", port=8091)


if __name__ == "__main__":
    main()
