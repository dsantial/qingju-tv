// SPDX-License-Identifier: GPL-3.0-only
// New TV interface and API21 playback integration, 2026-10-18.
package local.qingju.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import org.json.JSONArray
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.hgtv.star.list.HomeScreen
import com.hgtv.star.tv.*
import com.hgtv.star.core.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    private val repository = ContentRepository()
    private val prefs by lazy { getSharedPreferences("qingju", Context.MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var job: Future<*>? = null
    private var scope: RequestScope? = null
    private var ticket = 0
    private var board = RankBoard.REAL
    private var page = 1
    private var query = ""
    private var detail: Detail? = null
    private var episode = 0
    private var player: ExoPlayer? = null
    private var remote: RemoteVideo? = null
    private lateinit var prefetch: EpisodePrefetch
    private val queuedVideos=linkedMapOf<Int,RemoteVideo>()
    private var pendingNextIndex=-1
    private var prefetchAttempt=-1
    private var prefetchFailures=0
    private var consumedRemoteKey=-1
    private var episodeTitle:TextView?=null
    private var previousButton:Button?=null
    private var nextButton:Button?=null
    private var session: MediaSession? = null
    private var controls: LinearLayout? = null
    private var stateLabel: TextView? = null
    private var speedButton: Button? = null
    private var videoView: PlayerView? = null
    private var sourcePortrait=false
    private var quality = ""
    private var speed = 1f
    private var playingScreen = false
    private var overlayUntil = 0L
    private var failure: String? = null
    private var started = false
    private var destroyed = false
    private var registeredThisProcess = false
    private var lastLog = 0L
    private lateinit var posters: PosterLoader
    private var dialog: AlertDialog? = null
    private var screenMode = "home"
    private var collectionMode = "history"
    private var requestedSeries: Series? = null
    private var selectedSeriesId = ""
    private var wasStopped = false
    private var playbackProgress: SeekBar? = null
    private var draggingProgress = false
    private var retryButton: Button? = null
    private var playButton: Button? = null
    private val ink get()=TvStyle.text
    private val muted get()=TvStyle.muted
    private val accent get()=TvStyle.accent
    private var homeView:HomeScreen?=null
    private var homePosition:HomeScreen.Position?=null
    private var homeFocus=""
    private var homeFeed:Map<RankBoard,CatalogPage> = emptyMap()
    private var navEntry:View?=null
    private var browseOrigin="home"
    private var categoryPath=CategoryCatalog.defaultTag.path
    private var rankBoard=RankBoard.COMIC
    private val clock = object : Runnable {
        override fun run() {
            if (!started) return
            updatePlayback()
            if (playingScreen && dialog?.isShowing != true && player?.isPlaying == true && SystemClock.elapsedRealtime() > overlayUntil)
                controls?.visibility = View.GONE
            main.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        TvStyle.animate=false; Soft.quality=.5f
        root = FrameLayout(this).apply { setBackgroundColor(TvStyle.background) }
        setContentView(root)
        posters = PosterLoader(this)
        prefetch=EpisodePrefetch(repository)
        speed = prefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)
        session = MediaSession(this, "QingjuTV").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { player?.play(); showControls() }
                override fun onPause() { player?.pause(); saveProgress(); showControls() }
                override fun onSeekTo(pos: Long) { player?.seekTo(pos); showControls() }
                override fun onSkipToNext() { nextEpisode() }
                override fun onSkipToPrevious() { if (episode > 0) playEpisode(episode - 1) }
            })
        }
        showHome()
    }
    override fun onStart() {
        super.onStart(); started = true
        if (wasStopped) {
            wasStopped = false
            when {
                playingScreen && player == null -> playEpisode(episode, detail?.let { prefs.getLong("position:${it.series.id}",0) } ?: 0)
                playingScreen -> { showControls(); player?.let { prepareNext(it) } }
                screenMode == "detail" -> requestedSeries?.let { showDetail(it) }
                screenMode == "collection" -> showCollection(collectionMode)
                screenMode == "ranking" -> showRanking()
                screenMode == "category" -> showCategory()
                screenMode == "search" -> showSearchPage()
                else -> showHome()
            }
        }
        main.post(clock)
    }
    override fun onStop() {
        started = false; wasStopped = true; main.removeCallbacks(clock)
        posters.cancelPage()
        cancelJob(); prefetch.cancel(); pendingNextIndex=-1; prefetchAttempt=-1
        player?.pause(); saveProgress(); updatePlayback()
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true; dialog?.dismiss(); posters.close(); prefetch.close(); cancelJob(); releasePlayer(); session?.release()
        worker.shutdownNow()
        cleanup.execute { repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() }
        cleanup.shutdown(); super.onDestroy()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 19f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(230, 237, 245)); setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun shape(color: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 10) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), stroke)
    }
    private fun focusBackground(selected: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),TvStyle.soft(this@MainActivity,TvStyle.lit,14,glow=TvStyle.litGlow))
        addState(intArrayOf(android.R.attr.state_pressed),TvStyle.well(this@MainActivity))
        addState(intArrayOf(),if(selected) TvStyle.well(this@MainActivity) else TvStyle.soft(this@MainActivity))
    }
    private fun button(parent: LinearLayout,label:String,action:()->Unit): Button = Button(this).apply {
        text=label; isAllCaps=false; isFocusableInTouchMode=true; id=View.generateViewId(); gravity=Gravity.CENTER
        paintButton(this,ButtonSize.COMPACT,ButtonTone.SECONDARY,false)
        setOnFocusChangeListener { _,focused -> paintButton(this,ButtonSize.COMPACT,ButtonTone.SECONDARY,focused,isSelected,isEnabled) }
        setOnClickListener { action() }
        parent.addView(this,LinearLayout.LayoutParams(-2,dp(50)).apply { marginEnd=dp(5); bottomMargin=dp(4) })
    }
    private fun base(title: String): LinearLayout {
        posters.cancelPage(); root.removeAllViews()
        return column().also { body ->
            body.setPadding(dp(26),dp(20),dp(26),dp(16)); root.addView(body,FrameLayout.LayoutParams(-1,-1))
            body.addView(text(title,25f).apply { setTypeface(null,Typeface.BOLD) })
        }
    }
    private fun showDialog(builder: AlertDialog.Builder, focus: View? = null) {
        dialog?.dismiss()
        val shown = builder.create(); dialog = shown
        shown.setOnDismissListener { if (dialog === shown) dialog=null; if (!destroyed) { showControls(); if (focus?.isAttachedToWindow==true) focus.requestFocus() } }
        shown.show()
    }
    private fun shell(title:String,current:String):LinearLayout {
        posters.cancelPage(); root.removeAllViews()
        val body=column().apply { setPadding(dp(30),dp(12),dp(30),dp(12)) }
        root.addView(body,FrameLayout.LayoutParams(-1,-1))
        val header=row(); body.addView(header)
        header.addView(text("青聚",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) },LinearLayout.LayoutParams(0,dp(56),1f))
        fun nav(label:String,key:String,action:()->Unit) {
            button(header,label,action).apply { isSelected=current==key; paintButton(this,ButtonSize.COMPACT,ButtonTone.SECONDARY,false,isSelected); if(isSelected) navEntry=this }
        }
        navEntry=null
        nav("首页","home") { query=""; showHome() }
        nav("分类","category") { query="";page=1;showCategory() }
        nav("排行榜","ranking") { query="";page=1;showRanking() }
        nav("搜索","search") { searchDialog() }
        nav("收藏","favorites") { showCollection("favorites") }
        nav("历史","history") { showCollection("history") }
        nav("设置","settings") { settingsDialog() }
        if(navEntry==null) navEntry=header.getChildAt(1)
        if(title.isNotBlank() && title!="首页") body.addView(text(title,22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        return body
    }
    private fun storedList(key:String): List<Series> = runCatching {
        val list=JSONArray(prefs.getString(key,"[]")); (0 until list.length()).map { Series.fromJson(list.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun storeList(key:String,items:List<Series>) {
        val json=JSONArray(); items.distinctBy { it.id }.take(60).forEach { json.put(it.toJson()) }; prefs.edit().putString(key,json.toString()).apply()
    }
    private fun isFavorite(id:String)=storedList("favorites").any { it.id==id }
    private fun toggleFavorite(series:Series): Boolean {
        val list=storedList("favorites"); val added=list.none { it.id==series.id }
        storeList("favorites",if(added) listOf(series)+list else list.filterNot { it.id==series.id }); return added
    }
    private fun cardGrid(parent:LinearLayout,items:List<Series>,history:Boolean=false,ranking:ComicRanking?=null,requestFocus:Boolean=true) {
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; isFillViewport=true }
        val grid=column(); scroll.addView(grid); parent.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        if(items.isEmpty()) { grid.addView(text("暂时没有内容，可换个分类或稍后刷新。",18f)); return }
        val cols=6
        val width=((resources.displayMetrics.widthPixels/resources.displayMetrics.density-60-10*(cols-1))/cols).toInt()
        val height=((width-2*TvStyle.SOFT_BAND-8)/TvStyle.POSTER_ASPECT).toInt().coerceAtLeast(110)
        var focusTarget:View?=null
        items.chunked(cols).forEach { chunk ->
            val line=row().apply { gravity=Gravity.TOP }; grid.addView(line)
            chunk.forEach { series ->
                val position=ranking?.positions?.get(series.id)
                val label=if(history) "第 ${prefs.getInt("episode:${series.id}",0)+1} 集 · ${time(prefs.getLong("position:${series.id}",0))}" else position?.heat?.takeIf { it.isNotBlank() } ?: series.badge
                val card=PosterCard(this,height) { image,url -> posters.load(url,image,dp(width),dp(height)) }
                card.bind(series,label,position?.rank?.let { "第 $it 名" } ?: series.badge,null,position?.rank?.let { it<=3 } ?: false)
                card.nextFocusUpId=navEntry?.id ?: View.NO_ID
                card.setOnFocusChangeListener { _,focused -> card.setFocused(focused) }
                card.setOnClickListener { selectedSeriesId=series.id; showDetail(series) }
                line.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(10);bottomMargin=dp(10) })
                if(focusTarget==null || selectedSeriesId==series.id) focusTarget=card
            }
            repeat(cols-chunk.size) { line.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply { marginEnd=dp(10) }) }
        }
        if(requestFocus) focusTarget?.requestFocus()
    }
    private fun restoreBrowse() {
        when(browseOrigin) {
            "ranking" -> showRanking()
            "category" -> showCategory()
            "search" -> showSearchPage()
            "collection" -> showCollection(collectionMode)
            else -> { query=""; showHome() }
        }
    }
    private fun cancelJob() { ticket++; scope?.cancel(); scope = null; job?.cancel(true); job = null }
    private fun <T> load(message: String, block: () -> T, done: (T) -> Unit) {
        cancelJob(); val token = ticket; val request = RequestScope(); scope = request
        status.text = message
        job = worker.submit {
            try {
                val value = request.run(block)
                main.post {
                    if (!destroyed && token == ticket && (value !is RemoteVideo || started)) done(value)
                    else if (value is RemoteVideo) value.close()
                }
            } catch (error: Throwable) {
                if (error is ThreadDeath || error is VirtualMachineError) throw error
                val explanation = explain(error)
                android.util.Log.w("QingjuTV", "request failed: $explanation")
                main.post { if (!destroyed && token == ticket) { status.text = if(online()) "暂时无法播放，请重试或换一部。" else "网络未连接，请检查电视网络。"; failure = explanation; retryButton?.visibility=View.VISIBLE; showControls() } }
            }
        }
    }
    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION") return cm.activeNetworkInfo?.isConnected == true
    }
    private fun explain(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(5).joinToString(" → ") {
            it.javaClass.simpleName + ": " + (it.message ?: "无详细信息")
        }.replace(Regex("https?://[^\\s)]+"), "[请求地址]").take(360)
        return (if (!online()) "电视当前未联网。\n" else "网络已连接，请求或播放失败。\n") + chain
    }
    private fun showHome() {
        if(query.isNotBlank()) { showSearchPage(); return }
        cancelJob(); releasePlayer(); playingScreen=false; detail=null; requestedSeries=null; failure=null; screenMode="home"
        val body=shell("首页","home")
        status=text("正在更新热门…",12f).apply { setTextColor(muted) }; body.addView(status)
        val top=navEntry ?: root
        val home=HomeScreen(this,top,dp(30),
            { image,url -> posters.load(url,image,dp(150),dp(205)) },
            { series,_ -> showDetail(series) },
            { series,_,_ -> showDialog(AlertDialog.Builder(this).setTitle(series.title).setItems(arrayOf(if(isFavorite(series.id)) "取消收藏" else "加入收藏","查看详情")) { _,which -> if(which==0) { toggleFavorite(series); showHome() } else showDetail(series) }) },
            { key -> homeFocus=key })
        homeView=home; body.addView(home,LinearLayout.LayoutParams(-1,0,1f))
        home.onHeadUp={ top.requestFocus(); true }
        fun render(position:HomeScreen.Position?,focus:Boolean) {
            val shelves=mutableListOf<HomeScreen.Shelf>()
            val history=storedList("history").take(10)
            if(history.isNotEmpty()) shelves+=HomeScreen.Shelf("history","历史记录",history.map { HomeScreen.Entry(it,"第 ${prefs.getInt("episode:${it.id}",0)+1} 集 · ${time(prefs.getLong("position:${it.id}",0))}") },{ showCollection("history") },{ showCollection("history") })
            val favorites=storedList("favorites").take(10)
            if(favorites.isNotEmpty()) shelves+=HomeScreen.Shelf("favorites","我的收藏",favorites.map { HomeScreen.Entry(it,it.badge) },{ showCollection("favorites") },{ showCollection("favorites") })
            RankBoard.entries.forEach { type -> homeFeed[type]?.takeIf { it.items.isNotEmpty() }?.let { data ->
                shelves+=HomeScreen.Shelf("rank-${type.storedValue}",type.homeTitle,data.items.take(10).map { series -> HomeScreen.Entry(series,series.badge) },{ page=1;showRanking(type) },{ page=1;showRanking(type) })
            } }
            home.render(shelves,homeFocus,focus,position)
        }
        render(homePosition,true)
        load("正在更新热门…",{ repository.homeFeed() }) { data ->
            val current=home.capturePosition(); val focus=home.hasFocus() || !top.hasFocus()
            homeFeed=data; status.visibility=View.GONE; render(current,focus)
        }
    }
    private fun showRanking(type:RankBoard=rankBoard,focusResults:Boolean=true) {
        rankBoard=type; query=""; cancelJob(); releasePlayer(); playingScreen=false; detail=null; requestedSeries=null;screenMode="ranking"
        val body=shell("热播榜","ranking")
        val tabs=row();body.addView(tabs)
        RankBoard.entries.forEach { board -> button(tabs,board.label) { page=1;selectedSeriesId="";showRanking(board,false) }.apply { isSelected=board==rankBoard; if(isSelected && !focusResults) requestFocus() } }
        status=text("正在加载榜单…",13f).apply { setTextColor(muted) };body.addView(status)
        val content=column();body.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        val nav=row();body.addView(nav)
        button(nav,"上一页") { if(page>1) { page--;selectedSeriesId="";showRanking() } }.isEnabled=page>1
        val more=button(nav,"下一页") { page++;selectedSeriesId="";showRanking() };more.isEnabled=false
        button(nav,"刷新") { showRanking() }
        load("正在加载榜单…",{ repository.rank(page,rankBoard) }) { result ->
            status.text="${rankBoard.source}  ·  第 $page 页"+result.ranking?.updatedText?.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty()
            cardGrid(content,result.items,ranking=result.ranking,requestFocus=focusResults);more.isEnabled=result.hasMore
        }
    }
    private fun showCategory(focusResults:Boolean=true) {
        cancelJob();releasePlayer();playingScreen=false;detail=null;requestedSeries=null;screenMode="category"
        val body=shell("分类片库","category")
        val groups=row();body.addView(groups)
        val selected=CategoryCatalog.groupOf(categoryPath) ?: CategoryCatalog.groups.first()
        CategoryCatalog.groups.forEach { group -> button(groups,group.label) { categoryPath=group.tags.first().path;page=1;selectedSeriesId="";showCategory(false) }.isSelected=group==selected }
        val tagsScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false };val tags=row();tagsScroll.addView(tags);body.addView(tagsScroll)
        selected.tags.forEach { tag -> button(tags,tag.label) { categoryPath=tag.path;page=1;selectedSeriesId="";showCategory(false) }.apply { isSelected=tag.path==categoryPath;if(isSelected && !focusResults) requestFocus() } }
        status=text("正在加载分类…",13f).apply { setTextColor(muted) };body.addView(status)
        val content=column();body.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        val nav=row();body.addView(nav)
        button(nav,"上一页") { if(page>1) {page--;selectedSeriesId="";showCategory()} }.isEnabled=page>1
        val more=button(nav,"下一页") { page++;selectedSeriesId="";showCategory() };more.isEnabled=false
        load("正在加载分类…",{ repository.category(categoryPath,page) }) { result ->
            status.text="${CategoryCatalog.describe(categoryPath)}  ·  第 $page 页  ·  ${result.items.size} 部"
            cardGrid(content,result.items,requestFocus=focusResults);more.isEnabled=result.hasMore
        }
    }
    private fun showSearchPage() {
        cancelJob();releasePlayer();playingScreen=false;detail=null;requestedSeries=null;screenMode="search"
        val body=shell("搜索：$query","search")
        status=text("正在搜索…",13f);body.addView(status)
        val content=column();body.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        val nav=row();body.addView(nav)
        button(nav,"重新搜索") { searchDialog() }
        val more=button(nav,"下一页") { page++;selectedSeriesId="";showSearchPage() };more.isEnabled=false
        load("正在搜索…",{ repository.search(query,page) }) { result -> status.text="第 $page 页 · ${result.items.size} 部";cardGrid(content,result.items);more.isEnabled=result.hasMore }
    }
    private fun showCollection(kind:String) {
        cancelJob(); releasePlayer(); playingScreen=false; detail=null; requestedSeries=null; screenMode="collection"; collectionMode=kind
        val body=shell(if(kind=="favorites") "我的收藏" else "继续观看",kind)
        status=text("只保存在这台电视",14f).apply { setTextColor(muted) }; body.addView(status)
        var entries=storedList(kind)
        if(kind=="history" && entries.isEmpty()) prefs.getString("last-series",null)?.let { runCatching { listOf(Series.fromJson(JSONObject(it))) }.getOrNull()?.let { entries=it } }
        cardGrid(body,entries,kind=="history")
    }
    private fun searchDialog() {
        val input=EditText(this).apply { isSingleLine=true; hint="剧名或关键词"; setText(query); setPadding(dp(20),dp(12),dp(20),dp(12)) }
        showDialog(AlertDialog.Builder(this).setTitle("搜索短剧").setView(input)
            .setPositiveButton("搜索") { _,_-> query=input.text.toString().trim().take(80); page=1; selectedSeriesId=""; showHome() }.setNegativeButton("取消",null))
    }
    private fun showDetail(series:Series) {
        if(screenMode=="home") homePosition=homeView?.capturePosition()
        if(screenMode in listOf("home","ranking","category","search","collection")) browseOrigin=screenMode
        cancelJob(); releasePlayer(); playingScreen=false; detail=null; requestedSeries=series; failure=null; screenMode="detail"
        val body=base("剧集详情")
        val outer=ScrollView(this); val content=column(); outer.addView(content); body.addView(outer,LinearLayout.LayoutParams(-1,0,1f))
        val hero=row().apply { gravity=Gravity.TOP; setPadding(0,dp(8),0,dp(14)) }; content.addView(hero)
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(32,49,66)) }
        hero.addView(image,LinearLayout.LayoutParams(dp(150),dp(208)).apply { marginEnd=dp(22) }); posters.load(series.cover,image,dp(150),dp(208))
        val info=column(); hero.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        info.addView(text(series.title,26f).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        status=text("正在加载剧集…",14f).apply { setTextColor(muted); maxLines=2; ellipsize=TextUtils.TruncateAt.END }; info.addView(status)
        val summary=text(series.description,14f).apply { setTextColor(muted); maxLines=3; ellipsize=TextUtils.TruncateAt.END }; info.addView(summary)
        val actions=row(); info.addView(actions)
        val play=button(actions,"正在加载") {}; play.isEnabled=false
        val fav=button(actions,if(isFavorite(series.id)) "✓ 已收藏" else "＋ 收藏") {}
        fav.setOnClickListener { fav.text=if(toggleFavorite(detail?.series ?: series)) "✓ 已收藏" else "＋ 收藏" }
        button(actions,"返回列表") { restoreBrowse() }
        content.addView(text("选择集数",19f))
        val rangesScroll=HorizontalScrollView(this); val ranges=row(); rangesScroll.addView(ranges); content.addView(rangesScroll)
        val episodes=column(); content.addView(episodes)
        load("正在加载剧集…",{ repository.detail(series.id) }) { result ->
            detail=result; requestedSeries=result.series
            status.text="${result.episodes.size} 集  ·  ${result.series.tags.ifBlank { "随时续看" }}"
            summary.text=result.series.description
            val last=prefs.getInt("episode:${series.id}",0).coerceIn(0,(result.episodes.size-1).coerceAtLeast(0))
            play.text="▶ 继续第 ${last+1} 集"; play.isEnabled=result.episodes.isNotEmpty()
            play.setOnClickListener { playEpisode(last,prefs.getLong("position:${series.id}",0)) }; play.requestFocus()
            fun render(start:Int) {
                episodes.removeAllViews()
                (start until minOf(start+40,result.episodes.size)).toList().chunked(8).forEach { indices ->
                    val line=row(); episodes.addView(line)
                    indices.forEach { index -> button(line,"${index+1}") { playEpisode(index) }.apply { layoutParams=LinearLayout.LayoutParams(0,dp(42),1f).apply { marginEnd=dp(7); bottomMargin=dp(7) }; background=focusBackground(index==last) } }
                    repeat(8-indices.size) { line.addView(View(this),LinearLayout.LayoutParams(0,1,1f)) }
                }
            }
            result.episodes.indices.step(40).forEach { start -> button(ranges,"${start+1}—${minOf(start+40,result.episodes.size)}") { render(start) } }
            render(last/40*40)
        }
    }
    private fun ensureRegistered() {
        if (registeredThisProcess) return
        var profile = prefs.getString("profile", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (profile == null) {
            profile = JSONObject().put("cdid", UUID.randomUUID().toString()).put("openudid", UUID.randomUUID().toString().replace("-", "").take(16))
                .put("device_type", Build.MODEL).put("device_brand", Build.BRAND).put("os_version", Build.VERSION.RELEASE)
                .put("os_api", Build.VERSION.SDK_INT.toString()).put("rom_version", Build.DISPLAY)
            prefs.edit().putString("profile", profile.toString()).apply()
        }
        val values = linkedMapOf<String, String>()
        profile.keys().forEach { values[it] = profile.optString(it) }
        repository.registerProfile = values
        // Register once per process: keep the server-issued ID and Cookie together in memory.
        registeredThisProcess = repository.registerDevice() != null
        android.util.Log.i("QingjuTV", "device registration: $registeredThisProcess")
    }
    private fun playEpisode(index: Int, start: Long = 0, resume:Boolean=true) {
        val data = detail ?: return
        if (index !in data.episodes.indices) return
        saveProgress(); cancelJob(); releasePlayer(); episode = index; failure = null; quality = ""; playingScreen = true
        screenMode="player"; posters.cancelPage(); root.removeAllViews()
        sourcePortrait=false
        val view=layoutInflater.inflate(R.layout.tv_player,root,false) as PlayerView
        videoView=view; applyFrameMode()
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val hud = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)); background=shape(0xEE101B29.toInt(),radius=0) }
        root.addView(hud, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        controls = hud
        episodeTitle=text("${data.series.title} · 第 ${index + 1} 集",20f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }; hud.addView(episodeTitle)
        status = text("正在准备播放…",14f).apply { setTextColor(muted); maxLines=2 }; stateLabel=status; hud.addView(status)
        val seek=SeekBar(this).apply {
            max=1000; setKeyProgressIncrement(50); contentDescription="播放进度，左右调整"; isFocusable=true
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) {
                    if(fromUser && !draggingProgress) player?.let { if(it.duration>0) it.seekTo(it.duration*value/1000) }
                }
                override fun onStartTrackingTouch(bar:SeekBar?) { draggingProgress=true; showControls() }
                override fun onStopTrackingTouch(bar:SeekBar?) {
                    player?.let { if(it.duration>0) it.seekTo(it.duration*(bar?.progress ?: 0)/1000) }; draggingProgress=false; showControls()
                }
            })
        }
        playbackProgress=seek; hud.addView(seek,LinearLayout.LayoutParams(-1,dp(28)))
        val buttons=row(); hud.addView(buttons)
        previousButton=button(buttons,"上一集") { if(episode>0) playEpisode(episode-1) }.apply { isEnabled=index>0 }
        playButton=button(buttons,"暂停") { togglePlayback() }
        nextButton=button(buttons,"下一集") { nextEpisode() }.apply { isEnabled=index<data.episodes.lastIndex }
        button(buttons,"选集") { episodeDialog() }
        speedButton=button(buttons,"${speed} 倍") { speedDialog() }
        button(buttons,"画面") { frameDialog() }
        button(buttons,"更多") { moreDialog() }
        retryButton=button(buttons,"重新播放") { playEpisode(episode,prefs.getLong("position:${data.series.id}",start)) }.apply { visibility=View.GONE }
        showControls()
        load("正在准备播放…", {
            ensureRegistered()
            val info = repository.stream(data.episodes[index], prefs.getInt("quality", 480))
            val video = RemoteVideo(repository.http, info)
            try { video.prepare(); video } catch (error: Throwable) { video.close(); throw error }
        }) { video ->
            remote = video; queuedVideos[index]=video; quality = video.info.quality
            val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            val engine = ExoPlayer.Builder(this, renderers).setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 15000, 1500, 2500).setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
            player = engine; view.player = engine
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            engine.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size:VideoSize) {
                    if(player!==engine) return
                    sourcePortrait=size.height>size.width
                    applyFrameMode()
                    android.util.Log.i("QingjuTV","video-size ${size.width}x${size.height} portrait=$sourcePortrait surface=TextureView")
                }
                override fun onPlayerError(error: PlaybackException) {
                    if(player!==engine) return
                    failure=explain(error); status.text="这集暂时无法播放，可重试或调整画质。"; retryButton?.visibility=View.VISIBLE; showControls()
                    android.util.Log.w("QingjuTV","player failed: $failure")
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if(player!==engine) return
                    updatePlayback()
                    if(state==Player.STATE_READY) prepareNext(engine)
                    if(state==Player.STATE_ENDED) {
                        saveProgress()
                        if(episode<data.episodes.lastIndex) { status.text="正在准备下一集…"; prepareNext(engine); showControls() }
                        else { status.text="本剧已播放完"; engine.pause(); showControls() }
                    }
                }
                override fun onMediaItemTransition(item:MediaItem?,reason:Int) {
                    if(player!==engine) return
                    val next=item?.mediaId?.toIntOrNull() ?: return
                    val changed=episode!=next
                    episode=next; if(changed) prefetchFailures=0; remote=queuedVideos[next]; quality=remote?.info?.quality.orEmpty(); failure=null
                    episodeTitle?.text="${data.series.title} · 第 ${next+1} 集"
                    previousButton?.isEnabled=next>0; nextButton?.isEnabled=next<data.episodes.lastIndex
                    if(changed) prefs.edit().putInt("episode:${data.series.id}",next).putLong("position:${data.series.id}",0).apply()
                    android.util.Log.i("QingjuTV","queue-transition episode=${next+1} reason=$reason player=${System.identityHashCode(engine)}")
                    main.post {
                        if(player===engine) {
                            val before=engine.currentMediaItemIndex
                            if(before>0) engine.removeMediaItems(0,before)
                            queuedVideos.keys.filter { it<episode }.forEach { old -> queuedVideos.remove(old)?.let { cleanup.execute { it.close() } } }
                            prepareNext(engine)
                        }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if(player===engine) updatePlayback() }
            })
            val source=mediaSource(index,video)
            engine.setMediaSource(source); engine.seekTo(start.coerceAtLeast(0)); engine.setPlaybackSpeed(speed); engine.prepare(); engine.playWhenReady=resume
            session?.isActive = true; showControls(); playButton?.requestFocus(); remember(data.series)
        }
    }
    private fun mediaSource(index:Int,video:RemoteVideo)=ProgressiveMediaSource.Factory(DataSource.Factory { VideoDataSource(video) })
        .createMediaSource(MediaItem.Builder().setUri(video.info.url).setMediaId(index.toString()).build())
    private fun prepareNext(engine:ExoPlayer) {
        val data=detail ?: return
        val next=episode+1
        if(!started || player!==engine || engine.playbackState !in listOf(Player.STATE_READY,Player.STATE_ENDED) || next !in data.episodes.indices || queuedVideos.containsKey(next) || pendingNextIndex==next || prefetchAttempt==next) return
        val seriesId=data.series.id; val selectedQuality=prefs.getInt("quality",480)
        pendingNextIndex=next;prefetchAttempt=next
        prefetch.schedule(seriesId,next,data.episodes[next],selectedQuality,{ index,requestedQuality,video ->
            if(player!==engine || detail?.series?.id!=seriesId || index!=episode+1 || requestedQuality!=prefs.getInt("quality",480) || !started) { video.close(); return@schedule }
            pendingNextIndex=-1;queuedVideos[index]=video
            engine.addMediaSource(mediaSource(index,video))
            android.util.Log.i("QingjuTV","queue-ready episode=${index+1} quality=${video.info.quality} player=${System.identityHashCode(engine)}")
            if(engine.playbackState==Player.STATE_ENDED && engine.hasNextMediaItem()) { val resume=engine.playWhenReady;engine.seekToNextMediaItem();engine.playWhenReady=resume }
        },{ index,error ->
            if(player===engine && pendingNextIndex==index) {
                pendingNextIndex=-1;prefetchFailures++
                if(prefetchFailures<2) main.postDelayed({ if(player===engine && episode+1==index && started && !queuedVideos.containsKey(index)) { prefetchAttempt=-1;prepareNext(engine) } },10000)
                android.util.Log.w("QingjuTV","next-prefetch failed: ${error.javaClass.simpleName}")
                if(engine.playbackState==Player.STATE_ENDED) { status.text="下一集未准备好，请按下一集重试。";showControls();nextButton?.requestFocus() }
            }
        })
    }
    private fun releasePlayer() {
        prefetch.cancel();pendingNextIndex=-1;prefetchAttempt=-1;prefetchFailures=0
        queuedVideos.values.forEach { it.close() };queuedVideos.clear();remote?.close();remote=null
        player?.let { saveProgress(); player=null; videoView?.player=null; it.release() }
        session?.isActive = false
        controls=null;stateLabel=null;speedButton=null;videoView=null;playbackProgress=null;retryButton=null;playButton=null;episodeTitle=null;previousButton=null;nextButton=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun togglePlayback() {
        player?.let { if(it.playWhenReady) it.pause() else it.play() }
        showControls();playButton?.requestFocus();saveProgress();updatePlayback()
    }
    private fun showControls() {
        if (!playingScreen) return
        controls?.visibility = View.VISIBLE; overlayUntil = SystemClock.elapsedRealtime() + 6000
    }
    private fun updatePlayback() {
        val engine = player ?: return
        playButton?.text=if(engine.playWhenReady) "暂停" else "继续"
        if (!draggingProgress && engine.duration>0) playbackProgress?.progress=(engine.currentPosition*1000/engine.duration).toInt().coerceIn(0,1000)
        if (failure == null) {
            val state = when(engine.playbackState) { Player.STATE_BUFFERING -> "缓冲中"; Player.STATE_READY -> if (engine.isPlaying) "播放中" else "已暂停"; Player.STATE_ENDED -> "已结束"; else -> "正在准备" }
            stateLabel?.text = "$state   ${time(engine.currentPosition)} / ${time(engine.duration)}   ·   $quality"
        }
        if (engine.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (engine.isPlaying) PlaybackState.STATE_PLAYING else if (engine.playbackState == Player.STATE_BUFFERING) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED, engine.currentPosition.coerceAtLeast(0), if (engine.isPlaying) engine.playbackParameters.speed else 0f).build())
        if (SystemClock.elapsedRealtime() - lastLog > 10000) {
            lastLog = SystemClock.elapsedRealtime(); saveProgress()
            android.util.Log.i("QingjuTV", "playback state=${engine.playbackState} playing=${engine.isPlaying} position=${engine.currentPosition} duration=${engine.duration} speed=${engine.playbackParameters.speed}")
        }
    }
    private fun time(ms: Long): String { val n = ms.coerceAtLeast(0) / 1000; return "%02d:%02d".format(n / 60, n % 60) }
    private fun saveProgress() {
        val data = detail ?: return; val engine = player ?: return
        if (engine.playbackState != Player.STATE_READY && engine.playbackState != Player.STATE_ENDED) return
        prefs.edit().putInt("episode:${data.series.id}", episode).putLong("position:${data.series.id}", engine.currentPosition.coerceAtLeast(0)).apply()
    }
    private fun remember(series: Series) {
        prefs.edit().putString("last-series",series.toJson().toString()).apply()
        storeList("history",listOf(series)+storedList("history").filterNot { it.id==series.id })
    }
    private fun nextEpisode() {
        val count=detail?.episodes?.size ?: return
        if(episode+1<count) {
            val engine=player
            if(engine!=null && engine.hasNextMediaItem()) { saveProgress(); engine.seekToNextMediaItem();engine.play() }
            else playEpisode(episode+1)
        } else { player?.pause();showControls();status.text="本剧已播放完" }
    }
    private fun speedDialog() {
        val options=floatArrayOf(0.75f,1f,1.25f,1.5f,1.75f,2f)
        showDialog(AlertDialog.Builder(this).setTitle("播放速度").setSingleChoiceItems(options.map { "${it} 倍" }.toTypedArray(),options.indexOfFirst { it==speed }) { shown,index ->
            speed=options[index]; prefs.edit().putFloat("speed",speed).apply(); player?.setPlaybackSpeed(speed); speedButton?.text="${speed} 倍"
            updatePlayback(); shown.dismiss()
        }.setNegativeButton("取消",null),speedButton)
    }
    private fun qualityDialog() {
        val values=intArrayOf(480,720,1080)
        showDialog(AlertDialog.Builder(this).setTitle("清晰度").setSingleChoiceItems(arrayOf("480P · 流畅优先","720P · 高清","1080P · 全高清，取片源可用上限"),values.indexOf(prefs.getInt("quality",480))) { shown,index ->
            val pos=player?.currentPosition?.coerceAtLeast(0) ?: 0; val resume=player?.playWhenReady ?: true; prefs.edit().putInt("quality",values[index]).apply(); shown.dismiss(); playEpisode(episode,pos,resume)
        }.setNegativeButton("取消",null))
    }
    private fun episodeDialog() {
        val data=detail ?: return
        showDialog(AlertDialog.Builder(this).setTitle("选择集数").setSingleChoiceItems(data.episodes.indices.map { "第 ${it+1} 集" }.toTypedArray(),episode) { shown,index -> shown.dismiss(); playEpisode(index) }.setNegativeButton("取消",null))
    }
    private fun applyFrameMode() {
        val view=videoView ?: return
        val mode=prefs.getInt("frame-mode",0)
        view.resizeMode=when(mode) {
            4 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val scale=when(mode) { 5->1.25f;6->1.5f;else->1f }
        view.videoSurfaceView?.apply { scaleX=scale;scaleY=scale }
        view.post {
            val surface=view.videoSurfaceView
            android.util.Log.i("QingjuTV","frame mode=$mode viewport=${view.width}x${view.height} surface=${surface?.width}x${surface?.height} scale=$scale")
        }
    }
    private fun frameDialog() {
        val values=intArrayOf(0,4,3,5,6)
        val size=player?.videoSize
        val note=if(size!=null && size.width>0) " · ${size.width}×${size.height}" else ""
        showDialog(AlertDialog.Builder(this).setTitle("画面比例$note")
            .setSingleChoiceItems(arrayOf("原比例 · 完整画面","铺满屏幕 · 裁掉部分画面","拉伸全屏 · 画面可能变形","放大125% · 裁剪四周","放大150% · 裁剪更多"),values.indexOf(prefs.getInt("frame-mode",0))) { shown,index ->
                prefs.edit().putInt("frame-mode",values[index]).apply();applyFrameMode();shown.dismiss()
            }.setNegativeButton("取消",null))
    }
    private fun settingsDialog() {
        showDialog(AlertDialog.Builder(this).setTitle("青聚设置").setItems(arrayOf("默认播放速度","默认画面比例","关于青聚")) { _,index -> when(index) { 0->speedDialog();1->frameDialog();2->about() } }.setNegativeButton("关闭",null))
    }
    private fun moreDialog() {
        val data=detail ?: return
        val labels=mutableListOf("清晰度","画面比例",if(isFavorite(data.series.id)) "取消收藏" else "收藏这部剧","返回剧集详情")
        if(failure!=null) labels.add("查看错误详情")
        showDialog(AlertDialog.Builder(this).setTitle("播放选项").setItems(labels.toTypedArray()) { _,index ->
            when(index) {
                0 -> qualityDialog()
                1 -> frameDialog()
                2 -> { val added=toggleFavorite(data.series);Toast.makeText(this,if(added) "已加入收藏" else "已取消收藏",Toast.LENGTH_SHORT).show() }
                3 -> showDetail(data.series)
                4 -> showDialog(AlertDialog.Builder(this).setTitle("错误详情").setMessage(failure).setPositiveButton("知道了",null))
            }
        }.setNegativeButton("关闭",null))
    }
    private fun about() {
        showDialog(AlertDialog.Builder(this).setTitle("青聚 · 0.4.0").setMessage("""为电视遥控器设计的短剧客户端。

支持收藏、观看历史、续播与倍速。确认键暂停/继续；菜单键打开控制栏，左右选择倍速、画面等操作。

本应用非红果官方客户端。内容接口基于HGTV / N3urda（GPL-3.0-only），播放器Media3（Apache-2.0）。完整对应源码和许可证随安装包提供。

无广告SDK、无账号登录、无后台常驻服务。""").setPositiveButton("知道了",null))
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        val key=event.keyCode
        if(event.action==KeyEvent.ACTION_UP && consumedRemoteKey==key) { consumedRemoteKey=-1;return true }
        if(event.action==KeyEvent.ACTION_DOWN && consumedRemoteKey==key) { overlayUntil=SystemClock.elapsedRealtime()+6000;return true }
        if(playingScreen && dialog?.isShowing!=true && event.action==KeyEvent.ACTION_DOWN) {
            overlayUntil=SystemClock.elapsedRealtime()+6000
            fun consume(action:()->Unit):Boolean { consumedRemoteKey=key;if(event.repeatCount==0) action();return true }
            when(key) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY -> return consume {
                    when(key) { KeyEvent.KEYCODE_MEDIA_PAUSE->player?.pause();KeyEvent.KEYCODE_MEDIA_PLAY->player?.play();else->togglePlayback() }
                    showControls();playButton?.requestFocus();updatePlayback()
                }
                KeyEvent.KEYCODE_MENU -> return consume { showControls();playButton?.requestFocus() }
            }
            if(controls?.visibility!=View.VISIBLE) {
                when(key) {
                    KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN -> return consume { showControls();playButton?.requestFocus() }
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> return consume {
                        player?.let { it.seekTo((it.currentPosition+if(key==KeyEvent.KEYCODE_DPAD_RIGHT) 10000 else -10000).coerceIn(0,(it.duration-500).coerceAtLeast(0))) }
                        showControls();playButton?.requestFocus()
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> return consume { togglePlayback() }
                }
            } else if((key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) && currentFocus !is Button) {
                return consume { togglePlayback() }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Android legacy back")
    override fun onBackPressed() {
        if (playingScreen) { val series = detail?.series; if (series != null) showDetail(series) else showHome() }
        else if(screenMode=="detail") restoreBrowse()
        else if(screenMode!="home" || query.isNotBlank()) { query="";page=1;showHome() }
        else super.onBackPressed()
    }
}
