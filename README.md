# Yüz Tanıma – Kapı Erişimi (Android)

Kişileri yüzleriyle tanımlayın, **AÇ** butonuna basınca kamera kişiyi tanır ve
**AÇILDI – Ad Soyad** gösterir. Tanımlı değilse **TANINMADI** uyarısı verir.

## Özellikler
- **Kişi Tanımla:** Ad, soyad + ön kameradan 5 yüz örneği
- **AÇ:** Canlı kamerada yüz eşleştirme, isim soyisim gösterimi
- **Sessiz canlılık kontrolü:** Hareket istemez; doğal göz kırpmayı bekler (basılı fotoğrafla açılmaz)
- Tamamen **cihaz içinde** çalışır, internet gerekmez, yüz verisi dışarı gönderilmez

## Kullanılan açık kaynak bileşenler
| Bileşen | Görev | Lisans |
|---|---|---|
| MediaPipe Face Landmarker | Yüz bulma, 478 nokta, göz kırpma | Apache 2.0 |
| TensorFlow Lite | Model çalıştırma | Apache 2.0 |
| MobileFaceNet (estebanuri/face_recognition) | 192 boyutlu yüz imzası | açık kaynak |
| CameraX | Kamera | Apache 2.0 |

Modeller derleme sırasında otomatik indirilir (`app/build.gradle.kts` → `downloadModels`).

## Derleme
GitHub Actions her `main` push'unda APK üretir: **Releases** sayfasından `YuzTanima.apk` indirilip telefona kurulur.

## Ayarlar
- Eşleşme hassasiyeti: `FaceEngine.MATCH_THRESHOLD` (varsayılan 0.70)
- Canlılık kontrolü: `RecognizeActivity.REQUIRE_LIVENESS` (true/false)
- Kişi kayıtları: `FaceStore` (ileride Firebase vb. ile çok cihazlı kullanım için tek değişecek yer)
