
# Nora AI

Nora — Android telefon uchun ovozli, oflayn va onlayn ishlashga mo‘ljallangan shaxsiy yordamchi.

## Asosiy imkoniyatlar

- O‘zbek tilidagi ovozli buyruqlar.
- Internet bo‘lmaganda telefon buyruqlarini bajarish.
- Oflayn AI modelini yuklash va ishlatish.
- Oflayn nutqni matnga aylantirish modeli.
- Android Text-to-Speech orqali ovozli javob.
- Onlayn AI bilan suhbat.
- GitHub fayllarini o‘qish va tahlil qilish.
- Foydalanuvchi tasdig‘i bilan GitHub kodlarini yangilash.
- Suhbat tarixini telefonda saqlash.

## Qurilma

- Android 8.0 yoki undan yangi.
- ARM64 qurilma.
- Tavsiya etilgan RAM: 8 GB.
- Oflayn AI modeli uchun qo‘shimcha bo‘sh xotira kerak.

## APK yig‘ish

GitHub Actions orqali:

1. Repository ichida Actions bo‘limini oching.
2. Nora Android Build workflow'ni tanlang.
3. Run workflow tugmasini bosing.
4. Build tugagach, Nora-Android-APK artifactini oching.
5. APK faylini yuklab olib telefonga o‘rnating.

Har bir `main` branch commitidan keyin ham build avtomatik boshlanadi.

## Maxfiylik va xavfsizlik

- AI API kaliti Android Keystore asosidagi saqlash mexanizmi orqali himoyalanadi.
- GitHub tokenini ommaga yoki kodga joylamang.
- GitHub fayllarini o‘zgartirishdan oldin foydalanuvchi tasdig‘i talab qilinadi.
- Oflayn model va nutq modeli birinchi marta internet orqali yuklanadi.

## Muhim eslatma

Oflayn AI va nutqni aniqlash sifati model, telefon xotirasi va protsessoriga bog‘liq. Android TTS ovozi esa telefonda mavjud ovoz dvigateliga bog‘liq.
