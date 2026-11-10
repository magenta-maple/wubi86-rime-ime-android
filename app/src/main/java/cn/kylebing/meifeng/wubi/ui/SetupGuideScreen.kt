package cn.kylebing.meifeng.wubi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.key
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.kylebing.meifeng.wubi.R
import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.KeyboardPrefs
import cn.kylebing.meifeng.wubi.data.PunctuationPrefs
import cn.kylebing.meifeng.wubi.data.RimeConfigDeploy
import cn.kylebing.meifeng.wubi.data.RimeResourceDeployer
import cn.kylebing.meifeng.wubi.data.UserDictWriter
import cn.kylebing.meifeng.wubi.lan.RimeFileSyncServer
import cn.kylebing.meifeng.wubi.sync.CloudException
import cn.kylebing.meifeng.wubi.sync.WubiCloudApi
import cn.kylebing.meifeng.wubi.sync.WubiCloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Section(val title: String, val icon: ImageVector) {
    Enable("启用键盘", Icons.Outlined.Keyboard),
    Look("外观触感", Icons.Outlined.Palette),
    Input("输入习惯", Icons.Outlined.Tune),
    Lexicon("词库管理", Icons.Outlined.Book),
    Account("账户同步", Icons.Outlined.Cloud),
    Scheme("方案", Icons.Outlined.GridView)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideScreen() {
    var current by remember { mutableStateOf<Section?>(null) }
    var nestedTitle by remember { mutableStateOf<String?>(null) }
    val navBack = remember { NavBack() }
    val syncController = remember { cn.kylebing.meifeng.wubi.sync.CloudDictSyncController() }
    val context = LocalContext.current
    val version = try {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        "${p.versionName} ($code)"
    } catch (_: Exception) { "1.0.0" }

    // 子页返回 true 表示已处理；否则退回首页
    BackHandler(enabled = current != null) {
        if (navBack.pop?.invoke() == true) return@BackHandler
        nestedTitle = null
        current = null
    }

    Scaffold(containerColor = settingsPageColor(), topBar = {
        // 首页内容里已有标题，不再叠一层顶栏标识
        if (current != null) {
            TopAppBar(title = {
                Text(nestedTitle ?: current?.title ?: "")
            }, navigationIcon = {
                FilledTonalIconButton(
                    onClick = {
                        if (navBack.pop?.invoke() == true) return@FilledTonalIconButton
                        nestedTitle = null
                        current = null
                    },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "返回")
                }
            })
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (current) {
                null -> HomeList(version) { current = it }
                Section.Enable -> EnablePage()
                Section.Look -> LookPage()
                Section.Input -> InputPage()
                Section.Lexicon -> LexiconHost(navBack, onTitle = { nestedTitle = it })
                Section.Account -> AccountHost(syncController, navBack, onTitle = { nestedTitle = it })
                Section.Scheme -> SchemePage()
            }
        }
    }
}

@Composable
private fun HomeList(version: String, open: (Section) -> Unit) {
    var test by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painterResource(R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("玫枫五笔", style = MaterialTheme.typography.titleMedium)
                    // 副标题与 iOS 一致，版本号紧挨在下方小字
                    Text("Rime 五笔输入法", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SettingsCard(title = "输入测试") {
                OutlinedTextField(
                    value = test,
                    onValueChange = { test = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("在此输入以测试键盘…") },
                    minLines = 3
                )
                TextButton(onClick = { test = "" }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("清空") }
                Text(
                    "点输入框后，在系统键盘列表选「玫枫五笔」。",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SettingsCard(title = "设置") {
                Section.entries.forEachIndexed { index, s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { open(s) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(s.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(s.title, Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (index < Section.entries.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 52.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EnablePage() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        SettingsCard(title = "启用") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. 打开系统「语言和输入法」，启用「玫枫五笔」。")
                Text("2. 在任意输入框长按地球键或空格，选「玫枫五笔」。")
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }) { Text("打开输入法设置") }
                TextButton(onClick = {
                    val imm = context.getSystemService(InputMethodManager::class.java)
                    imm.showInputMethodPicker()
                }) { Text("选择当前输入法") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookPage() {
    var haptic by remember { mutableStateOf(KeyboardPrefs.hapticEnabled) }
    var level by remember { mutableIntStateOf(KeyboardPrefs.hapticIntensityLevel) }
    var hold by remember { mutableIntStateOf(KeyboardPrefs.keyPressHoldMillis) }
    var del by remember { mutableIntStateOf(KeyboardPrefs.deleteRepeatLevel) }
    var hdr by remember { mutableStateOf(KeyboardPrefs.hdrKeyPressEnabled) }
    var center by remember { mutableStateOf(KeyboardPrefs.centerCandidatesOnWideScreen) }
    var accent by remember { mutableStateOf(KeyboardPrefs.accentColorHex) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SettingsCard(title = "外观") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("候选居中显示")
                    Switch(center, {
                        center = it
                        KeyboardPrefs.centerCandidatesOnWideScreen = it
                    })
                }
                Text(
                    "大宽屏上候选不满一栏时居中显示。",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SettingsCard(title = "触感") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("按键震动")
                    Switch(haptic, {
                        haptic = it
                        KeyboardPrefs.hapticEnabled = it
                    })
                }
                Text("震动力度：${KeyboardPrefs.hapticTitle(level)}", Modifier.padding(horizontal = 16.dp))
                Slider(
                    value = level.toFloat(),
                    onValueChange = {
                        level = it.toInt()
                        KeyboardPrefs.hapticIntensityLevel = level
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                    enabled = haptic,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)
                )
            }
        }
        item {
            SettingsCard(title = "按键反馈") {
                Text("按键颜色", Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
                // 窄屏一行排不下，自动换行，避免色块被挤扁
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    KeyboardPrefs.accentPresets.forEach { hex ->
                        val selected = accent.equals(hex, true)
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor("#$hex")))
                                .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable {
                                    accent = hex
                                    KeyboardPrefs.accentColorHex = hex
                                }
                        )
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("HDR 按下高亮")
                    Switch(hdr, {
                        hdr = it
                        KeyboardPrefs.hdrKeyPressEnabled = it
                    })
                }
                Text("按下态保留：${if (hold <= 0) "关闭" else "$hold 毫秒"}", Modifier.padding(horizontal = 16.dp))
                Slider(
                    value = hold.toFloat(),
                    onValueChange = {
                        hold = (it / 20).toInt() * 20
                        KeyboardPrefs.keyPressHoldMillis = hold
                    },
                    valueRange = 0f..400f,
                    steps = 19,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Text("回删连删：${KeyboardPrefs.deleteRepeatTitle(del)}", Modifier.padding(horizontal = 16.dp))
                Slider(
                    value = del.toFloat(),
                    onValueChange = {
                        del = it.toInt()
                        KeyboardPrefs.deleteRepeatLevel = del
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun InputPage() {
    var comment by remember { mutableStateOf(KeyboardPrefs.showCommentInPanel) }
    var uniqueFour by remember { mutableStateOf(KeyboardPrefs.autoSelectUniqueFourCode) }
    var empty by remember { mutableStateOf(KeyboardPrefs.clearEmptyCode) }
    var enter by remember { mutableStateOf(KeyboardPrefs.enterClearsCode) }
    var close by remember { mutableStateOf(KeyboardPrefs.closesSymbolPageAfterInput) }
    var rev by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SettingsCard(title = "五笔打码") {
                ToggleRow("显示候选备注", comment) { comment = it; KeyboardPrefs.showCommentInPanel = it }
                HorizontalDivider(Modifier.padding(start = 16.dp))
                ToggleRow("四码唯一上屏", uniqueFour) { uniqueFour = it; KeyboardPrefs.autoSelectUniqueFourCode = it }
                HorizontalDivider(Modifier.padding(start = 16.dp))
                ToggleRow("空码清空", empty) { empty = it; KeyboardPrefs.clearEmptyCode = it }
                HorizontalDivider(Modifier.padding(start = 16.dp))
                ToggleRow("回车清码", enter) { enter = it; KeyboardPrefs.enterClearsCode = it }
            }
        }
        item {
            SettingsCard(title = "符号键盘") {
                ToggleRow("点数字或标点后关闭符号键盘", close) { close = it; KeyboardPrefs.closesSymbolPageAfterInput = it }
            }
        }
        item {
            SettingsCard(title = "标点符号") {
                TextButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("恢复默认") }
                PunctuationPrefs.items.forEach { item ->
                    key(item.key + rev) { PunctRow(item) }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("恢复默认标点？") },
            text = { Text("输出内容和输出方式都会回到默认。") },
            confirmButton = {
                TextButton(onClick = {
                    PunctuationPrefs.restoreDefaults()
                    rev++
                    confirm = false
                }) { Text("恢复默认") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PunctRow(item: PunctuationPrefs.Item) {
    var draft by remember { mutableStateOf(PunctuationPrefs.symbols(item.key).joinToString(" ")) }
    var mode by remember { mutableStateOf(PunctuationPrefs.mode(item.key)) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.key, Modifier.width(28.dp), fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    PunctuationPrefs.setSymbols(PunctuationPrefs.parseSymbols(it), item.key)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("输出，多项用空格分开") }
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 4.dp).fillMaxWidth()) {
            PunctuationPrefs.Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = {
                        mode = m
                        PunctuationPrefs.setMode(m, item.key)
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)
                ) { Text(m.title) }
            }
        }
    }
}

class NavBack {
    var pop: (() -> Boolean)? = null
}

@Composable
fun LanPage() {
    val context = LocalContext.current
    val view = LocalView.current
    var running by remember { mutableStateOf(false) }
    var ips by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var deploying by remember { mutableStateOf(false) }
    var deployMsg by remember { mutableStateOf<String?>(null) }
    val port = 8765

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val server = RimeFileSyncServer(port) { r, list, err ->
            running = r
            ips = list
            error = err
        }
        server.start()
        onDispose {
            view.keepScreenOn = false
            server.stop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsCard(title = "部署") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
            onClick = {
                deploying = true
                deployMsg = null
                Thread {
                    val result = RimeConfigDeploy.deployNow()
                    view.post {
                        deploying = false
                        deployMsg = if (result.isSuccess) "部署成功，打开键盘后生效"
                        else result.exceptionOrNull()?.message
                    }
                }.start()
            },
            enabled = !deploying
                ) { Text(if (deploying) "正在部署…" else "部署当前配置") }
                deployMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        SettingsCard(title = "电脑浏览器打开") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
            !error.isNullOrEmpty() -> Text(error!!, color = MaterialTheme.colorScheme.error)
            ips.isEmpty() -> Text("未检测到局域网 IP，请确认已连 Wi‑Fi", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> ips.forEach { ip ->
                val url = "http://$ip:$port/"
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(url, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", url))
                        copied = true
                    }) { Text(if (copied) "已复制" else "复制链接") }
                }
            }
        }
                Text(
                    "手机与电脑同一 Wi‑Fi，保持本页打开即可管理配置文件；离开本页服务会关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SettingsCard(title = "本机目录") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppPaths.userDir.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (running) Text("服务已启动", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SchemePage() {
    val context = LocalContext.current
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SettingsCard(title = "当前方案") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("方案 wubi86_jidian（无 Lua）")
                Text("词库目录：${cn.kylebing.meifeng.wubi.data.AppPaths.userDir.path}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirm = true }) { Text("复位原始配置") }
                if (msg.isNotEmpty()) Text(msg)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("复位原始配置") },
            text = { Text("将清空当前配置与用户词，恢复内置方案。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    msg = "正在恢复…"
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    Thread {
                        val result = runCatching { RimeResourceDeployer.resetToFactory(context) }
                        main.post {
                            msg = if (result.isSuccess) "已恢复内置方案" else (result.exceptionOrNull()?.message ?: "恢复失败")
                        }
                    }.start()
                }) { Text("清空并恢复") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}
