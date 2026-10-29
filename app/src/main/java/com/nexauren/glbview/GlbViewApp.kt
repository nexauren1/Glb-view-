package com.nexauren.glbview

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberModelInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class UiLanguage { PT, EN }
private enum class AppScreen { LIBRARY, VIEWER, TOOLS }

private val pageBackground = Color(0xFF0B1020)
private val panelBackground = Color(0xFF141C2F)
private val mutedText = Color(0xFF9BA9C4)
private val accentBlue = Color(0xFF78A6FF)
private val accentMint = Color(0xFF55E0C1)
private val warningAmber = Color(0xFFFFC36C)

private fun UiLanguage.text(portuguese: String, english: String): String =
    if (this == UiLanguage.PT) portuguese else english

@Composable
fun GlbViewApp(initialUris: List<Uri>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var language by remember {
        mutableStateOf(if (loadLanguage(context) == "en") UiLanguage.EN else UiLanguage.PT)
    }
    var files by remember { mutableStateOf(loadSavedLibrary(context)) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(files.firstOrNull()?.uri) }
    var selectedUris by remember { mutableStateOf(emptySet<String>()) }
    var screen by rememberSaveable { mutableStateOf(AppScreen.LIBRARY) }
    var search by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var reports by remember { mutableStateOf(emptyList<InspectionResult>()) }

    val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            scope.launch {
                busy = true
                status = language.text("A importar ficheiros…", "Importing files…")
                val imported = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> readModelFileBlocking(context, uri) }
                }
                files = mergeModelFiles(files, imported)
                if (selectedUri == null && imported.isNotEmpty()) selectedUri = imported.first().uri
                selectedUris = selectedUris + imported.map { it.uri }
                status = if (imported.isEmpty()) {
                    language.text("Nenhum ficheiro GLB/glTF válido foi encontrado.", "No supported GLB/glTF files were found.")
                } else {
                    language.text("Importados: ", "Imported: ") + imported.size
                }
                busy = false
            }
        }
    }

    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                busy = true
                status = language.text("A pesquisar a pasta…", "Scanning folder…")
                val discovered = scanModelFolder(context, treeUri)
                files = mergeModelFiles(files, discovered)
                selectedUris = selectedUris + discovered.map { it.uri }
                if (selectedUri == null && discovered.isNotEmpty()) selectedUri = discovered.first().uri
                status = language.text("Encontrados: ", "Found: ") + discovered.size +
                    language.text(" modelos", " models")
                busy = false
            }
        }
    }

    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { destination ->
        if (destination != null) {
            val snapshot = reports
            scope.launch {
                busy = true
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(inspectionResultsToCsv(snapshot))
                        } ?: error("Unable to open destination")
                    }.isSuccess
                }
                status = if (saved) language.text("Relatório CSV guardado.", "CSV report saved.")
                else language.text("Não foi possível guardar o relatório.", "Could not save the report.")
                busy = false
            }
        }
    }

    LaunchedEffect(files) {
        saveLibrary(context, files)
        val available = files.map { it.uri }.toSet()
        selectedUris = selectedUris.intersect(available)
        if (selectedUri != null && selectedUri !in available) selectedUri = files.firstOrNull()?.uri
    }

    LaunchedEffect(initialUris) {
        if (initialUris.isNotEmpty()) {
            initialUris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            busy = true
            val imported = withContext(Dispatchers.IO) {
                initialUris.mapNotNull { uri -> readModelFileBlocking(context, uri) }
            }
            files = mergeModelFiles(files, imported)
            if (imported.isNotEmpty()) {
                selectedUri = imported.first().uri
                screen = AppScreen.VIEWER
                status = language.text("Modelo recebido.", "Model received.")
            } else {
                status = language.text("O ficheiro recebido não é GLB/glTF.", "The received file is not GLB/glTF.")
            }
            busy = false
        }
    }

    val selectedModel = files.firstOrNull { it.uri == selectedUri }
    val selectedModels = files.filter { it.uri in selectedUris }

    fun shareModels(models: List<ModelFile>) {
        if (models.isEmpty()) {
            status = language.text("Seleciona pelo menos um modelo.", "Select at least one model.")
            return
        }
        runCatching {
            val uris = models.map { Uri.parse(it.uri) }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                val streams = ArrayList<Parcelable>().apply { addAll(uris) }
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clipData = ClipData.newUri(context.contentResolver, models.first().name, uris.first())
            uris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }
            intent.clipData = clipData
            context.startActivity(Intent.createChooser(intent, language.text("Partilhar modelos", "Share models")))
        }.onFailure {
            status = language.text("Não foi possível partilhar os ficheiros.", "Unable to share these files.")
        }
    }

    fun inspectSelected() {
        val targets = if (selectedUris.isEmpty()) files else files.filter { it.uri in selectedUris }
        if (targets.isEmpty()) {
            status = language.text("Importa primeiro alguns modelos.", "Import some models first.")
            return
        }
        scope.launch {
            busy = true
            status = language.text("A validar os modelos…", "Validating models…")
            reports = targets.map { inspectModel(context, it) }
            status = language.text("Válidos: ", "Valid: ") + reports.count { it.valid && it.inspected } +
                language.text(" em ", " of ") + reports.count { it.inspected} +
                language.text(" ficheiros verificados", " inspected files")
            busy = false
            screen = AppScreen.TOOLS
        }
    }

    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = accentBlue,
            secondary = accentMint,
            background = pageBackground,
            surface = panelBackground,
            onPrimary = pageBackground,
            onBackground = Color(0xFFF3F6FF),
            onSurface = Color(0xFFF3F6FF)
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = pageBackground) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(
                    language = language,
                    onToggleLanguage = {
                        language = if (language == UiLanguage.PT) UiLanguage.EN else UiLanguage.PT
                        saveLanguage(context, if (language == UiLanguage.PT) "pt" else "en")
                    },
                    onOpenFiles = { openFiles.launch(arrayOf("*/*")) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(screen == AppScreen.LIBRARY, { screen = AppScreen.LIBRARY }, label = { Text(language.text("Biblioteca", "Library")) })
                    FilterChip(screen == AppScreen.VIEWER, { screen = AppScreen.VIEWER }, label = { Text(language.text("Visualizador 3D", "3D Viewer")) })
                    FilterChip(screen == AppScreen.TOOLS, { screen = AppScreen.TOOLS }, label = { Text(language.text("Lote e ferramentas", "Batch & tools")) })
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        AppScreen.LIBRARY -> LibraryScreen(
                            language = language,
                            files = files,
                            selectedUris = selectedUris,
                            selectedUri = selectedUri,
                            search = search,
                            onSearchChange = { search = it },
                            onToggle = { model, checked ->
                                selectedUris = if (checked) selectedUris + model.uri else selectedUris - model.uri
                            },
                            onOpen = { model -> selectedUri = model.uri; screen = AppScreen.VIEWER },
                            onPickFiles = { openFiles.launch(arrayOf("*/*")) },
                            onPickFolder = { openFolder.launch(null) },
                            onShareSelected = { shareModels(selectedModels) },
                            onRemoveSelected = {
                                files = files.filterNot { it.uri in selectedUris }
                                if (selectedUri != null && selectedUri in selectedUris) selectedUri = null
                                selectedUris = emptySet()
                                status = language.text("Seleção removida da biblioteca.", "Selection removed from library.")
                            },
                            onInspect = { inspectSelected() },
                            busy = busy
                        )
                        AppScreen.VIEWER -> ViewerScreen(
                            language = language,
                            model = selectedModel,
                            onBackToLibrary = { screen = AppScreen.LIBRARY },
                            onPickModel = { openFiles.launch(arrayOf("*/*")) },
                            onShare = { model -> shareModels(listOf(model)) }
                        )
                        AppScreen.TOOLS -> ToolsScreen(
                            language = language,
                            libraryCount = files.size,
                            selectedCount = selectedUris.size,
                            busy = busy,
                            reports = reports,
                            onPickFolder = { openFolder.launch(null) },
                            onPickFiles = { openFiles.launch(arrayOf("*/*")) },
                            onInspect = { inspectSelected() },
                            onExportCsv = { createCsv.launch("glb-view-report.csv") },
                            onShareSelected = { shareModels(selectedModels) },
                            onClearReports = { reports = emptyList() },
                            onClearLibrary = {
                                files = emptyList()
                                selectedUris = emptySet()
                                selectedUri = null
                                reports = emptyList()
                                status = language.text("Biblioteca limpa.", "Library cleared.")
                            }
                        )
                    }
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accentMint, trackColor = panelBackground)
                }
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        color = mutedText,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(language: UiLanguage, onToggleLanguage: () -> Unit, onOpenFiles: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFF142039), Color(0xFF10182A), Color(0xFF122B38))))
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(accentBlue, accentMint))),
            contentAlignment = Alignment.Center
        ) { Text("3D", color = Color(0xFF09101E), fontWeight = FontWeight.Black, fontSize = 18.sp) }
        Column(modifier = Modifier.weight(1f)) {
            Text("GLB View", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(language.text("Visualizador 3D e ferramentas em lote", "3D viewer & batch tools"), color = mutedText, fontSize = 11.sp)
        }
        OutlinedButton(onClick = onToggleLanguage, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
            Text(if (language == UiLanguage.PT) "PT · EN" else "EN · PT", fontSize = 12.sp)
        }
        Button(
            onClick = onOpenFiles,
            colors = ButtonDefaults.buttonColors(containerColor = accentBlue, contentColor = Color(0xFF09101E)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) { Text("+", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}

@Composable
private fun LibraryScreen(
    language: UiLanguage,
    files: List<ModelFile>,
    selectedUris: Set<String>,
    selectedUri: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    onToggle: (ModelFile, Boolean) -> Unit,
    onOpen: (ModelFile) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onShareSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onInspect: () -> Unit,
    busy: Boolean
) {
    val filtered = files.filter { it.name.contains(search, ignoreCase = true) }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(language.text("Os teus modelos", "Your models"), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(files.size.toString() + language.text(" modelos na biblioteca", " models in library"), color = mutedText, fontSize = 12.sp)
            }
            SmallStat(selectedUris.size.toString(), language.text("selecionados", "selected"))
        }
        SearchField(search, onSearchChange, language.text("Pesquisar ficheiros…", "Search files…"))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = onPickFiles, enabled = !busy) { Text(language.text("＋ Ficheiros", "＋ Files")) }
            FilledTonalButton(onClick = onPickFolder, enabled = !busy) { Text(language.text("▧ Pasta", "▧ Folder")) }
            OutlinedButton(onClick = onInspect, enabled = files.isNotEmpty() && !busy) { Text(language.text("✓ Validar", "✓ Validate")) }
            OutlinedButton(onClick = onShareSelected, enabled = selectedUris.isNotEmpty() && !busy) { Text(language.text("⇧ Partilhar", "⇧ Share")) }
            OutlinedButton(onClick = onRemoveSelected, enabled = selectedUris.isNotEmpty() && !busy) { Text(language.text("Remover", "Remove")) }
        }
        if (filtered.isEmpty()) {
            EmptyState(
                language = language,
                title = if (search.isBlank()) language.text("A biblioteca está vazia", "Your library is empty") else language.text("Nenhum resultado", "No matches"),
                message = if (search.isBlank()) language.text(
                    "Importa ficheiros GLB/glTF ou escolhe uma pasta. Os modelos ficam acessíveis aqui.",
                    "Import GLB/glTF files or choose a folder. Your models will appear here."
                ) else language.text("Experimenta outro termo de pesquisa.", "Try a different search term."),
                actionLabel = language.text("Importar modelos", "Import models"),
                onAction = onPickFiles
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(filtered, key = { it.uri }) { model ->
                    ModelFileRow(
                        model = model,
                        language = language,
                        selected = model.uri in selectedUris,
                        active = model.uri == selectedUri,
                        onCheckedChange = { onToggle(model, it) },
                        onClick = { onOpen(model) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelFileRow(
    model: ModelFile,
    language: UiLanguage,
    selected: Boolean,
    active: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFF1A2B48) else panelBackground)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Checkbox(checked = selected, onCheckedChange = onCheckedChange)
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF243D63), Color(0xFF1A554F)))),
                contentAlignment = Alignment.Center
            ) { Text(model.extension, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatFileSize(model.sizeBytes, language) +
                        if (model.modifiedAt > 0L) "  ·  " + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(model.modifiedAt)) else "",
                    color = mutedText,
                    fontSize = 11.sp,
                    maxLines = 1
                )
                if (active) Text(language.text("Aberto no visualizador", "Open in viewer"), color = accentMint, fontSize = 10.sp)
            }
            Text("›", color = accentBlue, fontSize = 26.sp)
        }
    }
}

@Composable
private fun ViewerScreen(
    language: UiLanguage,
    model: ModelFile?,
    onBackToLibrary: () -> Unit,
    onPickModel: () -> Unit,
    onShare: (ModelFile) -> Unit
) {
    if (model == null) {
        EmptyState(
            language = language,
            title = language.text("Nenhum modelo aberto", "No model opened"),
            message = language.text("Escolhe um GLB ou glTF na biblioteca para começar.", "Choose a GLB or glTF from your library to get started."),
            actionLabel = language.text("Escolher ficheiro", "Choose file"),
            onAction = onPickModel
        )
        return
    }

    var attempt by remember(model.uri) { mutableIntStateOf(0) }
    var loaded by remember(model.uri) { mutableStateOf(false) }
    var timedOut by remember(model.uri) { mutableStateOf(false) }
    LaunchedEffect(model.uri, attempt) {
        loaded = false
        timedOut = false
        delay(20_000L)
        if (!loaded) timedOut = true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(formatFileSize(model.sizeBytes, language), fontSize = 12.sp, color = mutedText)
            }
            OutlinedButton(onClick = onBackToLibrary, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                Text(language.text("Biblioteca", "Library"))
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { onShare(model) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                Text(language.text("Partilhar", "Share"))
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF10192A), Color(0xFF1A2540), Color(0xFF112D35))))
        ) {
            key(attempt) {
                SceneView(modifier = Modifier.fillMaxSize()) {
                    val instance = rememberModelInstance(modelLoader, model.uri)
                    LaunchedEffect(instance) {
                        if (instance != null) {
                            loaded = true
                            timedOut = false
                        }
                    }
                    instance?.let {
                        ModelNode(modelInstance = it, scaleToUnits = 1.0f, autoAnimate = true, animationLoop = true)
                    }
                }
            }
            if (!loaded) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!timedOut) CircularProgressIndicator(color = accentMint)
                    Text(
                        if (timedOut) language.text("O modelo demorou a carregar.", "The model is taking too long to load.")
                        else language.text("A carregar o modelo 3D…", "Loading 3D model…"),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    if (timedOut) {
                        Button(
                            onClick = { attempt++ },
                            colors = ButtonDefaults.buttonColors(containerColor = accentBlue, contentColor = pageBackground)
                        ) { Text(language.text("Tentar novamente", "Try again")) }
                    }
                }
            }
            Text(
                language.text("Arrasta para rodar · pinça para zoom", "Drag to rotate · pinch to zoom"),
                color = Color(0xFFCBD5EA),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xAA09101E)).padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            InfoCard(
                modifier = Modifier.weight(1f),
                label = language.text("Formato", "Format"),
                value = when (model.extension) {
                    "GLB" -> "GLB · Binary"
                    "GLTF" -> "glTF · JSON"
                    else -> model.extension
                }
            )
            InfoCard(
                modifier = Modifier.weight(1f),
                label = language.text("Última alteração", "Last changed"),
                value = if (model.modifiedAt > 0L) {
                    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(model.modifiedAt))
                } else "—"
            )
        }
    }
}

@Composable
private fun ToolsScreen(
    language: UiLanguage,
    libraryCount: Int,
    selectedCount: Int,
    busy: Boolean,
    reports: List<InspectionResult>,
    onPickFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onInspect: () -> Unit,
    onExportCsv: () -> Unit,
    onShareSelected: () -> Unit,
    onClearReports: () -> Unit,
    onClearLibrary: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(language.text("Ferramentas", "Tools"), fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(
            language.text(
                "Operações pensadas para trabalhar com muitos ficheiros sem carregar todos os modelos 3D ao mesmo tempo.",
                "Batch operations designed to handle many files without loading every 3D model at once."
            ),
            color = mutedText,
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(Modifier.weight(1f), language.text("Na biblioteca", "In library"), libraryCount.toString())
            InfoCard(Modifier.weight(1f), language.text("Selecionados", "Selected"), selectedCount.toString())
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ToolCard(
                    title = language.text("Importação em lote", "Batch import"),
                    description = language.text("Importa vários ficheiros ou pesquisa recursivamente uma pasta por GLB/glTF.", "Import multiple files or recursively scan a folder for GLB/glTF."),
                    action = language.text("Escolher ficheiros", "Choose files"),
                    onAction = onPickFiles,
                    enabled = !busy
                )
            }
            item {
                ToolCard(
                    title = language.text("Pesquisar uma pasta", "Scan a folder"),
                    description = language.text("Inclui os modelos encontrados nas subpastas sem pedir acesso a todo o armazenamento.", "Add models found in subfolders without requesting full-storage access."),
                    action = language.text("Selecionar pasta", "Choose folder"),
                    onAction = onPickFolder,
                    enabled = !busy
                )
            }
            item {
                ToolCard(
                    title = language.text("Validar modelos", "Validate models"),
                    description = language.text("Verifica o cabeçalho GLB, o chunk JSON e conta meshes, nodes, materiais e animações.", "Checks the GLB header and JSON chunk; counts meshes, nodes, materials and animations."),
                    action = language.text("Executar validação", "Run validation"),
                    onAction = onInspect,
                    enabled = libraryCount > 0 && !busy
                )
            }
            item {
                ToolCard(
                    title = language.text("Partilha em lote", "Batch sharing"),
                    description = language.text("Partilha os ficheiros selecionados com outras aplicações compatíveis.", "Share selected files with other compatible apps."),
                    action = language.text("Partilhar seleção", "Share selection"),
                    onAction = onShareSelected,
                    enabled = selectedCount > 0 && !busy
                )
            }

            if (reports.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = panelBackground),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(language.text("Relatório de validação", "Validation report"), fontWeight = FontWeight.Bold)
                                    Text(
                                        language.text("Válidos: ", "Valid: ") + reports.count { it.valid && it.inspected } +
                                            language.text(" em ", " of ") + reports.count { it.inspected },
                                        fontSize = 12.sp,
                                        color = mutedText
                                    )
                                }
                                OutlinedButton(onClick = onExportCsv, enabled = !busy) {
                                    Text(language.text("Exportar CSV", "Export CSV"))
                                }
                            }
                            reports.take(100).forEach { result ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        when { !result.inspected -> "–"; result.valid -> "✓"; else -> "!" },
                                        color = when { !result.inspected -> mutedText; result.valid -> accentMint; else -> warningAmber },
                                        fontWeight = FontWeight.Bold
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(result.file.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(result.note, color = mutedText, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (reports.size > 100) {
                                Text(
                                    language.text("A mostrar 100 resultados; o CSV inclui todos.", "Showing 100 results; the CSV contains all results."),
                                    color = mutedText,
                                    fontSize = 11.sp
                                )
                            }
                            OutlinedButton(onClick = onClearReports, enabled = !busy) {
                                Text(language.text("Limpar relatório", "Clear report"))
                            }
                        }
                    }
                }
            }
            item {
                ToolCard(
                    title = language.text("Limpar biblioteca", "Clear library"),
                    description = language.text("Remove as referências guardadas na aplicação. Os ficheiros originais não são apagados.", "Remove saved references from the app. Original files are not deleted."),
                    action = language.text("Limpar referências", "Clear references"),
                    onAction = onClearLibrary,
                    enabled = libraryCount > 0 && !busy,
                    destructive = true
                )
            }
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
    enabled: Boolean,
    destructive: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = panelBackground)) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = mutedText, fontSize = 12.sp)
            if (destructive) {
                OutlinedButton(onClick = onAction, enabled = enabled) { Text(action) }
            } else {
                FilledTonalButton(onClick = onAction, enabled = enabled) { Text(action) }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Surface(color = panelBackground, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("⌕", color = accentBlue, fontSize = 22.sp)
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = mutedText, fontSize = 14.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                Text("×", color = mutedText, fontSize = 22.sp, modifier = Modifier.clickable { onValueChange("") })
            }
        }
    }
}

@Composable
private fun EmptyState(
    language: UiLanguage,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(90.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF203A60), Color(0xFF17463F)))),
            contentAlignment = Alignment.Center
        ) { Text("3D", color = accentMint, fontWeight = FontWeight.Black, fontSize = 28.sp) }
        Spacer(Modifier.height(22.dp))
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = mutedText, textAlign = TextAlign.Center, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = accentBlue, contentColor = pageBackground)
        ) { Text(actionLabel) }
    }
}

@Composable
private fun SmallStat(value: String, label: String) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(panelBackground).padding(horizontal = 13.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = accentMint, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(label, color = mutedText, fontSize = 9.sp)
    }
}

@Composable
private fun InfoCard(modifier: Modifier = Modifier, label: String, value: String) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = panelBackground)) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = mutedText, fontSize = 10.sp)
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
