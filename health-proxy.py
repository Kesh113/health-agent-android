#!/usr/bin/env python3
"""
Health proxy: receives raw JSON from Android Health Agent app,
formats it as a ClaudeClaw prompt, signs with HMAC, forwards to webhook.
Run: python3 health-proxy.py
"""

import json
import hashlib
import hmac as hmac_lib
import http.server
import urllib.request
import logging
import os
from datetime import datetime, timezone

WEBHOOK_SECRET = os.environ.get("WEBHOOK_SECRET", "")
CLAUDECLAW_URL = "http://localhost:3100/webhook/telegram_health"
LISTEN_PORT = int(os.environ.get("HEALTH_PROXY_PORT", "3200"))


def format_prompt(data: dict) -> str:
    now = datetime.now(timezone.utc).strftime("%H:%M")
    lines = [f"[HEALTH DATA {now}] Данные Samsung Watch:"]

    # Steps
    steps = data.get("steps", [])
    if steps:
        total = sum(s.get("count", 0) for s in steps)
        line = f"• Шаги: {total:,}"
        lines.append(line)

    # Heart rate
    hr = data.get("heart_rate", [])
    if hr:
        bpms = [r.get("bpm", 0) for r in hr if r.get("bpm")]
        if bpms:
            lines.append(f"• Пульс: avg {sum(bpms)//len(bpms)} bpm (min {min(bpms)}, max {max(bpms)})")

    # Blood pressure
    bp = data.get("blood_pressure", [])
    if bp:
        last = bp[-1]
        sys_ = last.get("systolic", "?")
        dia  = last.get("diastolic", "?")
        lines.append(f"• Давление: {sys_}/{dia} мм рт. ст.")

    # SpO2
    spo2 = data.get("oxygen_saturation", [])
    if spo2:
        pcts = [r.get("percentage", 0) for r in spo2 if r.get("percentage")]
        if pcts:
            lines.append(f"• SpO2: avg {sum(pcts)/len(pcts):.1f}% (min {min(pcts):.1f}%)")

    # Sleep
    sleep = data.get("sleep", [])
    if sleep:
        for s in sleep:
            dur = s.get("duration_seconds", 0)
            h, m = divmod(dur // 60, 60)
            score = s.get("score")
            line = f"• Сон: {h}ч {m}м"
            if score: line += f" (оценка {score})"
            lines.append(line)

    # Floors climbed (key: floors_climbed)
    floors = data.get("floors_climbed", [])
    if floors:
        total_fl = sum(f.get("count", 0) for f in floors)
        if total_fl:
            lines.append(f"• Этажей: {total_fl:.0f}")

    # Water intake (key: water_intake)
    water = data.get("water_intake", [])
    if water:
        total_ml = sum(w.get("ml", 0) for w in water)
        if total_ml:
            lines.append(f"• Вода: {total_ml:.0f} мл")

    # Nutrition
    nutrition = data.get("nutrition", [])
    if nutrition:
        total_cal = sum(n.get("calories", 0) for n in nutrition)
        total_prot = sum(n.get("protein_g", 0) for n in nutrition)
        total_fat = sum(n.get("fat_g", 0) for n in nutrition)
        total_carb = sum(n.get("carbs_g", 0) for n in nutrition)
        line = f"• Питание: {total_cal:.0f} ккал"
        extras = []
        if total_prot: extras.append(f"Б {total_prot:.0f}г")
        if total_fat:  extras.append(f"Ж {total_fat:.0f}г")
        if total_carb: extras.append(f"У {total_carb:.0f}г")
        if extras: line += f" ({', '.join(extras)})"
        lines.append(line)

    # Blood glucose (key: blood_glucose)
    glucose = data.get("blood_glucose", [])
    if glucose:
        vals = [g.get("mmol_l", 0) for g in glucose if g.get("mmol_l")]
        if vals:
            lines.append(f"• Глюкоза: {vals[-1]:.1f} ммоль/л")

    # Body composition (key: body_composition — was "weight")
    bc = data.get("body_composition", [])
    if bc:
        last = bc[-1]
        kg  = last.get("weight_kg")
        bmi = last.get("bmi")
        bf  = last.get("body_fat")
        if kg:
            line = f"• Вес: {kg:.1f} кг"
            if bmi: line += f" (ИМТ {bmi:.1f})"
            if bf:  line += f", жир {bf:.1f}%"
            lines.append(line)

    # Skin temperature (key: skin_temperature — replaces body_temperature)
    temp = data.get("skin_temperature", [])
    if temp:
        t = temp[-1].get("celsius")
        if t:
            lines.append(f"• Темп. кожи: {t:.1f}°C")

    # Exercise sessions
    exercises = data.get("exercise", [])
    if exercises:
        lines.append(f"• Тренировки ({len(exercises)}):")
        for ex in exercises[:5]:
            etype = ex.get("type", "Активность")
            dur   = ex.get("duration_minutes", 0)
            ecal  = ex.get("calories", 0)
            edist = ex.get("distance_m", 0)
            line  = f"  - {etype}: {dur:.0f} мин"
            if ecal:  line += f", {ecal:.0f} ккал"
            if edist: line += f", {edist/1000:.2f} км"
            lines.append(line)

    lines.append(
        "\nПроанализируй данные, сохрани в журнал здоровья "
        "(memory/topics/health-journal.md). Если показатели не в норме — "
        "дай рекомендации. Задай вопрос о питании и самочувствии."
    )
    return "\n".join(lines)


class HealthProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        """Health-check endpoint — used by the Android app's Settings → Test Connection."""
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length)
            data = json.loads(raw)
        except Exception as e:
            logging.error(f"Bad request: {e}")
            self.send_response(400)
            self.end_headers()
            return

        prompt = format_prompt(data)
        payload = json.dumps({"prompt": prompt}).encode("utf-8")

        sig = hmac_lib.new(
            WEBHOOK_SECRET.encode("utf-8"),
            payload,
            hashlib.sha256
        ).hexdigest()

        req = urllib.request.Request(
            CLAUDECLAW_URL,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "X-Signature": sig,
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                logging.info(f"Forwarded to ClaudeClaw: {resp.status}")
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"ok":true}')
        except Exception as e:
            logging.error(f"ClaudeClaw forward error: {e}")
            self.send_response(502)
            self.end_headers()

    def log_message(self, fmt, *args):
        logging.info(f"HC Proxy: {fmt % args}")


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
    server = http.server.HTTPServer(("0.0.0.0", LISTEN_PORT), HealthProxyHandler)
    logging.info(f"Health proxy listening on :{LISTEN_PORT}")
    server.serve_forever()
