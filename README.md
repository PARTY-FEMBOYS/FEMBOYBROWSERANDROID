# ✿ Femboy Browser для Android ✿

Нативное приложение на **Kotlin** + Android System WebView (движок Chromium).
Те же темы и анимированные фоны, что и на ПК: mp4 / gif за интерфейсом, «стеклянные» панели, редактор тем с живым предпросмотром.

* Android 9+ (API 28), размер APK ≈ 25 МБ (из них ~19 МБ — пять mp4-фонов).
* Вкладки, закладки, история, поиск, смена поисковика, ПК-версия сайтов, загрузки, полноэкранное видео, загрузка файлов на сайты.
* Можно выбрать «браузером по умолчанию» — ссылки из других приложений откроются в нём.
* 5 тем: Sakura Bunny, Lavender Night, Mint Cloud, Cyber Femboy, Sunset Bunny. Фоны `bg.mp4 … bg4.mp4` — в `app/src/main/assets/backgrounds/`.

> Код проверен компилятором Kotlin на Android API 34, но APK из этой папки я не собирал и на телефоне не запускал — сборку делаешь ты одним из способов ниже.

---

## Как получить APK

### Способ A — без установки чего-либо (GitHub Actions) ⭐ самый простой
1. Зарегистрируйся на github.com и создай **пустой** репозиторий (например `femboy-browser`).
2. Залей в него содержимое этой папки:
   ```
   git init
   git add .
   git commit -m "Femboy Browser"
   git branch -M main
   git remote add origin https://github.com/ТВОЙ_НИК/femboy-browser.git
   git push -u origin main
   ```
   (или на сайте: *Add file → Upload files*, перетащи ВСЁ, включая скрытую папку `.github` и файл `gradlew`).
3. Открой вкладку **Actions** → **Build APK** → дождись зелёной галочки (5–10 минут).
4. Внутри запуска скачай артефакт **FemboyBrowser-apk** — в zip лежит `app-release.apk`.

### Способ B — Android Studio
1. Установи Android Studio: https://developer.android.com/studio
2. *File → Open* → выбери эту папку. Дождись **Gradle Sync** (первый раз качает ~1 ГБ).
3. *Build → Build Bundle(s) / APK(s) → Build APK(s)*.
4. Готово: `app/build/outputs/apk/debug/app-debug.apk` (кнопка *locate* в уведомлении).
5. Прямо на телефон: включи на нём «Отладку по USB», подключи кабелем и нажми зелёный ▶ **Run**.

### Способ C — командная строка
Нужны **JDK 17** и **Android SDK** (проще всего поставить Android Studio и взять SDK из неё).
```
# Windows (PowerShell):   $env:ANDROID_HOME="C:\Users\ТЫ\AppData\Local\Android\Sdk"
# Linux / macOS:          export ANDROID_HOME=$HOME/Android/Sdk
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"

./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
./gradlew assembleRelease
```
Результат: `app/build/outputs/apk/debug/app-debug.apk` и `app/build/outputs/apk/release/app-release.apk`.
Если Gradle пишет *SDK location not found* — создай файл `local.properties` со строкой `sdk.dir=ПУТЬ_К_SDK` (в Windows слэши двойные: `C:\\Users\\...`).

---

## Установка на телефон
1. Скинь APK на телефон (Telegram, кабель, облако).
2. Открой файл → разреши «Установку из неизвестных источников» для приложения, из которого открываешь.
3. Play Protect может предупредить, что приложение не из магазина — нажми «Всё равно установить».
4. *Настройки → Приложения → Браузер по умолчанию* — выбери Femboy Browser, если хочешь открывать в нём все ссылки.

## Свой ключ подписи (чтобы обновлять приложение и для Google Play)
По умолчанию APK подписан отладочным ключом — для раздачи друзьям этого достаточно, но **обновление поверх** установленной версии работает только с тем же ключом, поэтому сохрани свой:
```
keytool -genkeypair -v -keystore femboy.jks -alias femboy -keyalg RSA -keysize 2048 -validity 10000
```
Потом в `app/build.gradle.kts` замени `signingConfig = signingConfigs.getByName("debug")` на свой `signingConfigs { create("release") { storeFile = file("femboy.jks"); storePassword = "…"; keyAlias = "femboy"; keyPassword = "…" } }`.
Для Google Play собирай AAB: `./gradlew bundleRelease`. Файл `*.jks` никому не показывай и не заливай на GitHub (он в `.gitignore`).

## Кастомизация
* **В самом приложении:** 🎨 → «Редактор тем» — цвета (HSV-пикер или HEX), скругление, прозрачность, затемнение, выбор фона и кнопка «＋ свой» (mp4/gif с телефона). Темы и фоны сохраняются на телефоне.
* **В исходниках:** кидай `*.json` в `app/src/main/assets/themes/` (формат как у ПК-версии, можно копировать файлы оттуда) и `bg5.mp4`, `bg6.gif` … в `app/src/main/assets/backgrounds/`. Если есть и mp4, и gif с одним именем — берётся mp4.
* **Название / значок:** `android:label` в `AndroidManifest.xml`, значок — `res/drawable/ic_launcher.xml`.
* **Имя пакета:** `applicationId` и `namespace` в `app/build.gradle.kts` (+ папка и `package` в .kt-файлах).

## Что стоит знать
* Движок — системный WebView: он обновляется через Google Play («Android System WebView» / Chrome). Это не Firefox/Gecko.
* Видеофон расходует батарею: для экономии выбери gif или фон «нет».
* На телефоне нет «размытия стекла» за панелями (в Android нет такого API для обычных View) — вместо него полупрозрачные панели.
* Расширений и синхронизации нет — это лёгкий браузер, а не замена Chrome.

## Структура
```
app/src/main/java/com/djanime/femboybrowser/
  MainActivity.kt   — интерфейс, вкладки, WebView, шторки меню, редактор тем
  Theme.kt          — модель темы (JSON, совместим с ПК)
  Repo.kt           — закладки/история/настройки, загрузка тем и фонов
  Ui.kt             — анимированный фон (mp4/gif) и полоска прогресса
app/src/main/assets/themes, backgrounds
.github/workflows/build.yml — облачная сборка APK
```
