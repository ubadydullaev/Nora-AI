# Nora AI — Android starter

Boshlang‘ich Android loyiha: ovozli kiritish, Android TTS orqali to‘rtta ovoz sozlamasi, sozlamalarni JSON faylga zaxiralash/tiklash va GitHub Actions orqali debug APK yig‘ish.

## Talablar
- JDK 17
- Android SDK 36
- Gradle 8.13
- Android Studio (loyihani lokal yig‘ish uchun)

## GitHub orqali APK yig‘ish
1. Ushbu papkadagi fayllarni `Nora-AI` repository ildiziga yuklang.
2. `main` branchga commit/push qiling.
3. GitHub → Actions → **Build Nora Android APK** workflow natijasini oching.
4. Build muvaffaqiyatli bo‘lsa, `Nora-debug-apk` artifactini yuklab oling.

## Muhim
Bu faqat starter versiya. Chat oynasidagi javoblar hozircha demo matn; haqiqiy AI API, GitHub boshqaruvi, Telegram signal kuzatuvi va fon rejimidagi wake-word hali ulanmagan. Ovozlar telefonning o‘rnatilgan Text-to-Speech dvigateli imkoniyatiga bog‘liq. Uzbekcha speech recognition ham qurilmadagi Google/Android nutq xizmatiga bog‘liq.
