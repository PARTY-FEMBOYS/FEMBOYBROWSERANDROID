package com.djanime.femboybrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : Activity() {

    companion object {
        private const val REQ_FILE = 11
        private const val REQ_BG = 12
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val ENGINES = linkedMapOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
            "Google" to "https://www.google.com/search?q=%s",
            "Bing" to "https://www.bing.com/search?q=%s",
            "Yandex" to "https://yandex.com/search/?text=%s",
            "Brave" to "https://search.brave.com/search?q=%s"
        )
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    class Tab {
        lateinit var web: WebView
        var title = "Новая вкладка"
        var url = ""
        var blank = true
        var loading = false
    }

    private lateinit var store: Store
    private lateinit var repo: ThemeRepo
    private var theme = Theme()
    private var themes: List<Theme> = emptyList()

    // каркас
    private lateinit var root: FrameLayout
    private lateinit var bg: BackgroundView
    private lateinit var shade: View
    private lateinit var column: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var reloadBtn: TextView
    private lateinit var progress: ProgressView
    private lateinit var content: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var tabsBadge: TextView
    private val barButtons = mutableListOf<TextView>()
    private lateinit var btnBack: TextView
    private lateinit var btnFwd: TextView

    // стартовая страница
    private lateinit var startScroll: ScrollView
    private lateinit var clock: TextClock
    private lateinit var dateClock: TextClock
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var startSearch: EditText
    private lateinit var tilesBox: LinearLayout

    private val tabs = ArrayList<Tab>()
    private var curIdx = 0
    private val tab: Tab get() = tabs[curIdx]

    private var insetTop = 0
    private var insetBottom = 0
    private var sheet: FrameLayout? = null
    private var onSheetClosed: (() -> Unit)? = null
    private var fsView: View? = null
    private var fsCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingBg: ((String) -> Unit)? = null
    private var defaultUa = ""

    // ───────────────────────── жизненный цикл ─────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        repo = ThemeRepo(this)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        defaultUa = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        buildUi()
        setContentView(root)
        themes = repo.themes()
        applyTheme((themes.firstOrNull { it.name == store.theme } ?: themes.first()).copyTheme())
        newTab(null)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.dataString ?: return
        if (i.action != Intent.ACTION_VIEW) return
        if (tabs.size == 1 && tab.blank) go(data) else newTab(data)
    }

    override fun onPause() {
        super.onPause()
        bg.pause()
        tabs.forEach { it.web.onPause() }
    }

    override fun onResume() {
        super.onResume()
        bg.resume()
        tabs.forEach { it.web.onResume() }
    }

    override fun onDestroy() {
        bg.destroy()
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
            fsView != null -> hideCustomView()
            sheet != null -> closeSheet()
            urlField.hasFocus() -> { urlField.clearFocus(); hideKeyboard() }
            tab.blank -> {
                val u = tab.web.url
                if (u != null && u != "about:blank") { tab.blank = false; selectTab(curIdx) }
                else if (tabs.size > 1) closeTab(curIdx) else super.onBackPressed()
            }
            tab.web.canGoBack() -> tab.web.goBack()
            tabs.size > 1 -> closeTab(curIdx)
            else -> super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else if (requestCode == REQ_BG && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            toast("Копирую фон…")
            Thread {
                val stem = repo.addBackground(uri)
                runOnUiThread {
                    if (stem == null) toast("Не получилось: нужен mp4 / gif") else { toast("Фон добавлен ✿"); pendingBg?.invoke(stem) }
                    pendingBg = null
                }
            }.start()
        }
    }

    // ───────────────────────── помощники ─────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r.toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun toUrl(input: String): String {
        val t = input.trim()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*").matches(t) || t == "about:blank") return t
        if (!t.contains(' ')) {
            if (Regex("^localhost(:\\d+)?(/.*)?$").matches(t) || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(t)) return "http://$t"
            if (Regex("^[^\\s/]+\\.[^\\s./]{2,}(:\\d+)?(/.*)?$").matches(t)) return "https://$t"
        }
        return (ENGINES[store.search] ?: ENGINES.getValue("DuckDuckGo")).replace("%s", Uri.encode(t))
    }

    // ───────────────────────── интерфейс ─────────────────────────
    private fun buildUi() {
        root = FrameLayout(this)
        bg = BackgroundView(this)
        root.addView(bg, MATCH, MATCH)
        shade = View(this)
        root.addView(shade, MATCH, MATCH)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, MATCH, MATCH)

        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                insetTop = i.top; insetBottom = i.bottom
            } else {
                insetTop = ins.systemWindowInsetTop; insetBottom = ins.systemWindowInsetBottom
            }
            column.setPadding(0, insetTop, 0, insetBottom)
            ins
        }

        // верхняя панель: адресная строка + обновить
        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(6), dp(6)) }
        urlField = EditText(this).apply {
            hint = "Поиск или адрес…"
            textSize = 15f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSelectAllOnFocus(true)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { go(text.toString()); clearFocus(); hideKeyboard(); true } else false
            }
        }
        topBar.addView(urlField, LinearLayout.LayoutParams(0, WRAP, 1f))
        reloadBtn = TextView(this).apply {
            text = "⟳"; textSize = 22f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { if (tab.loading) tab.web.stopLoading() else if (!tab.blank) tab.web.reload() }
        }
        topBar.addView(reloadBtn)
        column.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(8), dp(10), 0) })

        progress = ProgressView(this)
        column.addView(progress, LinearLayout.LayoutParams(MATCH, dp(3)).apply { setMargins(dp(26), dp(3), dp(26), dp(3)) })

        // контент: стартовая страница + WebView вкладок
        content = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(theme.radius).toFloat())
                }
            }
        }
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
        buildStartPage()

        // нижняя панель
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btnBack = barBtn("◀") { onBackPressedNav() }
        btnFwd = barBtn("▶") { if (tab.web.canGoForward()) tab.web.goForward() }
        val btnHome = barBtn("⌂") { goHome() }
        val tabsCell = FrameLayout(this).apply { setOnClickListener { tabsSheet() } }
        tabsBadge = TextView(this).apply { gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        tabsCell.addView(tabsBadge, FrameLayout.LayoutParams(dp(30), dp(26), Gravity.CENTER))
        val btnTheme = barBtn("🎨") { themesSheet() }
        val btnMenu = barBtn("☰") { menuSheet() }
        for (b in listOf(btnBack, btnFwd, btnHome)) bottomBar.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f))
        bottomBar.addView(tabsCell, LinearLayout.LayoutParams(0, dp(52), 1f))
        for (b in listOf(btnTheme, btnMenu)) bottomBar.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f))
        column.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) })
    }

    private fun barBtn(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this).apply { text = label; textSize = 22f; gravity = Gravity.CENTER; setOnClickListener { onClick() } }
        barButtons.add(b)
        return b
    }

    private fun buildStartPage() {
        startScroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18), dp(24), dp(18), dp(24)) }
        clock = TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"; textSize = 64f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
        dateClock = TextClock(this).apply { format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"; textSize = 14f; gravity = Gravity.CENTER }
        heroTitle = TextView(this).apply { text = "✿ Femboy Browser ✿"; textSize = 27f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        heroSub = TextView(this).apply { text = "быстрый · милый · полностью твой"; textSize = 13f; gravity = Gravity.CENTER }
        startSearch = EditText(this).apply {
            hint = "Поиск или адрес…"; textSize = 16f; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { val q = text.toString(); setText(""); clearFocus(); hideKeyboard(); go(q); true } else false
            }
        }
        tilesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(dateClock, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(18) })
        box.addView(heroTitle, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(heroSub, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(18) })
        box.addView(startSearch, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(8), 0, dp(8), dp(22)) })
        box.addView(tilesBox, LinearLayout.LayoutParams(MATCH, WRAP))
        startScroll.addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        content.addView(startScroll, MATCH, MATCH)
    }

    private fun buildTiles() {
        tilesBox.removeAllViews()
        val items = store.bookmarks().take(8).ifEmpty {
            listOf(Link("YouTube", "https://youtube.com"), Link("Pinterest", "https://pinterest.com"), Link("GitHub", "https://github.com"), Link("Wikipedia", "https://wikipedia.org"))
        }
        items.chunked(4).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 4) {
                val l = rowItems.getOrNull(k)
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(8), dp(2), dp(8)) }
                if (l != null) {
                    val name = l.title.ifBlank { l.url }
                    val ico = TextView(this).apply {
                        text = name.trim().take(1).uppercase(); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                        setTextColor(theme.bg)
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(theme.accent, theme.accent2)).apply { cornerRadius = dp(16).toFloat() }
                    }
                    cell.addView(ico, LinearLayout.LayoutParams(dp(52), dp(52)))
                    cell.addView(TextView(this).apply {
                        text = name; textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setTextColor(theme.text)
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
                    cell.setOnClickListener { go(l.url) }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            tilesBox.addView(row)
        }
    }

    // ───────────────────────── тема ─────────────────────────
    private fun applyTheme(t: Theme) {
        theme = t
        bg.show(repo.find(t.background))
        shade.setBackgroundColor(Theme.withAlpha(t.bg, t.overlayOpacity))
        val panel = Theme.withAlpha(t.panel, t.panelOpacity)
        val r = dp(t.radius)
        topBar.background = rounded(panel, r, 0x22FFFFFF)
        bottomBar.background = rounded(panel, r, 0x22FFFFFF)
        urlField.background = rounded(0x55000000, dp(999))
        urlField.setTextColor(t.text); urlField.setHintTextColor(t.textDim)
        reloadBtn.setTextColor(t.text)
        barButtons.forEach { it.setTextColor(t.text) }
        tabsBadge.setTextColor(t.text)
        tabsBadge.background = rounded(Color.TRANSPARENT, dp(8), t.accent).apply { setStroke(dp(2), t.accent) }
        progress.color = t.accent
        content.invalidateOutline()
        clock.setTextColor(t.text)
        dateClock.setTextColor(t.textDim)
        heroSub.setTextColor(t.textDim)
        heroTitle.paint.shader = LinearGradient(0f, 0f, heroTitle.paint.measureText(heroTitle.text.toString()), 0f, t.accent, t.accent2, Shader.TileMode.CLAMP)
        heroTitle.invalidate()
        startSearch.background = rounded(panel, dp(999), 0x33FFFFFF)
        startSearch.setTextColor(t.text); startSearch.setHintTextColor(t.textDim)
        buildTiles()
        refreshUi()
    }

    private fun selectTheme(name: String) {
        val t = themes.firstOrNull { it.name == name } ?: return
        store.theme = name
        applyTheme(t.copyTheme())
    }

    // ───────────────────────── вкладки ─────────────────────────
    private fun newTab(url: String?, switch: Boolean = true): Tab {
        val t = Tab()
        t.web = makeWebView(t)
        content.addView(t.web, MATCH, MATCH)
        t.web.visibility = View.GONE
        tabs.add(t)
        if (url != null) { t.blank = false; t.web.loadUrl(toUrl(url)) }
        if (switch) selectTab(tabs.size - 1) else refreshUi()
        return t
    }

    private fun selectTab(i: Int) {
        curIdx = i
        tabs.forEachIndexed { idx, t -> t.web.visibility = if (idx == i && !t.blank) View.VISIBLE else View.GONE }
        startScroll.visibility = if (tab.blank) View.VISIBLE else View.GONE
        refreshUi()
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) { newTab(null); return }
        if (i < curIdx) curIdx-- else if (i == curIdx) curIdx = min(i, tabs.size - 1)
        selectTab(curIdx)
    }

    private fun go(text: String) {
        if (text.isBlank()) return
        tab.blank = false
        tab.web.loadUrl(toUrl(text))
        selectTab(curIdx)
    }

    private fun goHome() {
        tab.blank = true
        tab.title = "Новая вкладка"
        tab.url = ""
        selectTab(curIdx)
    }

    private fun onBackPressedNav() {
        if (tab.blank) { onBackPressed(); return }
        if (tab.web.canGoBack()) tab.web.goBack() else goHome()
    }

    private fun refreshUi() {
        if (tabs.isEmpty()) return
        val t = tab
        if (!urlField.hasFocus()) urlField.setText(if (t.blank) "" else t.url)
        reloadBtn.text = if (t.loading) "✕" else "⟳"
        btnBack.alpha = if (t.blank && (t.web.url == null || t.web.url == "about:blank")) 0.4f else 1f
        btnFwd.alpha = if (t.web.canGoForward() && !t.blank) 1f else 0.4f
        tabsBadge.text = tabs.size.toString()
        progress.visibility = if (t.loading) View.VISIBLE else View.INVISIBLE
    }

    private fun applyUa(w: WebView) {
        w.settings.userAgentString = if (store.desktop) DESKTOP_UA else defaultUa
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = store.desktop
    }

    private fun makeWebView(t: Tab): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.WHITE)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        applyUa(w)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.setDownloadListener(DownloadListener { url, ua, cd, mime, _ ->
            try {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mime)
                req.addRequestHeader("User-Agent", ua)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                req.setTitle(name)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("⬇ Скачивание: $name")
            } catch (e: Exception) {
                toast("Не удалось скачать файл")
            }
        })

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.startsWith("http://") || u.startsWith("https://") || u == "about:blank") return false
                try {
                    val i = if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, request.url)
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    toast("Нет приложения для этой ссылки")
                } catch (e: Exception) { /* ignore */ }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                t.loading = true; t.url = url
                if (t === tab) refreshUi()
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.loading = false; t.url = url
                if (url.startsWith("http")) {
                    val h = store.history()
                    if (h.isEmpty() || h.last().url != url) { h.add(Link(view.title ?: url, url)); store.saveHistory(h) }
                }
                if (t === tab) refreshUi()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (url != "about:blank") t.url = url
                if (t === tab) refreshUi()
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title ?: t.url }
            override fun onProgressChanged(view: WebView, p: Int) { if (t === tab) progress.fraction = p / 100f }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val nt = newTab(null)
                nt.blank = false
                selectTab(tabs.indexOf(nt))
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = nt.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fsView != null) { callback.onCustomViewHidden(); return }
                fsView = view; fsCb = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, MATCH, MATCH)
                immersive(true)
            }

            override fun onHideCustomView() = hideCustomView()

            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }
        return w
    }

    private fun hideCustomView() {
        fsView?.let { root.removeView(it) }
        fsView = null
        fsCb?.onCustomViewHidden()
        fsCb = null
        immersive(false)
    }

    private fun immersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility =
                if (on) base or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else base
        }
    }

    // ───────────────────────── шторки (bottom sheets) ─────────────────────────
    private fun openSheet(title: String, frac: Float = 0.7f, dim: Int = 0x77000000, build: (LinearLayout) -> Unit) {
        closeSheet()
        val layer = FrameLayout(this).apply { setBackgroundColor(dim); setOnClickListener { closeSheet() } }
        val r = dp(26).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Theme.withAlpha(theme.panel, 0.97f))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), 0x33FFFFFF)
            }
            isClickable = true
            setPadding(0, 0, 0, insetBottom)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(10), dp(6)) }
        head.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(theme.accent); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this).apply { text = "✕"; textSize = 20f; setTextColor(theme.text); setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { closeSheet() } })
        box.addView(head)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(16)) }
        val sv = ScrollView(this).apply { addView(body); overScrollMode = View.OVER_SCROLL_NEVER }
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val h = (resources.displayMetrics.heightPixels * frac).toInt()
        layer.addView(box, FrameLayout.LayoutParams(MATCH, h + insetBottom, Gravity.BOTTOM))
        root.addView(layer, MATCH, MATCH)
        sheet = layer
        build(body)
    }

    private fun closeSheet() {
        val cb = onSheetClosed
        onSheetClosed = null
        sheet?.let { root.removeView(it) }
        sheet = null
        cb?.invoke()
    }

    private fun label(text: String, dim: Boolean = true): TextView = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(if (dim) theme.textDim else theme.text)
        setPadding(dp(4), dp(12), dp(4), dp(4))
    }

    private fun item(text: String, sub: String? = null, onClick: () -> Unit): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(0x22FFFFFF, dp(14))
            setOnClickListener { onClick() }
        }
        v.addView(TextView(this).apply { this.text = text; textSize = 15f; setTextColor(theme.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (sub != null) v.addView(TextView(this).apply { this.text = sub; textSize = 12f; setTextColor(theme.textDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        return v
    }

    private fun LinearLayout.addGap(v: View) = addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (filled) theme.bg else theme.text)
        typeface = if (filled) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        background = if (filled) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(theme.accent, theme.accent2)).apply { cornerRadius = dp(999).toFloat() }
        else rounded(0x22FFFFFF, dp(999))
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    private fun menuSheet() {
        openSheet("Меню", 0.62f) { body ->
            val entries = listOf(
                "＋ Новая вкладка" to { newTab(null) ; Unit },
                "🎨 Темы" to { themesSheet() },
                "★ В закладки" to { addBookmark() },
                "📑 Закладки" to { bookmarksSheet() },
                "🕘 История" to { historySheet() },
                "🔎 Поисковик: ${store.search}" to { searchSheet() },
                (if (store.desktop) "🖥 ПК-версия: вкл" else "📱 ПК-версия: выкл") to { toggleDesktop() },
                "✎ Редактор тем" to { editorSheet() },
                "🗑 Очистить данные" to { clearData() }
            )
            entries.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { (txt, act) ->
                    val b = item(txt) { closeSheet(); act() }
                    row.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }

    private fun themesSheet() {
        openSheet("🎨 Темы", 0.6f) { body ->
            themes = repo.themes()
            themes.forEach { t ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(if (t.name == theme.name) 0x44FFFFFF else 0x22FFFFFF, dp(14))
                    setOnClickListener { selectTheme(t.name); closeSheet() }
                }
                val dot = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.accent, t.accent2)).apply { shape = GradientDrawable.OVAL } }
                row.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
                row.addView(TextView(this).apply { text = t.name; textSize = 16f; setTextColor(theme.text) }, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (t.name == theme.name) row.addView(TextView(this).apply { text = "✿"; textSize = 16f; setTextColor(theme.accent) })
                body.addGap(row)
            }
            body.addView(pill("✎ Редактор тем", true) { editorSheet() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    private fun tabsSheet() {
        openSheet("Вкладки: ${tabs.size}", 0.7f) { body ->
            body.addView(pill("＋ Новая вкладка", true) { closeSheet(); newTab(null) }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            tabs.forEachIndexed { i, t ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = rounded(if (i == curIdx) 0x44FFFFFF else 0x22FFFFFF, dp(14))
                    setPadding(dp(14), dp(8), dp(4), dp(8))
                    setOnClickListener { closeSheet(); selectTab(i) }
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply { text = if (t.blank) "Новая вкладка" else t.title; textSize = 15f; setTextColor(theme.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                col.addView(TextView(this).apply { text = if (t.blank) "—" else t.url; textSize = 12f; setTextColor(theme.textDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 18f; setTextColor(theme.text); setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener { closeSheet(); closeTab(i); if (tabs.size > 1) tabsSheet() }
                })
                body.addGap(row)
            }
        }
    }

    private fun bookmarksSheet() {
        openSheet("📑 Закладки", 0.7f) { body ->
            val list = store.bookmarks()
            if (list.isEmpty()) body.addView(label("Закладок пока нет — открой сайт и выбери «★ В закладки» в меню."))
            list.forEach { l ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = rounded(0x22FFFFFF, dp(14)); setPadding(dp(14), dp(8), dp(4), dp(8))
                    setOnClickListener { closeSheet(); go(l.url) }
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply { text = l.title.ifBlank { l.url }; textSize = 15f; setTextColor(theme.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                col.addView(TextView(this).apply { text = l.url; textSize = 12f; setTextColor(theme.textDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(TextView(this).apply {
                    text = "🗑"; textSize = 18f; setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener { store.saveBookmarks(store.bookmarks().filter { it.url != l.url }); buildTiles(); bookmarksSheet() }
                })
                body.addGap(row)
            }
        }
    }

    private fun historySheet() {
        openSheet("🕘 История", 0.75f) { body ->
            val list = store.history().asReversed()
            if (list.isEmpty()) body.addView(label("История пуста"))
            else body.addView(pill("Очистить историю", false) { store.saveHistory(emptyList()); historySheet() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            list.take(200).forEach { l -> body.addGap(item(l.title.ifBlank { l.url }, l.url) { closeSheet(); go(l.url) }) }
        }
    }

    private fun searchSheet() {
        openSheet("🔎 Поисковик", 0.45f) { body ->
            ENGINES.keys.forEach { n ->
                body.addGap(item((if (n == store.search) "● " else "○ ") + n) { store.search = n; toast("Поисковик: $n"); closeSheet() })
            }
        }
    }

    private fun addBookmark() {
        if (tab.blank) { toast("Сначала открой сайт"); return }
        val list = store.bookmarks()
        if (list.any { it.url == tab.url }) { toast("Уже в закладках"); return }
        list.add(0, Link(tab.title, tab.url))
        store.saveBookmarks(list)
        buildTiles()
        toast("★ Добавлено в закладки")
    }

    private fun toggleDesktop() {
        store.desktop = !store.desktop
        tabs.forEach { applyUa(it.web) }
        if (!tab.blank) tab.web.reload()
        toast(if (store.desktop) "ПК-версия сайтов включена" else "Мобильная версия сайтов")
    }

    private fun clearData() {
        AlertDialog.Builder(this)
            .setTitle("Очистить данные?")
            .setMessage("Будут удалены куки, кэш, история и данные сайтов. Закладки и темы останутся.")
            .setPositiveButton("Очистить") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.web.clearCache(true); it.web.clearHistory() }
                store.saveHistory(emptyList())
                toast("Готово ✿")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ───────────────────────── редактор тем ─────────────────────────
    private fun editorSheet() {
        val orig = theme.copyTheme()
        val draft = theme.copyTheme()
        var saved = false
        openSheet("✎ Редактор тем", 0.58f, 0x11000000) { body ->
            onSheetClosed = { if (!saved) applyTheme(orig) }
            fun live() = applyTheme(draft)

            body.addView(label("Название"))
            body.addView(EditText(this).apply {
                setText(draft.name); setSingleLine(); setTextColor(theme.text); textSize = 15f
                background = rounded(0x33000000, dp(12)); setPadding(dp(14), dp(10), dp(14), dp(10))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { draft.name = s.toString() }
                })
            })

            body.addView(label("Анимированный фон (mp4 / gif)"))
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
            fun drawChips() {
                chips.removeAllViews()
                val opts = listOf("" to "нет") + repo.backgrounds().map { it.stem to (it.stem + if (it.isVideo) " ▶" else " gif") }
                opts.forEach { (stem, text) ->
                    chips.addView(pill(text, stem == draft.background) { draft.background = stem; live(); drawChips() },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
                }
                chips.addView(pill("＋ свой", false) {
                    pendingBg = { stem -> draft.background = stem; live(); drawChips() }
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/gif", "image/webp"))
                    }
                    startActivityForResult(i, REQ_BG)
                })
            }
            drawChips()
            body.addView(hs)

            body.addView(label("Цвета"))
            colorRow(body, "Фон", { draft.bg }, { draft.bg = it; live() })
            colorRow(body, "Панели", { draft.panel }, { draft.panel = it; live() })
            colorRow(body, "Акцент", { draft.accent }, { draft.accent = it; live() })
            colorRow(body, "Акцент 2", { draft.accent2 }, { draft.accent2 = it; live() })
            colorRow(body, "Текст", { draft.text }, { draft.text = it; live() })
            colorRow(body, "Доп. текст", { draft.textDim }, { draft.textDim = it; live() })

            sliderRow(body, "Скругление", 0, 32, draft.radius, { "$it px" }) { draft.radius = it; live() }
            sliderRow(body, "Прозрачность панелей", 0, 100, (draft.panelOpacity * 100).toInt(), { "$it%" }) { draft.panelOpacity = it / 100f; live() }
            sliderRow(body, "Затемнение фона", 0, 90, (draft.overlayOpacity * 100).toInt(), { "$it%" }) { draft.overlayOpacity = it / 100f; live() }

            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(pill("💾 Сохранить", true) {
                draft.name = draft.name.trim().ifEmpty { "Моя тема" }
                repo.save(draft)
                themes = repo.themes()
                saved = true
                selectTheme(draft.name)
                closeSheet()
                toast("Тема «${draft.name}» сохранена ✿")
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            btns.addView(pill("Удалить", false) {
                if (repo.delete(orig.name)) {
                    themes = repo.themes()
                    saved = true
                    selectTheme(themes.first().name)
                    closeSheet()
                    toast("Тема удалена")
                } else toast("Встроенную тему не удалить — сохрани копию под другим названием")
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            body.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
            body.addView(label("Сменил название — получится новая тема. Формат файлов совпадает с ПК-версией."))
        }
    }

    private fun colorRow(parent: LinearLayout, name: String, get: () -> Int, set: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val sw = View(this).apply { background = rounded(get(), dp(10), Color.WHITE) }
        val hex = EditText(this).apply {
            setText(Theme.hex(get())); textSize = 14f; setTextColor(theme.text); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            background = rounded(0x33000000, dp(10)); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        var internal = false
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (internal) return
                val c = try { Color.parseColor(s.toString()) } catch (e: Exception) { return }
                val rgb = c or (0xFF shl 24)
                set(rgb)
                sw.background = rounded(rgb, dp(10), Color.WHITE)
            }
        })
        sw.setOnClickListener {
            pickColor(get()) { c ->
                set(c)
                internal = true; hex.setText(Theme.hex(c)); internal = false
                sw.background = rounded(c, dp(10), Color.WHITE)
            }
        }
        row.addView(sw, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
        row.addView(TextView(this).apply { text = name; textSize = 14f; setTextColor(theme.text) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(hex, LinearLayout.LayoutParams(dp(120), WRAP))
        parent.addView(row)
    }

    private fun sliderRow(parent: LinearLayout, name: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply { text = "$name: ${fmt(value)}"; textSize = 12f; setTextColor(theme.textDim); setPadding(dp(4), dp(12), dp(4), 0) }
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(theme.accent)
            thumbTintList = ColorStateList.valueOf(theme.accent2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$name: ${fmt(p + min)}"
                    if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(tv)
        parent.addView(sb)
    }

    /** Маленький HSV-пикер цвета: оттенок / насыщенность / яркость. */
    private fun pickColor(initial: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(4)) }
        val preview = View(this)
        col.addView(preview, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(10) })
        fun cur() = Color.HSVToColor(hsv)
        fun upd() { preview.background = rounded(cur(), dp(12), Color.WHITE) }
        fun bar(label: String, max: Int, start: Int, set: (Int) -> Unit) {
            col.addView(TextView(this).apply { text = label; textSize = 12f })
            col.addView(SeekBar(this).apply {
                this.max = max; progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { set(p); upd() }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        bar("Оттенок", 360, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        bar("Насыщенность", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f }
        bar("Яркость", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f }
        upd()
        AlertDialog.Builder(this).setView(col)
            .setPositiveButton("Готово") { _, _ -> onPick(cur()) }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
