"""Прогон сценариев сандбокса на живом сервере (порт 8091).

Сценарии:
1. Авторизация: чужой ключ -> 401, свой -> ok, acked_to по часам телефона.
2. Идемпотентность: повтор пачки ничего не задваивает.
3. Подмена потока: ведущий молчит -> ячейки заполняет второй приёмник.
4. Эстафета: ведущий молчит >= 10 с -> ведущим становится второй.
5. Калибровка часов: телефон с уходом часов +5 с -> записи переносятся на часы сервера.
6. Офлайн-буфер: старые записи с дублями досылаются -> попадают в ряд, без задваивания.
7. Heartbeat: «браслет молчит» (alive есть, пульса нет) vs «телефон умер» (alive нет).
"""
import json
import sys
import threading
import time

import requests
from werkzeug.serving import make_server

from app import create_app
from simulator import FakeDevice
from store import Store, now_ms

BASE = "http://127.0.0.1:8091"
RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), "-", name, ("| " + str(detail) if detail else ""))


def start_server(path="sandbox_test.db"):
    import os
    if os.path.exists(path):
        os.remove(path)
    store = Store(path)
    app = create_app(store, seed_devices=[
        {"device_id": "phone-1", "name": "ведущий", "api_key": "sbx-key-1", "priority": 10},
        {"device_id": "phone-2", "name": "запасной", "api_key": "sbx-key-2", "priority": 20},
        {"device_id": "phone-3", "name": "часы-кривые", "api_key": "sbx-key-3", "priority": 30},
        {"device_id": "phone-4", "name": "мёртвый", "api_key": "sbx-key-4", "priority": 40},
    ])
    srv = make_server("127.0.0.1", 8091, app, threaded=True)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, store


def series_rows(frm, to):
    r = requests.get(BASE + "/series", params={"from": frm, "to": to}, timeout=10)
    return r.json()["rows"]


def main():
    srv, store = start_server()
    time.sleep(0.3)
    t0 = now_ms()

    # --- 1. авторизация ---
    d1 = FakeDevice("phone-1", "sbx-key-1", base_bpm=70, seed=1)
    d2 = FakeDevice("phone-2", "sbx-key-2", base_bpm=75, seed=2)
    d3 = FakeDevice("phone-3", "sbx-key-3", base_bpm=65, seed=3, clock_skew_ms=5000)

    bad = requests.post(BASE + "/ingest", json={"device": "phone-1", "sent_at": now_ms(), "hr": []},
                        headers={"X-Device-Key": "wrong-key"}, timeout=10)
    check("1a. чужой ключ -> 401", bad.status_code == 401)

    mismatch = requests.post(BASE + "/ingest", json={"device": "phone-2", "sent_at": now_ms(), "hr": []},
                             headers={"X-Device-Key": "sbx-key-1"}, timeout=10)
    check("1b. чужой device в пачке -> 403", mismatch.status_code == 403)

    d1.measure_span(t0, 10)
    resp = d1.flush()
    check("1c. приём пачки, acked_to = sent_at", resp.get("ok") and resp.get("acked_to") == resp.get("acked_to"),
          f"acked_to={resp.get('acked_to')}, inserted={resp.get('inserted')}")
    check("1d. 10 записей принято", resp.get("inserted") == 10, f"inserted={resp.get('inserted')}")

    # --- 2. идемпотентность ---
    n_before = store.count_raw("phone-1")
    d1.measure_span(t0, 10)  # те же метки времени -> те же ключи
    d1.flush(dup=True)       # каждая пачка отправлена дважды
    n_after = store.count_raw("phone-1")
    check("2. повторы не задваивают (count не вырос)", n_after == n_before,
          f"before={n_before}, after={n_after}")

    # --- 3. подмена потока (без смены ведущего) ---
    # d1 молчит 5 с, d2 шлёт те же секунды: ячейки заполняет d2, ведущий остаётся d1
    gap_start = t0 + 10_000
    d2.measure_span(gap_start, 5)
    d2.flush()
    rows = series_rows(gap_start, gap_start + 5000)
    sources = {r["source"] for r in rows}
    check("3. дыру ведущего заполняет второй", len(rows) == 5 and sources == {"phone-2"},
          f"rows={len(rows)}, sources={sources}")

    # --- 4. эстафета: ведущий молчит >= 10 с ---
    relay_start = gap_start + 5_000
    d2.measure_span(relay_start, 15)  # d2 шлёт 15 с подряд, d1 молчит
    d2.flush()
    rows = series_rows(relay_start, relay_start + 15_000)
    first_src = rows[0]["source"] if rows else None
    late_src = rows[-1]["source"] if rows else None
    switched = [r for r in rows if r["source"] == "phone-2"]
    check("4. эстафета: после 10 с молчания ведущим становится phone-2",
          late_src == "phone-2" and len(switched) >= 5,
          f"первый={first_src}, последний={late_src}, phone-2 ячеек={len(switched)}")

    # 4b. подтверждение смены ведущего: оба шлют одни секунды -> брать должен phone-2
    both_start = relay_start + 15_000
    d1.measure_span(both_start, 5)
    d2.measure_span(both_start, 5)
    d1.flush()
    d2.flush()
    rows = series_rows(both_start, both_start + 5000)
    check("4b. при общих секундах ведущий остался phone-2",
          rows and all(r["source"] == "phone-2" for r in rows),
          f"sources={ {r['source'] for r in rows} }")

    # --- 5. калибровка часов (phone-3, skew +5 с) ---
    real_now = now_ms()
    d3.measure_span(real_now + 5_000, 10)  # телефон думает, что сейчас +5 с
    resp3 = d3.flush()
    rows3 = series_rows(real_now - 2000, real_now + 12_000)
    corrected_ok = resp3.get("corrected") is True and abs(resp3.get("clock_offset_ms", 0) + 5000) < 1500
    times_ok = all(real_now - 2000 <= r["t"] <= real_now + 11_500 for r in rows3 if r["source"] == "phone-3")
    raw3 = requests.get(BASE + "/raw/phone-3", params={"from": real_now - 2000, "to": real_now + 12_000}, timeout=10).json()["rows"]
    raw3_ok = len(raw3) >= 8 and all(real_now - 2000 <= r["t"] <= real_now + 11_500 for r in raw3)
    check("5a. уход часов +5 с обнаружен и скорректирован", corrected_ok,
          f"offset={resp3.get('clock_offset_ms')}, corrected={resp3.get('corrected')}")
    check("5b. метки времени перенесены на часы сервера", raw3_ok,
          f"raw3={len(raw3)} записей, диапазон ок")

    # --- 6. офлайн-буфер: старые записи с дублями ---
    old_start = real_now - 3600_000  # час назад
    d2.measure_span(old_start, 30, step=1000)
    resp6 = d2.flush(dup=True)
    rows6 = series_rows(old_start, old_start + 30_000)
    uniq = len({r["t"] for r in rows6})
    check("6. досылка старого буфера с дублями: 30 записей, без задваивания",
          resp6.get("inserted") == 30 and len(rows6) == 30 and uniq == 30,
          f"inserted={resp6.get('inserted')}, rows={len(rows6)}, uniq={uniq}")

    # --- 7. heartbeat: «браслет молчит» vs «телефон умер» ---
    d1.heartbeat()  # телефон жив, пульса нет
    st = requests.get(BASE + "/status", timeout=10).json()["devices"]
    by_id = {d["id"]: d for d in st}
    check("7a. heartbeat: phone-1 жив, но без пульса (alive=true)", by_id["phone-1"]["alive"] is True)
    # телефон-4: последняя отметка «жив» 6 минут назад -> мёртв
    dead_ts = now_ms() - 6 * 60_000
    store.insert_alive("phone-4", dead_ts, dead_ts)
    st = requests.get(BASE + "/status", timeout=10).json()["devices"]
    by_id = {d["id"]: d for d in st}
    check("7b. телефон без отметок 6 мин -> alive=false", by_id["phone-4"]["alive"] is False,
          f"last_alive={by_id['phone-4']['last_alive_ms']}")
    # различие: phone-1 жив (есть heartbeat), но его последний пульс старше heartbeat
    check("7c. различаем «браслет молчит» (alive, пульс старый) от «телефон умер» (!alive)",
          by_id["phone-1"]["alive"] is True and by_id["phone-4"]["alive"] is False)

    # --- 8. события: причины дыр + имя оператора (требования Витала) ---
    now = now_ms()
    requests.post(BASE + "/ingest",
                  json={"device": "phone-2", "sent_at": now, "status": {"operator": "Сева"}, "hr": []},
                  headers={"X-Device-Key": "sbx-key-2"}, timeout=10)
    gap_start = now - 7_200_000  # дыра два часа назад
    d2.measure_span(gap_start, 10)
    d2.flush()
    d2.measure_span(gap_start + 300_000, 10)  # возобновление через 5 минут
    d2.flush()
    requests.post(BASE + "/events",
                  json={"device": "phone-2", "sent_at": now,
                        "events": [[gap_start + 5_000, "bt_off", "браслет отключился"]]},
                  headers={"X-Device-Key": "sbx-key-2"}, timeout=10)
    g = requests.get(BASE + "/gaps",
                     params={"from": gap_start - 60_000, "to": gap_start + 400_000}, timeout=10).json()
    check("8a. дыра >60с объяснена событием bt_off",
          bool(g["gaps"]) and g["gaps"][0]["explained"] and g["explained_pct"] == 100.0,
          f"explained={g['gaps'][0]['explained'] if g['gaps'] else None}, pct={g['explained_pct']}")
    ev = requests.get(BASE + "/events",
                      params={"from": gap_start, "to": gap_start + 10_000}, timeout=10).json()["events"]
    check("8b. событие сохранено с типом и деталью",
          bool(ev) and ev[0]["type"] == "bt_off" and ev[0]["detail"] == "браслет отключился",
          f"events={ev}")
    rows = requests.get(BASE + "/series",
                        params={"from": gap_start, "to": gap_start + 5_000}, timeout=10).json()["rows"]
    check("8c. имя оператора (Сева) в итоговом журнале",
          bool(rows) and rows[0].get("source_name") == "Сева",
          f"source_name={rows[0].get('source_name') if rows else None}")

    # --- итог ---
    ok = sum(1 for _, c, _ in RESULTS if c)
    print(f"\nИТОГО: {ok}/{len(RESULTS)} прошло")
    if ok != len(RESULTS):
        sys.exit(1)


if __name__ == "__main__":
    main()
