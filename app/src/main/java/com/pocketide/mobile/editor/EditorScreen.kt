package com.pocketide.mobile.editor

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketide.mobile.ui.components.MilestoneBadge
import com.pocketide.mobile.ui.theme.PocketMonoFont

/**
 * Real editor surface (M2): the pinned Monaco 0.57.0 release in a WebView
 * served exclusively from APK assets through WebViewAssetLoader, driven by
 * the validated [MonacoHostEngine] bridge with:
 *  - a per-project document store ([EditorViewModel] / [MonacoEditorStore]);
 *  - the dedicated IME bridge recommended by decision 0002 for the known
 *    WebView keyboard risk (a hidden driver surface relaying IME edits
 *    into the model via window snapshots, plus a symbol/grid toolbar);
 *  - tap-to-open-keyboard on the editor canvas (WebView keyboard cannot be
 *    relied on; the driver keeps a 1dp alpha surface that can request
 *    focus).
 *
 * No editing content is simulated: what the WebView shows is Monaco's own
 * model state.
 */
@Composable
fun EditorScreen(
    projectName: String?,
    viewModel: EditorViewModel
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(projectName) {
        viewModel.openProject(projectName)
    }

    val windowState = remember { mutableStateOf<MonacoImeWindow?>(null) }
    val lastWindowSnapshot = remember { mutableStateOf<MonacoImeWindow?>(null) }
    val engine = remember {
        MonacoHostEngine(
            callbacks = object : MonacoHostEngine.Callbacks {
                override fun onReady() {
                    // The document push happens below from the current
                    // [MonacoDocument]; nothing else to record here.
                }

                override fun onContentLoaded(content: String, language: String) {
                    viewModel.onContentLoaded(content, language)
                }

                override fun onImeWindow(window: MonacoImeWindow) {
                    // Text-bearing changes mark the document dirty; pure
                    // cursor moves do not. The first snapshot is the load
                    // baseline, not an edit.
                    val previous = lastWindowSnapshot.value
                    if (previous != null && previous.text != window.text) {
                        viewModel.markEdited()
                    }
                    lastWindowSnapshot.value = window
                    windowState.value = window
                }

                override fun onEngineError(detail: String) {
                    viewModel.onEngineError(detail)
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { engine.detach() }
    }

    // Push the loaded (or empty) document whenever it changes; the engine
    // deduplicates against what it already pushed.
    if (state is EditorUiState.Ready) {
        val readyState = state as EditorUiState.Ready
        LaunchedEffect(readyState.projectName) {
            engine.pushDocument(readyState.document)
        }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val showKeyboard: () -> Unit = {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    val hideKeyboard: () -> Unit = {
        keyboardController?.hide()
    }
    val saveAction: () -> Unit = {
        if (viewModel.requestSave()) {
            engine.pullContent()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BreadcrumbBar(projectName = projectName)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        KeyboardAccessoryBar(
            enabled = state is EditorUiState.Ready,
            onInsert = engine::insertText,
            onCursor = engine::moveCursor,
            onTab = engine::insertTab,
            onEscape = {
                engine.escape()
                hideKeyboard()
            },
            onKeyboard = showKeyboard,
            onSave = saveAction,
            dirty = (state as? EditorUiState.Ready)?.dirtyWithinSession == true
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val webview = engine.createWebView(ctx)
                    installTapDetector(webview, ctx) { showKeyboard() }
                    webview
                }
            )
        }
        StatusFooter(state)
        ImeDriver(
            window = windowState,
            focusRequester = focusRequester,
            engine = engine
        )
    }
}

/**
 * Records touch pairs to detect taps on the editor canvas without
 * consuming them (the callback fires and returns false, so Monaco still
 * receives the touch). The WebView keyboard cannot be relied on for open
 * (Monaco issue 4946), so taps explicitly open the IME driver.
 */
private fun installTapDetector(
    webview: WebView,
    context: Context,
    onTap: () -> Unit
) {
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var downTime: Long = 0
    var downX = 0f
    var downY = 0f
    var tracking = false
    webview.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                tracking = true
            }

            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    val moved = Math.hypot(
                        (event.x - downX).toDouble(),
                        (event.y - downY).toDouble()
                    )
                    val elapsed = event.eventTime - downTime
                    if (moved <= touchSlop && elapsed < 400) {
                        onTap()
                    }
                    tracking = false
                }
            }

            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        false // never consume: the WebView content keeps its own gestures
    }
}

@Composable
private fun BreadcrumbBar(projectName: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = projectName ?: "no project selected",
            fontFamily = PocketMonoFont,
            style = MaterialTheme.typography.labelLarge,
            color = if (projectName == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (projectName == null) "" else "/ scratch",
            fontFamily = PocketMonoFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        MilestoneBadge("in-app store · real files M3")
    }
}

/**
 * Active accessory toolbar (M2): Save, dirty indicator, keyboard toggle,
 * Esc/Tab, cursor arrows and symbol insertion — every key is backed by a
 * real engine command, never static decoration.
 */
@Composable
private fun KeyboardAccessoryBar(
    enabled: Boolean,
    onInsert: (String) -> Unit,
    onCursor: (String) -> Unit,
    onTab: () -> Unit,
    onEscape: () -> Unit,
    onKeyboard: () -> Unit,
    onSave: () -> Unit,
    dirty: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Save",
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = enabled, onClick = onSave)
            )
            Text(
                text = if (dirty) "● unsaved" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "⌨",
                fontFamily = PocketMonoFont,
                modifier = Modifier.clickable(enabled = enabled, onClick = onKeyboard)
            )
            Text(
                text = "Esc",
                fontFamily = PocketMonoFont,
                modifier = Modifier.clickable(enabled = enabled, onClick = onEscape)
            )
            Text(
                text = "Tab",
                fontFamily = PocketMonoFont,
                modifier = Modifier.clickable(enabled = enabled, onClick = onTab)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "←",
                fontFamily = PocketMonoFont,
                modifier = Modifier.clickable(enabled = enabled, onClick = { onCursor("left") })
            )
            Text(
                text = "↑",
                fontFamily = PocketMonoFont,
                modifier = Modifier.clickable(enabled = enabled, onClick = { onCursor("up") })
            )
            Text(
                text = "↓",
                fontFamily = PocketMonoFont,
                modifier = Modifier.clickable(enabled = enabled, onClick = { onCursor("down") })
            )
            Text(
                text = "→",
                fontFamily = PocketMonoFont,
                modifier = Modifier.clickable(enabled = enabled, onClick = { onCursor("right") })
            )
            accessorySymbols.forEach { symbol ->
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 34.dp, height = 30.dp)
                            .clickable(enabled = enabled, onClick = { onInsert(symbol) }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = symbol,
                            fontFamily = PocketMonoFont,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        Text(
            text = "IME bridge + symbols drive the Monaco model; nothing is simulated.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

private val accessorySymbols = listOf("{", "}", "(", ")", "[", "]", "<", ">", ";", "=", "|")

/**
 * The hidden IME driver surface (decision 0002). The soft keyboard edits
 * the current Monaco window slice; every user-driven change is converted
 * to a model-anchored edit via [MonacoImeWindows.extractWindowEdit] and
 * applied by the engine. Fresh windows pushed by the harness reset this
 * surface: the driver follows the model and never leads it.
 */
@Composable
private fun ImeDriver(
    window: State<MonacoImeWindow?>,
    focusRequester: FocusRequester,
    engine: MonacoHostEngine
) {
    var driverText by remember { mutableStateOf("") }

    LaunchedEffect(window.value) {
        driverText = window.value?.text ?: ""
    }

        BasicTextField(
            value = driverText,
            onValueChange = { next ->
                val baseline = window.value
                driverText = next
                if (baseline == null || next == baseline.text) return@BasicTextField
                val edit = MonacoImeWindows.extractWindowEdit(
                    windowBefore = baseline.text,
                    windowAfter = next,
                    windowStartOffset = baseline.windowStartOffset
                )
                if (edit != null) {
                    engine.applyImeWindowEdit(edit)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .alpha(0.05f)
                .focusRequester(focusRequester)
        )
}

@Composable
private fun StatusFooter(state: EditorUiState) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = when (state) {
                EditorUiState.NoProject ->
                    "Engine: Monaco 0.57.0 · open a project from Home first"
                is EditorUiState.Ready -> buildString {
                    append("Engine: Monaco 0.57.0 · document: ")
                    append(state.projectName)
                    if (state.dirtyWithinSession) append(" (unsaved)")
                    if (!state.loadFailed.isNullOrEmpty()) {
                        append(" · load: ")
                        append(state.loadFailed)
                    }
                    if (!state.saveMessage.isNullOrEmpty()) {
                        append(" · ")
                        append(state.saveMessage)
                    }
                }
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = PocketMonoFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
