"""Построение итогового ряда из сырых потоков приёмников.

Правила (из предложения, п. 3.2):
- ячейка = 1 с (сырые данные приходят ~1 Гц, по одному значению на секунду);
- в ячейке берём значение ведущего приёмника; у него пусто -> любого другого;
- ведущий меняется, только если молчит >= 10 с (без дёрганья);
- порядок предпочтения = priority в таблице devices (меньше = важнее).
"""


LEAD_SILENCE_MS = 10_000


def build_merged(raw_rows, priority_map, lead_silence_ms=LEAD_SILENCE_MS, warmup_rows=None):
    """raw_rows: [(device, ts, bpm), ...] отсортировано по ts.

    warmup_rows: записи ДО окна (для восстановления состояния ведущего):
    ряд пересчитывается по окнам, и без прогрева ведущим всегда «становился бы»
    приёмник №1 из списка — эстафета не работала.

    Возвращает [(ts, bpm, source), ...].
    """
    if not raw_rows and not warmup_rows:
        return []

    order = sorted(priority_map, key=lambda d: priority_map[d])

    merged = []
    lead = order[0] if order else None
    lead_last_ts = None

    def feed(rows, emit):
        nonlocal lead, lead_last_ts
        by_ts = {}
        for dev, ts, bpm in rows:
            by_ts.setdefault(ts, {})[dev] = bpm
        for ts in sorted(by_ts):
            cand = by_ts[ts]
            if lead is not None and lead in cand:
                lead_last_ts = ts
            if lead is None or (lead not in cand and lead_last_ts is not None
                               and ts - lead_last_ts >= lead_silence_ms):
                for d in order:
                    if d in cand:
                        lead = d
                        lead_last_ts = ts
                        break
            if emit:
                if lead in cand:
                    src = lead
                else:
                    src = next((d for d in order if d in cand), None)
                    if src is None:
                        src = next(iter(cand))
                merged.append((ts, cand[src], src))

    feed(warmup_rows or [], emit=False)
    feed(raw_rows, emit=True)
    return merged


def gaps(merged, max_gap_ms=60_000):
    """Слепые окна: пропуски между итоговыми точками длиннее max_gap_ms."""
    out = []
    prev = None
    for ts, _, _ in merged:
        if prev is not None and ts - prev > max_gap_ms:
            out.append({"from": prev, "to": ts, "length_s": round((ts - prev) / 1000, 1)})
        prev = ts
    return out
