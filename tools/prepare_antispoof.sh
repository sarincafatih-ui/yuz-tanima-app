#!/usr/bin/env bash
# Sahte yüz tespiti (MiniFASNet, Apache 2.0) modellerini .onnx olarak hazırlar.
# Gradle derlemesinde otomatik çalışır (GitHub Actions / Linux / macOS).
set -euo pipefail
OUT="$1"
if [ -f "$OUT/2.7_80x80_MiniFASNetV2.onnx" ] && [ -f "$OUT/4_0_0_80x80_MiniFASNetV1SE.onnx" ]; then
  echo "Sahte yüz tespiti modelleri hazır."; exit 0
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
git clone -q https://github.com/minivision-ai/Silent-Face-Anti-Spoofing "$TMP/sfas"
git -C "$TMP/sfas" checkout -q b6d5f04ad78778917853b25c778acef6d5626d15
python3 -m venv "$TMP/venv"
"$TMP/venv/bin/pip" install -q torch --index-url https://download.pytorch.org/whl/cpu
"$TMP/venv/bin/pip" install -q onnx onnxscript
"$TMP/venv/bin/python" "$ROOT/tools/export_antispoof.py" "$TMP/sfas" "$OUT"
rm -rf "$TMP"
