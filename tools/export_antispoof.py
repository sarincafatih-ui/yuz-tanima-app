"""
Açık kaynak Silent-Face-Anti-Spoofing (Minivision, Apache 2.0) modellerini
Android'de ONNX Runtime ile çalışacak .onnx dosyalarına dönüştürür.

Kullanım:  python tools/export_antispoof.py <Silent-Face-Anti-Spoofing klasörü> <çıktı klasörü>
Gradle derlemesi bu betiği tools/prepare_antispoof.sh ile otomatik çalıştırır.
"""
import os
import sys

import torch

repo, out_dir = sys.argv[1], sys.argv[2]
sys.path.insert(0, repo)
from src.model_lib.MiniFASNet import MiniFASNetV2, MiniFASNetV1SE  # noqa: E402

MODELS = {
    "2.7_80x80_MiniFASNetV2.pth": MiniFASNetV2,
    "4_0_0_80x80_MiniFASNetV1SE.pth": MiniFASNetV1SE,
}

os.makedirs(out_dir, exist_ok=True)
for name, cls in MODELS.items():
    model = cls(conv6_kernel=(5, 5))  # 80x80 giriş için ((80+15)//16)
    state = torch.load(os.path.join(repo, "resources/anti_spoof_models", name), map_location="cpu")
    state = {k[7:] if k.startswith("module.") else k: v for k, v in state.items()}
    model.load_state_dict(state)
    model.eval()
    target = os.path.join(out_dir, name.replace(".pth", ".onnx"))
    torch.onnx.export(model, torch.rand(1, 3, 80, 80) * 255, target,
                      input_names=["input"], output_names=["logits"], opset_version=13, dynamo=False)
    print("Oluşturuldu:", target)
