# ULAK v4.0 — BYD Seal U DM-i (DiLink 3.0) & Samsung Galaxy S26 Ultra Rehberi

ULAK v4.0; DashArc benzeri uzaktan araç kontrolü, **Hey Ulak (Gemini AI Sesli Asistan)**, **Bluetooth Yakınlık Anahtarı (Phone as Key)** ve **Render Bulut + WebRTC Uzak Canlı Kamera & Web Portal** özelliklerini tek pakette birleştirir.

## 1. Yeni v4.0 Özellikleri
- **Hey Ulak (Gemini 2.5 Flash Sesli Asistan):**
  - Araç içi kokpitten veya S26 Ultra'dan sesle ya da yazıyla komut verin (*"Klimayı 21 derece aç, camları kapat, havalimanına Waze rotası başlat"*).
  - Google AI Studio'dan ücretsiz alacağınız Gemini API anahtarını **Sistem** sekmesine (veya araç ekranına) girin; anahtar yoksa temel Türkçe komutlar çevrimdışı yerel motorla çalışmaya devam eder.
- **Bluetooth Yakınlık Anahtarı (Phone as Key — PaK):**
  - En fazla 2 telefon (Bluetooth adı veya MAC adresi) kaydedilebilir.
  - Sadece **P** vitesinde ve **0 km/s** hızda çalışır; yaklaşınca (~2-3 m / `-65 dBm`) kilitleri açar, uzaklaşınca (`-82 dBm`) kilitler. Canlı RSSI ölçer ile **Hey Ulak → Bluetooth Anahtar** sekmesinden kalibre edilir.
- **Render 4G/5G Canlı Kamera & WebRTC Sinyalleşme + Web Portal:**
  - Aynı Wi-Fi'deyken düşük gecikmeli LAN MJPEG (`:45456`), araçtan uzaktayken **Render Bulut 4G/5G** üzerinden canlı kamera akışı çalışır.
  - `server-relay/` klasörünü Render'a (`https://server-kk7c.onrender.com`) yüklediğinizde hem mobil uygulama hem de herhangi bir web tarayıcısı (`https://server-kk7c.onrender.com`) üzerinden canlı telemetri, komutlar, Hey Ulak ve canlı kamera izlenebilir.
  - Render Environment Variables içine `TURN_URL`, `TURN_USERNAME`, `TURN_CREDENTIAL` eklerseniz `/api/ice-config` otomatik TURN sunucusunu paylaşır.

## 2. GitHub Actions ile APK Üretme
1. Bu ZIP içindeki tüm dosyaları GitHub deponuza gönderin.
2. **Actions** sekmesinde **ULAK v4.0 BYD APK Otomatik Derleyici** tüm birim testlerini çalıştırıp (`testDebugUnitTest`) iki APK dosyasını hazırlayacaktır:
   - `UlakCar_DiLink3_v4.0.0-DiLink3-debug.apk`
   - `UlakMobile_S26Ultra_v4.0.0-S26Ultra-debug.apk`
