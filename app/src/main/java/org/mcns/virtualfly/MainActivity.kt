package org.mcns.virtualfly

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.*
import kotlin.random.Random

/**
 * MCNS Virtual Fly is an experimental visualization/simulation.
 * It is not a validated biological model of Drosophila behavior.
 */
class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var pageHost: FrameLayout
    private lateinit var worldView: FlyWorldView
    private lateinit var brainStats: TextView
    private lateinit var heatView: ActivityHeatView
    private lateinit var settingsText: TextView
    private lateinit var eventText: TextView
    private val pages = mutableListOf<View>()
    private var brain: BrainModel? = null
    private var selectedPage = 0
    private var paused = false
    private var tickIntervalMs = 100L
    private var stepsPerUiTick = 1
    private var inputIntensity = 1.0f
    private var exportPending = false
    private var loopingStimulus: String? = null
    private var loopStimulusCountdown = 0
    private var eventHistory = ArrayList<String>()
    private var lastSnapshot = "Not loaded"
    private val pickFileRequest = 1701
    private val exportRequest = 1702

    private val ticker = object : Runnable {
        override fun run() {
            val b = brain
            if (!paused && b != null) {
                repeat(stepsPerUiTick) {
                    loopingStimulus?.let { kind ->
                        loopStimulusCountdown--
                        if (loopStimulusCountdown <= 0) {
                            b.stimulate(kind, inputIntensity * 0.7f)
                            loopStimulusCountdown = 5
                        }
                    }
                    val out = b.step()
                    worldView.setNeuralOutput(out.turn, out.drive, b.activeCount, b.lastStimulus)
                }
                if (b.stepNumber % 3L == 0L) refreshStats()
                worldView.invalidate()
            } else if (b == null && !paused) {
                worldView.demoTick()
                worldView.invalidate()
            }
            mainHandler.postDelayed(this, tickIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 14, 23)
        window.navigationBarColor = Color.rgb(7, 14, 23)
        buildUi()
        mainHandler.postDelayed(ticker, tickIntervalMs)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 15, 24))
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        val title = TextView(this).apply {
            text = "MCNS 30K  /  VIRTUAL FLY"
            textSize = 18f
            setTextColor(Color.rgb(220, 239, 255))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        statusText = TextView(this).apply {
            text = "Demo world • load MCNS_30000_MERGED.npz to activate the connectome"
            textSize = 11f
            setTextColor(Color.rgb(139, 181, 198))
            setPadding(0, dp(3), 0, 0)
        }
        header.addView(title)
        header.addView(statusText)
        root.addView(header)

        val navScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("WORLD", "SENSES", "BRAIN", "LAB")
        labels.forEachIndexed { idx, label ->
            nav.addView(button(label) { showPage(idx) }, LinearLayout.LayoutParams(dp(86), dp(44)).apply {
                setMargins(dp(2), dp(2), dp(2), dp(6))
            })
        }
        navScroll.addView(nav)
        root.addView(navScroll)
        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        makeWorldPage()
        makeSensesPage()
        makeBrainPage()
        makeLabPage()
        showPage(0)
    }

    private fun showPage(index: Int) {
        selectedPage = index.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        pages.forEachIndexed { i, v -> v.visibility = if (i == selectedPage) View.VISIBLE else View.GONE }
        val b = brain
        statusText.text = if (b == null) "DEMO ONLY • connectome not loaded" else b.statusLine()
    }

    private fun wrapPage(content: View): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(8, 15, 24))
            addView(content, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        pages.add(scroll)
        pageHost.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return scroll
    }

    private fun makeWorldPage() {
        val col = column()
        col.addView(sectionTitle("FLY ARENA"))
        col.addView(info("Tap the arena to give a touch pulse. Movement is partly a visualization fallback unless a real motor-side mapping exists in the NPZ."))
        worldView = FlyWorldView(this) { x, y ->
            if (brain != null) applyStimulus("touch", "Touch at ${(x * 100).toInt()}%, ${(y * 100).toInt()}%")
            else addEvent("Demo touch at ${(x * 100).toInt()}%, ${(y * 100).toInt()}")
        }
        col.addView(worldView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270)).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
        val row1 = row()
        row1.addView(button("LOAD CONNECTOME") { openNpZ() }, weighted())
        row1.addView(button(if (paused) "RESUME" else "PAUSE") {
            paused = !paused; refreshStats(); Toast.makeText(this, if (paused) "Paused" else "Running", Toast.LENGTH_SHORT).show()
            rebuildWorldControls()
        }, weighted())
        col.addView(row1)
        val row2 = row()
        row2.addView(button("VISUAL FLASH") { applyStimulus("visual", "Manual visual flash") }, weighted())
        row2.addView(button("ADD FOOD") { worldView.moveFood(); addEvent("Food target moved") }, weighted())
        row2.addView(button("RESET FLY") { worldView.resetFly(); brain?.reset(); addEvent("World and neural state reset"); refreshStats() }, weighted())
        col.addView(row2)
        col.addView(sectionTitle("MOTION"))
        col.addView(info("Speed affects only the visualization. Neural firing uses the selected simulation rate on the BRAIN page."))
        val speedRow = row()
        speedRow.addView(button("SLOW") { worldView.worldSpeed = 0.55f; addEvent("World speed: slow") }, weighted())
        speedRow.addView(button("NORMAL") { worldView.worldSpeed = 1.0f; addEvent("World speed: normal") }, weighted())
        speedRow.addView(button("FAST") { worldView.worldSpeed = 1.7f; addEvent("World speed: fast") }, weighted())
        col.addView(speedRow)
        val steerRow = row()
        steerRow.addView(button("NUDGE LEFT") { worldView.nudgeTurn(-0.9f); brain?.stimulate("touch", 0.4f); addEvent("Manual left nudge") }, weighted())
        steerRow.addView(button("NUDGE RIGHT") { worldView.nudgeTurn(0.9f); brain?.stimulate("touch", 0.4f); addEvent("Manual right nudge") }, weighted())
        col.addView(steerRow)
        col.addView(info("Scientific note: the connectome is wiring data, not a complete physiological brain. Motor behavior here is exploratory and must not be interpreted as validated fly behavior."))
        wrapPage(col)
    }

    // This deliberately does not rebuild the world page (which would discard the Canvas state).
    private fun rebuildWorldControls() {
        statusText.text = if (brain == null) "DEMO ONLY • connectome not loaded" else brain!!.statusLine()
    }

    private fun makeSensesPage() {
        val col = column()
        col.addView(sectionTitle("SENSORY STIMULUS BENCH"))
        col.addView(info("Buttons inject a pulse into the model. Visual uses visual_mask when available. Other senses use repeatable proxy neuron groups unless matching annotations exist; they are not measured modality-specific circuits."))
        col.addView(sectionTitle("INPUT STRENGTH"))
        col.addView(seekRow("Intensity", 5, 25, 10, "×0.5", "×2.5") { v -> inputIntensity = v / 10f })
        val gridRows = listOf(
            listOf("VISUAL FLASH", "visual", "Brief light-pattern input"),
            listOf("LOOMING OBJECT", "looming", "Repeated visual pulse; simulated approaching object"),
            listOf("TOUCH / HAIR", "touch", "Touch-like pulse"),
            listOf("SOUND BURST", "sound", "Auditory proxy pulse; no microphone used"),
            listOf("COLD PULSE", "cold", "Temperature proxy pulse"),
            listOf("HEAT PULSE", "heat", "Temperature proxy pulse"),
            listOf("NOCICEPTION", "nociception", "Abstract aversive-stimulus experiment"),
            listOf("BALANCE TILT", "balance", "Vestibular/balance proxy pulse"),
            listOf("VELOCITY CHANGE", "velocity", "Self-motion proxy pulse"),
            listOf("CENTRAL STIMULUS", "central", "Central neurons from central_mask if available")
        )
        gridRows.forEach { item ->
            val b = button(item[0]) {
                if (item[1] == "looming") {
                    loopingStimulus = if (loopingStimulus == "visual") null else "visual"
                    loopStimulusCountdown = 0
                    worldView.looming = loopingStimulus != null
                    addEvent(if (loopingStimulus == null) "Looming stimulus stopped" else "Looming pulse train started")
                } else applyStimulus(item[1], item[2])
            }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
            if (item[1] == "looming") col.addView(info(item[2]))
        }
        col.addView(button("STOP REPEATED STIMULUS") {
            loopingStimulus = null; worldView.looming = false; addEvent("Repeated stimulus stopped")
        })
        col.addView(button("CLEAR NEURAL ACTIVITY") { brain?.reset(); addEvent("Neural activity cleared"); refreshStats() })
        wrapPage(col)
    }

    private fun makeBrainPage() {
        val col = column()
        col.addView(sectionTitle("CONNECTOME STATUS"))
        brainStats = TextView(this).apply {
            text = "No connectome loaded\nExpected: edge_pre, edge_post, edge_weight; optional body_id, visual_mask, central_mask, motor_mask, somaSide, subclass"
            setTextColor(Color.rgb(190, 217, 229)); textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(Color.rgb(15, 27, 39))
        }
        col.addView(brainStats, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(sectionTitle("LIVE ACTIVITY MAP"))
        heatView = ActivityHeatView(this)
        col.addView(heatView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
        col.addView(sectionTitle("SINGLE-NEURON PROBE"))
        col.addView(info("Enter a zero-based row index (0 to N−1), not a FlyWire body ID. Inspect its outgoing targets, inject a pulse, or temporarily silence it."))
        val neuronInput = EditText(this).apply {
            hint = "Neuron row index, e.g. 120"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.rgb(19, 35, 48))
        }
        col.addView(neuronInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val neuronRow = row()
        neuronRow.addView(button("PULSE") {
            val idx = neuronInput.text.toString().toIntOrNull()
            val b = brain
            if (idx == null || b == null) Toast.makeText(this, "Load brain and enter a row index", Toast.LENGTH_SHORT).show()
            else if (!b.stimulateNeuron(idx, inputIntensity)) Toast.makeText(this, "Index outside 0..${b.neuronCount-1}", Toast.LENGTH_SHORT).show()
            else { addEvent("Direct pulse to neuron row $idx"); refreshStats() }
        }, weighted())
        neuronRow.addView(button("SILENCE / ON") {
            val idx = neuronInput.text.toString().toIntOrNull(); val b = brain
            if (idx == null || b == null) Toast.makeText(this, "Load brain and enter a row index", Toast.LENGTH_SHORT).show()
            else { val now = b.toggleSilence(idx); if (now == null) Toast.makeText(this, "Index outside range", Toast.LENGTH_SHORT).show() else { addEvent("Neuron row $idx ${if(now) "silenced" else "re-enabled"}"); refreshStats() } }
        }, weighted())
        neuronRow.addView(button("INSPECT") {
            val idx = neuronInput.text.toString().toIntOrNull(); val b = brain
            if (idx == null || b == null) Toast.makeText(this, "Load brain and enter a row index", Toast.LENGTH_SHORT).show()
            else { val infoText = b.inspectNeuron(idx); AlertDialog.Builder(this).setTitle("Neuron row $idx").setMessage(infoText).setPositiveButton("OK", null).show() }
        }, weighted())
        col.addView(neuronRow)
        settingsText = info("Model: leaky threshold units • edges treated as nonnegative • not biologically validated") as TextView
        col.addView(settingsText)
        col.addView(sectionTitle("MODEL PARAMETERS"))
        col.addView(seekRow("Firing threshold", 3, 20, 10, "0.3", "2.0") { v -> brain?.threshold = v / 10f; refreshStats() })
        col.addView(seekRow("Membrane persistence", 50, 99, 91, "0.50", "0.99") { v -> brain?.leak = v / 100f; refreshStats() })
        col.addView(seekRow("Synaptic gain", 1, 40, 15, "0.01", "0.40") { v -> brain?.gain = v / 100f; refreshStats() })
        col.addView(seekRow("Simulation steps per refresh", 1, 4, 1, "1", "4") { v -> stepsPerUiTick = v.coerceAtLeast(1) })
        col.addView(seekRow("Refresh interval (ms)", 50, 300, 100, "50", "300") { v -> tickIntervalMs = v.toLong().coerceIn(50, 300) })
        val conCheck = CheckBox(this).apply {
            text = "Propagate across connectome edges"; setTextColor(Color.WHITE); isChecked = true
            setOnCheckedChangeListener { _, checked -> brain?.connectionsEnabled = checked; addEvent("Connectome propagation ${if (checked) "enabled" else "disabled"}") }
        }
        col.addView(conCheck)
        val motorCheck = CheckBox(this).apply {
            text = "Allow neural motor output to influence fly"; setTextColor(Color.WHITE); isChecked = true
            setOnCheckedChangeListener { _, checked -> worldView.neuralMotorEnabled = checked }
        }
        col.addView(motorCheck)
        val controls = row()
        controls.addView(button("LOAD NPZ") { openNpZ() }, weighted())
        controls.addView(button("RESET BRAIN") { brain?.reset(); addEvent("Brain state reset"); refreshStats() }, weighted())
        controls.addView(button("ONE STEP") { brain?.let { val out = it.step(); worldView.setNeuralOutput(out.turn, out.drive, it.activeCount, it.lastStimulus); refreshStats() } }, weighted())
        col.addView(controls)
        wrapPage(col)
    }

    private fun makeLabPage() {
        val col = column()
        col.addView(sectionTitle("EXPERIMENT LIBRARY"))
        col.addView(info("Each preset logs what was changed. These are reproducible computational experiments, not claims that the simulated fly thinks or behaves like a real one."))
        val presets = listOf(
            Triple("1 · VISUAL DETECTION", "visual", "Visual-mask pulse; measure firing response"),
            Triple("2 · LOOMING / COLLISION", "looming", "Repeated visual pulses plus approaching-object visual"),
            Triple("3 · TOUCH RESPONSE", "touch", "Touch-like proxy input"),
            Triple("4 · SOUND RESPONSE", "sound", "Sound proxy input (no mic permission)"),
            Triple("5 · TEMPERATURE", "heat", "Heat proxy stimulus"),
            Triple("6 · BALANCE / SELF-MOTION", "balance", "Balance proxy plus motion disturbance"),
            Triple("7 · MOTOR SILENCE", "motorblock", "Compare movement with motor output disconnected"),
            Triple("8 · CONNECTIONS OFF", "edgeblock", "Compare with propagation disabled"),
            Triple("9 · CENTRAL STIMULATION", "central", "Stimulate central_mask if available"),
            Triple("10 · RECOVERY", "recovery", "Stop input and observe decay"),
            Triple("11 · SPONTANEOUS BASELINE", "baseline", "No sensory input; observe spontaneous activity"),
            Triple("12 · RANDOM PROBE", "random", "Inject repeatable random proxy stimulus")
        )
        presets.forEach { p ->
            col.addView(button(p.first) { runPreset(p.second, p.third) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                setMargins(0, dp(2), 0, dp(2))
            })
        }
        col.addView(button("PPGA / PEOPLE PLAYGROUND BRIDGE STATUS") { showPpgaInfo() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { setMargins(0, dp(4), 0, dp(4)) })
        col.addView(sectionTitle("EVENT LOG"))
        eventText = TextView(this).apply {
            text = "No events yet"; setTextColor(Color.rgb(178, 204, 219)); textSize = 11f
            typeface = Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.rgb(15, 27, 39))
        }
        col.addView(eventText)
        val row = row()
        row.addView(button("EXPORT CSV") { exportLog() }, weighted())
        row.addView(button("CLEAR LOG") { eventHistory.clear(); updateEvents(); addEvent("Log cleared") }, weighted())
        row.addView(button("ABOUT / LIMITS") { showAbout() }, weighted())
        col.addView(row)
        wrapPage(col)
    }

    private fun runPreset(name: String, desc: String) {
        when (name) {
            "looming" -> { loopingStimulus = "visual"; loopStimulusCountdown = 0; worldView.looming = true; applyStimulus("visual", desc) }
            "motorblock" -> { worldView.neuralMotorEnabled = false; addEvent("Motor output disconnected: $desc") }
            "edgeblock" -> { brain?.connectionsEnabled = false; addEvent("Connectome propagation disabled: $desc") }
            "recovery" -> { loopingStimulus = null; worldView.looming = false; addEvent("Stimulus stopped; recovery/decay phase started") }
            "baseline" -> { loopingStimulus = null; worldView.looming = false; addEvent("Baseline started; no injected input") }
            "random" -> { val kinds = listOf("touch", "sound", "visual", "balance", "velocity"); applyStimulus(kinds[Random.nextInt(kinds.size)], desc) }
            else -> applyStimulus(name, desc)
        }
    }

    private fun applyStimulus(kind: String, label: String) {
        val b = brain
        if (b == null) {
            addEvent("$label (demo only; load NPZ first)")
            Toast.makeText(this, "Load MCNS_30000_MERGED.npz first", Toast.LENGTH_SHORT).show()
            return
        }
        val actual = when (kind) {
            "looming" -> "visual"
            else -> kind
        }
        val count = b.stimulate(actual, inputIntensity)
        addEvent("$label; input=$actual; targets=$count; intensity=${"%.2f".format(Locale.US, inputIntensity)}")
        statusText.text = b.statusLine()
        refreshStats()
    }

    private fun openNpZ() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        try { startActivityForResult(intent, pickFileRequest) }
        catch (_: Exception) { Toast.makeText(this, "File picker unavailable", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated by Android API; retained for wide compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == pickFileRequest) loadConnectome(uri)
        if (requestCode == exportRequest && exportPending) saveLog(uri)
    }

    private fun loadConnectome(uri: Uri) {
        paused = true
        statusText.text = "Reading NPZ on background thread…"
        Toast.makeText(this, "Loading connectome; this can use substantial memory", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val stream = contentResolver.openInputStream(uri) ?: error("Could not open selected file")
                val loaded = NpzBrainLoader.load(stream)
                runOnUiThread {
                    brain = loaded
                    paused = false
                    loopingStimulus = null
                    worldView.looming = false
                    addEvent("Loaded connectome: ${loaded.neuronCount} neurons / ${loaded.edgeCount} edges")
                    refreshStats()
                    statusText.text = loaded.statusLine()
                    Toast.makeText(this, "MCNS loaded: ${loaded.neuronCount} neurons", Toast.LENGTH_LONG).show()
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { paused = false; statusText.text = "Load ran out of memory. Try closing apps first."; addEvent("Load failed: out of memory") }
            } catch (e: Exception) {
                runOnUiThread {
                    paused = false
                    statusText.text = "NPZ load failed: ${e.message ?: e.javaClass.simpleName}"
                    addEvent("NPZ load failed: ${e.message ?: e.javaClass.simpleName}")
                    AlertDialog.Builder(this).setTitle("Could not load this NPZ")
                        .setMessage("Expected edge_pre, edge_post, edge_weight. If edges use neuron body IDs, the archive must contain body_id.\n\n${e.message ?: "Unknown error"}")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun refreshStats() {
        val b = brain
        if (b == null) {
            if (::brainStats.isInitialized) brainStats.text = "No connectome loaded\nExpected ~30,000 neurons and edge_pre/edge_post/edge_weight arrays."
            if (::heatView.isInitialized) heatView.setActivity(FloatArray(96))
            return
        }
        lastSnapshot = b.summary()
        if (::brainStats.isInitialized) brainStats.text = lastSnapshot
        if (::heatView.isInitialized) heatView.setActivity(b.activityBins())
        if (::settingsText.isInitialized) settingsText.text = "Model: leaky threshold units • gain=${"%.2f".format(Locale.US,b.gain)} • leak=${"%.2f".format(Locale.US,b.leak)} • threshold=${"%.2f".format(Locale.US,b.threshold)}\n${b.modelNote}"
        statusText.text = b.statusLine() + if (paused) " • PAUSED" else ""
    }

    private fun addEvent(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        eventHistory.add("$ts,$message")
        if (eventHistory.size > 180) eventHistory.removeAt(0)
        updateEvents()
    }

    private fun updateEvents() {
        if (::eventText.isInitialized) eventText.text = if (eventHistory.isEmpty()) "No events yet" else eventHistory.takeLast(45).joinToString("\n")
    }

    private fun exportLog() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "mcns_virtual_fly_log.csv")
        }
        exportPending = true
        startActivityForResult(i, exportRequest)
    }

    private fun saveLog(uri: Uri) {
        try {
            val b = brain
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                w.write("timestamp,event\n")
                eventHistory.forEach { line ->
                    val cut = line.indexOf(',')
                    if (cut > 0) w.write("${line.substring(0,cut)},\"${line.substring(cut+1).replace("\"", "\"\"")}\"\n")
                }
                w.write("\nmodel_summary,\"${(b?.summary() ?: "no connectome loaded").replace("\n", "; ").replace("\"", "\"\"")}\"\n")
            } ?: error("Could not open destination")
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
        exportPending = false
    }

    private fun showPpgaInfo() {
        AlertDialog.Builder(this).setTitle("PPGA bridge status")
            .setMessage("""NOT CONNECTED in this build. This app currently runs offline and has no PPGA native sample, plugin API, or socket bridge enabled.

The experiment interface we discussed would send vision, touch, sound, nociception, temperature, balance and velocity inputs to an MCNS step endpoint, then translate annotated motor-neuron activity into movement commands. Before enabling it, the PPGA mod must expose a supported input/output path; a ZIP containing only the connectome is not enough.

This button is a status/help panel, not a live connection.""")
            .setPositiveButton("OK", null).show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("MCNS Virtual Fly — experimental model")
            .setMessage("The app uses your connectome's weighted edges with a simple leaky-threshold activity rule. The connectome does not by itself specify validated membrane dynamics, synapse signs, neurotransmitter response, sensory modality maps, or body mechanics.\n\nVisual stimulation uses visual_mask when present. Other senses are proxy groups unless real annotations are supplied. Motor-side mapping is used only if somaSide values can be read. Otherwise movement includes a visualization fallback. Results are exploratory, not biological predictions.")
            .setPositiveButton("Got it", null).show()
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5), dp(4), dp(5), dp(20))
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; isAllCaps = false
        setTextColor(Color.rgb(226, 242, 255))
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30, 63, 82)))
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { onClick() }
    }
    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s; textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Color.rgb(109, 207, 220)); setPadding(dp(3), dp(12), dp(3), dp(5))
    }
    private fun info(s: String) = TextView(this).apply {
        text = s; textSize = 11f; setTextColor(Color.rgb(148, 177, 192)); setPadding(dp(4), dp(4), dp(4), dp(7))
    }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
    private fun seekRow(label: String, min: Int, max: Int, initial: Int, minLabel: String, maxLabel: String, onValue: (Int) -> Unit): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(5), dp(4), dp(5)) }
        val text = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; this.text = "$label: $initial" }
        val seek = SeekBar(this).apply {
            this.max = max - min; progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress; text.text = "$label: $v"; onValue(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val labels = row()
        labels.addView(TextView(this).apply { text = minLabel; textSize = 10f; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        labels.addView(TextView(this).apply { text = maxLabel; textSize = 10f; gravity = Gravity.RIGHT; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(text); outer.addView(seek); outer.addView(labels)
        return outer
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}

private class FlyWorldView(context: android.content.Context, val onTap: (Float,Float)->Unit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fx = 0.40f; private var fy = 0.54f; private var heading = -0.15f
    private var turn = 0f; private var drive = 0f; private var active = 0; private var stimulus = "none"
    private var tick = 0L; private var lastTouch = 0L
    private var foodX = .77f; private var foodY = .30f
    var worldSpeed = 1f
    var looming = false
    var neuralMotorEnabled = true
    private var manualTurn = 0f

    fun setNeuralOutput(t: Float, d: Float, activeCount: Int, lastStimulus: String) {
        turn = t; drive = d; active = activeCount
        if (stimulus != lastStimulus) lastTouch = tick
        stimulus = lastStimulus
    }
    fun demoTick() { drive = .05f; turn = .12f * sin(tick * .025f) }
    fun moveFood() { foodX = Random.nextFloat() * .7f + .15f; foodY = Random.nextFloat() * .65f + .15f }
    fun nudgeTurn(amount: Float) { manualTurn = amount; heading += amount * .22f }
    fun resetFly() { fx=.40f; fy=.54f; heading=-.15f; drive=0f; turn=0f; active=0; manualTurn=0f; tick=0 }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = (event.x / width.coerceAtLeast(1)).coerceIn(0f,1f)
            val y = (event.y / height.coerceAtLeast(1)).coerceIn(0f,1f)
            onTap(x,y); performClick(); invalidate(); return true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w=width.toFloat(); val h=height.toFloat()
        c.drawColor(Color.rgb(10,22,30))
        p.color=Color.rgb(23,47,57); p.strokeWidth=1f
        for(x in 0..(w.toInt()/dp(36))) c.drawLine(x*dp(36).toFloat(),0f,x*dp(36).toFloat(),h,p)
        for(y in 0..(h.toInt()/dp(36))) c.drawLine(0f,y*dp(36).toFloat(),w,y*dp(36).toFloat(),p)
        // Plants / landmarks
        drawPlant(c,w*.20f,h*.28f,1f); drawPlant(c,w*.80f,h*.70f,1.2f)
        p.color=Color.rgb(105,227,137); c.drawCircle(foodX*w,foodY*h,dp(5).toFloat(),p)
        p.color=Color.rgb(35,77,53); p.style=Paint.Style.STROKE; p.strokeWidth=dp(2).toFloat()
        c.drawCircle(foodX*w,foodY*h,dp(12).toFloat(),p); p.style=Paint.Style.FILL
        if (looming) {
            val pulse = (tick % 34L).toFloat()/34f
            p.color=Color.argb((90*(1f-pulse)).toInt(),255,100,90); p.style=Paint.Style.STROKE; p.strokeWidth=dp(4).toFloat()
            c.drawCircle(w*.5f,h*.5f,dp(18).toFloat()+pulse*min(w,h)*.28f,p); p.style=Paint.Style.FILL
        }
        val x=fx*w; val y=fy*h
        p.color=if (stimulus!="none" && tick-lastTouch<18) Color.YELLOW else Color.rgb(255,192,111)
        c.save(); c.rotate(Math.toDegrees(heading.toDouble()).toFloat(),x,y)
        c.drawOval(RectF(x-dp(15),y-dp(8),x+dp(13),y+dp(8)),p)
        p.color=Color.rgb(25,25,29); c.drawCircle(x+dp(11),y,dp(3).toFloat(),p)
        p.color=Color.rgb(213,221,216); p.strokeWidth=dp(2).toFloat()
        c.drawLine(x-dp(2),y-dp(5),x-dp(12),y-dp(16),p); c.drawLine(x-dp(2),y+dp(5),x-dp(12),y+dp(16),p)
        c.drawLine(x-dp(1),y-dp(4),x+dp(5),y-dp(17),p); c.drawLine(x-dp(1),y+dp(4),x+dp(5),y+dp(17),p)
        c.restore()
        p.color=Color.WHITE; p.textSize=dp(11).toFloat(); p.typeface=Typeface.MONOSPACE
        c.drawText("Tap = touch pulse / move target",dp(8).toFloat(),dp(18).toFloat(),p)
        c.drawText("Active $active • $stimulus • ${if(neuralMotorEnabled) "motor linked" else "motor muted"}",dp(8).toFloat(),dp(35).toFloat(),p)
        val base = if(neuralMotorEnabled) .006f + drive*.018f else .006f
        heading += (if(neuralMotorEnabled) turn*.065f else 0f) + manualTurn*.004f + .0014f*sin(tick*.023f)
        fx += cos(heading)*base*worldSpeed
        fy += sin(heading)*base*worldSpeed
        if(fx<.04f||fx>.96f){heading=PI.toFloat()-heading; fx=fx.coerceIn(.04f,.96f)}
        if(fy<.10f||fy>.94f){heading = -heading; fy=fy.coerceIn(.10f,.94f)}
        manualTurn *= .94f
        tick++
    }
    private fun drawPlant(c: Canvas,x:Float,y:Float,s:Float){
        p.color=Color.rgb(39,103,73); p.strokeWidth=dp(3).toFloat()
        c.drawLine(x,y+dp(18),x,y-dp(18)*s,p)
        c.drawOval(x-dp(17)*s,y-dp(16)*s,x-dp(2),y-dp(4),p)
        c.drawOval(x+dp(1),y-dp(9),x+dp(17)*s,y+dp(2),p)
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
}

private class ActivityHeatView(context: android.content.Context) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG); private var values=FloatArray(96)
    fun setActivity(v:FloatArray){values=v.copyOf();invalidate()}
    override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(Color.rgb(11,22,32)); if(values.isEmpty())return
        val gap=2f; val bar=(width.toFloat()/values.size).coerceAtLeast(1f)
        values.forEachIndexed{idx,v -> val strength=v.coerceIn(0f,1f); val hue=Color.rgb((30+190*strength).toInt(),(70+160*strength).toInt(),(100+110*(1f-strength)).toInt());paint.color=hue
            val left=idx*bar+gap/2; val top=height-(height*strength.coerceAtLeast(.025f));c.drawRect(left,top,left+bar-gap, height.toFloat()-dp(3),paint)
        }
        paint.color=Color.LTGRAY;paint.textSize=dp(10).toFloat();c.drawText("Neuron-index activity bins (recent firing)",dp(5).toFloat(),dp(13).toFloat(),paint)
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
}

private data class MotorOutput(val turn:Float,val drive:Float)

private class BrainModel private constructor(
    val neuronCount:Int,
    private val offsets:IntArray,
    private val targets:IntArray,
    private val baseWeights:FloatArray,
    private val visualMask:BooleanArray,
    private val centralMask:BooleanArray,
    private val motorMask:BooleanArray,
    private val leftMotor:BooleanArray,
    private val rightMotor:BooleanArray,
    private val subclassLabels:Array<String>,
    private val visualHasAnnotations:Boolean
){
    val edgeCount get()=targets.size
    private val v=FloatArray(neuronCount)
    private val current=FloatArray(neuronCount)
    private val fired=BooleanArray(neuronCount)
    private val silenced=BooleanArray(neuronCount)
    private val firedIndices=IntArray(neuronCount)
    private val recentActivity=FloatArray(96)
    private val groupMasks=HashMap<String,BooleanArray>()
    private val random=Random(20261010)
    var threshold=1.0f
    var leak=.91f
    var gain=.15f
    var connectionsEnabled=true
    var activeCount=0; private set
    var stepNumber=0L; private set
    var lastStimulus="none"; private set
    var droppedEdgeWork=false; private set
    val hasMotorSideData=leftMotor.any{it} && rightMotor.any{it}
    val visualCount=visualMask.count{it}
    val centralCount=centralMask.count{it}
    val motorCount=motorMask.count{it}
    val modelNote:String get()=buildString{
        append(if(visualHasAnnotations && visualCount>0) "visual_mask: $visualCount neurons" else "visual: proxy group")
        append(" • central_mask: $centralCount • motor_mask: $motorCount")
        append(if(hasMotorSideData) " • left/right side map available" else " • no left/right motor-side map; turning is a display fallback")
        append(" • edge signs unknown")
    }

    fun statusLine()="MCNS ${neuronCount}N / ${edgeCount}E • active $activeCount ${if(connectionsEnabled) "edges ON" else "edges OFF"}"
    fun summary():String=buildString{
        append("Neurons: $neuronCount\nEdges: $edgeCount\nActive this step: $activeCount\nSimulation steps: $stepNumber\nVisual mask: $visualCount\nCentral mask: $centralCount\nMotor mask: $motorCount\nMotor subclasses: ${motorSubclassSummary()}\nSilenced neurons: ${silenced.count{it}}\nLeft/right motor map: ${if(hasMotorSideData) "yes" else "missing (fallback)"}\nConnection propagation: ${if(connectionsEnabled) "ON" else "OFF"}\nEdge work capped: ${if(droppedEdgeWork) "yes — activity too dense" else "no"}\n\nModel: leaky-threshold approximation; edge weights are nonnegative and normalized. Not biologically validated.")
    }

    private fun motorSubclassSummary():String {
        val counts = linkedMapOf<String,Int>()
        for(i in 0 until min(neuronCount,subclassLabels.size)) if(motorMask[i]) {
            val label = subclassLabels[i].trim().ifEmpty { "unlabelled" }
            counts[label] = (counts[label] ?: 0) + 1
        }
        return if(counts.isEmpty()) "not present" else counts.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { "${it.key}=${it.value}" }
    }

    fun inspectNeuron(index:Int):String {
        if(index !in 0 until neuronCount) return "Index out of range. Valid row indices: 0..${neuronCount-1}"
        val start=offsets[index];val end=offsets[index+1]
        val targetsText=(start until min(end,start+24)).joinToString(", "){targets[it].toString()}
        return "Row index: $index\nOutgoing edges: ${end-start}\nMembrane state: ${"%.3f".format(Locale.US,v[index])}\nFired last step: ${fired[index]}\nSilenced: ${silenced[index]}\nVisual: ${visualMask[index]}\nCentral: ${centralMask[index]}\nMotor: ${motorMask[index]}\nSide: ${if(leftMotor[index]) "left" else if(rightMotor[index]) "right" else "unmapped"}\n\nFirst ${min(24,end-start)} target indices:\n$targetsText"
    }

    fun stimulateNeuron(index:Int,intensity:Float):Boolean {
        if(index !in 0 until neuronCount || silenced[index]) return false
        v[index]=(v[index]+threshold*(.9f+intensity)).coerceAtMost(threshold*3f)
        lastStimulus="manual-neuron-$index"
        return true
    }

    fun toggleSilence(index:Int):Boolean? {
        if(index !in 0 until neuronCount) return null
        silenced[index]=!silenced[index]
        if(silenced[index]) { v[index]=0f; current[index]=0f; fired[index]=false }
        return silenced[index]
    }

    fun stimulate(kind:String,intensity:Float):Int{
        lastStimulus=kind
        val mask=when(kind){
            "visual"-> if(visualCount>0) visualMask else proxyMask(kind)
            "central"-> if(centralCount>0) centralMask else proxyMask(kind)
            else->proxyMask(kind)
        }
        var hits=0
        val boost=(threshold*(.85f+intensity*.9f)).coerceAtMost(threshold*3f)
        for(i in 0 until neuronCount) if(mask[i] && !motorMask[i]){v[i]=(v[i]+boost).coerceAtMost(threshold*3f);hits++}
        if(hits==0){
            val count=min(64,neuronCount)
            for(k in 0 until count){val i=(k*997+kind.hashCode().absoluteValue)%neuronCount;v[i]=(v[i]+boost).coerceAtMost(threshold*3f);hits++}
        }
        return hits
    }
    private fun proxyMask(kind:String):BooleanArray=groupMasks.getOrPut(kind){
        val mask=BooleanArray(neuronCount); val group=(kind.hashCode().absoluteValue%7)+1
        for(i in 0 until neuronCount){
            val h=((i.toLong()*1103515245L+12345L) xor (kind.hashCode().toLong()*2654435761L)).ushr(28).toInt() and 15
            mask[i]= !motorMask[i] && h%8==group%8
        }
        mask
    }

    fun step():MotorOutput{
        stepNumber++;droppedEdgeWork=false
        var totalFired=0
        for(i in 0 until neuronCount){
            if(silenced[i]) { v[i]=0f; current[i]=0f; fired[i]=false; continue }
            v[i]*=leak
            fired[i]=v[i]>=threshold
            if(fired[i]){v[i]=0f;firedIndices[totalFired++]=i}
        }
        activeCount=totalFired
        val binScale=neuronCount.toFloat()/recentActivity.size
        for(i in recentActivity.indices)recentActivity[i]*=.83f
        for(k in 0 until totalFired){val bin=(firedIndices[k]/binScale).toInt().coerceIn(0,recentActivity.lastIndex);recentActivity[bin]=(recentActivity[bin]+.18f).coerceAtMost(1f)}
        var edgeWork=0
        if(connectionsEnabled){
            // Limit highly synchronous bursts so an accidental avalanche cannot freeze a phone.
            val selected= min(totalFired,1800)
            for(k in 0 until selected){
                val source=if(totalFired<=selected) firedIndices[k] else firedIndices[(k*totalFired)/selected]
                var e=offsets[source]
                val end=offsets[source+1]
                while(e<end){
                    if(edgeWork>=550_000){droppedEdgeWork=true;break}
                    current[targets[e]] += baseWeights[e]*gain/.15f
                    e++;edgeWork++
                }
                if(droppedEdgeWork)break
            }
        }
        var motorFired=0;var l=0;var r=0
        for(k in 0 until totalFired){val i=firedIndices[k];if(motorMask[i]){motorFired++;if(leftMotor[i])l++;if(rightMotor[i])r++}}
        for(i in 0 until neuronCount){
            if(silenced[i]) v[i]=0f else v[i]=(v[i]+current[i]).coerceAtMost(threshold*3f)
            current[i]=0f
        }
        val turn=if(hasMotorSideData && l+r>0) ((r-l).toFloat()/(r+l)).coerceIn(-1f,1f) else 0f
        val drive=(motorFired/4f).coerceIn(0f,1f)
        return MotorOutput(turn,drive)
    }
    fun activityBins()=recentActivity.copyOf()
    fun reset(){v.fill(0f);current.fill(0f);fired.fill(false);recentActivity.fill(0f);activeCount=0;stepNumber=0;lastStimulus="none";droppedEdgeWork=false}

    companion object{
        fun fromNpz(data:LoadedArrays):BrainModel{
            val n=data.neuronCount
            require(n>0 && n<=100_000){"Neuron count is outside the supported range: $n"}
            require(data.pre.size==data.post.size && data.pre.size==data.weight.size){"Edge array lengths differ"}
            val counts=IntArray(n);var valid=0
            for(k in data.pre.indices){val a=data.pre[k];val b=data.post[k];if(a in 0 until n && b in 0 until n){counts[a]++;valid++}}
            require(valid>0){"No valid edges after endpoint mapping"}
            val offsets=IntArray(n+1);for(i in 0 until n)offsets[i+1]=offsets[i]+counts[i]
            val cursor=offsets.copyOf();val targets=IntArray(valid);val weights=FloatArray(valid)
            var maxW=0f;for(x in data.weight)if(x.isFinite() && x>maxW)maxW=x
            if(maxW<=0f)maxW=1f
            for(k in data.pre.indices){val a=data.pre[k];val b=data.post[k];if(a in 0 until n && b in 0 until n){val at=cursor[a]++;targets[at]=b
                val raw=if(data.weight[k].isFinite())data.weight[k].coerceAtLeast(0f) else 0f
                weights[at]=(sqrt(raw/maxW)*.15f/sqrt(counts[a].coerceAtLeast(1).toFloat())).coerceIn(0f,.15f)
            }}
            val vm=fitMask(data.visual,n);val cm=fitMask(data.central,n);val mm=fitMask(data.motor,n)
            val left=BooleanArray(n);val right=BooleanArray(n)
            for(i in 0 until min(n,data.sides.size)) if(mm[i]){
                val s=data.sides[i].lowercase(Locale.US).trim()
                if(s=="l"||s=="left"||s=="lhs"||s.contains("left"))left[i]=true
                if(s=="r"||s=="right"||s=="rhs"||s.contains("right"))right[i]=true
            }
            return BrainModel(n,offsets,targets,weights,vm,cm,mm,left,right,data.subclass, data.visual!=null)
        }
        private fun fitMask(input:BooleanArray?,n:Int):BooleanArray=BooleanArray(n){input!=null&&it<input.size&&input[it]}
    }
}

private data class LoadedArrays(
    val neuronCount:Int,val pre:IntArray,val post:IntArray,val weight:FloatArray,
    val visual:BooleanArray?,val central:BooleanArray?,val motor:BooleanArray?,
    val sides:Array<String>,val subclass:Array<String>
)

private object NpzBrainLoader{
    fun load(input:InputStream):BrainModel{
        var preL:LongArray?=null;var postL:LongArray?=null;var body:LongArray?=null
        var w:FloatArray?=null;var visual:BooleanArray?=null;var central:BooleanArray?=null;var motor:BooleanArray?=null
        var sides:Array<String> = emptyArray();var subclass:Array<String> = emptyArray()
        ZipInputStream(input.buffered()).use{zip->
            var entry=zip.nextEntry
            while(entry!=null){
                if(!entry.isDirectory&&entry.name.endsWith(".npy")){
                    val name=entry.name.substringAfterLast('/').removeSuffix(".npy")
                    if(name in setOf("edge_pre","edge_post","edge_weight","body_id","visual_mask","central_mask","motor_mask","somaSide","rootSide","subclass")){
                        val bytes=ByteArrayOutputStream();val buffer=ByteArray(64*1024)
                        while(true){val r=zip.read(buffer);if(r<0)break;bytes.write(buffer,0,r)}
                        val arr=Npy.parse(bytes.toByteArray())
                        when(name){
                            "edge_pre"->preL=arr.asLongs()
                            "edge_post"->postL=arr.asLongs()
                            "edge_weight"->w=arr.asFloats()
                            "body_id"->body=arr.asLongs()
                            "visual_mask"->visual=arr.asBooleans()
                            "central_mask"->central=arr.asBooleans()
                            "motor_mask"->motor=arr.asBooleans()
                            "somaSide"->sides=arr.asStrings()
                            "rootSide"->{if(sides.isEmpty())sides=arr.asStrings()}
                            "subclass"->subclass=arr.asStrings()
                        }
                    }
                }
                zip.closeEntry();entry=zip.nextEntry
            }
        }
        val pl=preL?:error("missing edge_pre.npy");val ql=postL?:error("missing edge_post.npy");val weights=w?:error("missing edge_weight.npy")
        require(pl.size==ql.size&&pl.size==weights.size){"edge_pre, edge_post and edge_weight must have equal lengths"}
        val maxEndpoint=maxOf(pl.maxOrNull()?:0L,ql.maxOrNull()?:0L)
        val n=body?.size?:maxOf((maxEndpoint+1).coerceAtMost(100_000L).toInt(), visual?.size?:0,central?.size?:0,motor?.size?:0)
        require(n in 1..100_000){"Unsupported neuron count $n"}
        val pre=IntArray(pl.size);val post=IntArray(ql.size)
        val areIndices=pl.all{it>=0L&&it<n.toLong()}&&ql.all{it>=0L&&it<n.toLong()}
        if(areIndices){for(i in pl.indices){pre[i]=pl[i].toInt();post[i]=ql[i].toInt()}}
        else{
            val ids=body?:error("edge endpoints exceed row indices and body_id.npy is missing")
            val lookup=HashMap<Long,Int>(ids.size*4/3+1);for(i in ids.indices)lookup[ids[i]]=i
            var missing=0
            for(i in pl.indices){pre[i]=lookup[pl[i]]?:-1;post[i]=lookup[ql[i]]?:-1;if(pre[i]<0||post[i]<0)missing++}
            require(missing==0){"$missing edge endpoints could not be matched against body_id. The selected mini-brain and edge arrays may use different neuron sets."}
        }
        val d=LoadedArrays(n,pre,post,weights,visual,central,motor,fitStrings(sides,n),fitStrings(subclass,n))
        return BrainModel.fromNpz(d)
    }
    private fun fitStrings(src:Array<String>,n:Int)=Array(n){if(it<src.size)src[it] else ""}
}

private class Npy(private val descr:String,private val shape:IntArray,private val raw:ByteArray, private val dataStart:Int){
    private val little=!descr.startsWith(">")
    private val count: Int get()=shape.fold(1L){a,b->a*b}.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun asLongs():LongArray{
        val size=itemSize(descr);val b=ByteBuffer.wrap(raw).order(if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        require(raw.size-dataStart>=count*size){"Truncated NPY array ($descr)"}
        return LongArray(count){i->val pos=dataStart+i*size;when(descr.lastOrNull()){
            '1'->if(descr.contains("u1")||descr.contains("b1")) (raw[pos].toInt() and 255).toLong() else raw[pos].toLong()
            '2'->if(descr.contains("u2")) (b.getShort(pos).toInt() and 65535).toLong() else b.getShort(pos).toLong()
            '4'->if(descr.contains("u4")) (b.getInt(pos).toLong() and 0xffffffffL) else b.getInt(pos).toLong()
            '8'->b.getLong(pos)
            else->error("unsupported integer dtype $descr")
        }}
    }
    fun asFloats():FloatArray{
        val size=itemSize(descr);val b=ByteBuffer.wrap(raw).order(if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        require(raw.size-dataStart>=count*size){"Truncated weight array"}
        return FloatArray(count){i->val pos=dataStart+i*size;when(descr.lastOrNull()){
            '4'->if(descr.contains("f4"))b.getFloat(pos)else b.getInt(pos).toFloat()
            '8'->if(descr.contains("f8"))b.getDouble(pos).toFloat()else b.getLong(pos).toFloat()
            '2'->b.getShort(pos).toFloat()
            '1'->raw[pos].toFloat()
            else->error("unsupported weight dtype $descr")
        }}
    }
    fun asBooleans():BooleanArray{
        val size=itemSize(descr);require(raw.size-dataStart>=count*size){"Truncated mask array"}
        if (descr.contains("U") || descr.contains("S")) {
            val strings = asStrings()
            return BooleanArray(count) { i -> i < strings.size && (strings[i].equals("true", true) || strings[i] == "1" || strings[i].equals("t", true)) }
        }
        return BooleanArray(count){i-> when {
            descr.contains("b1")||descr.contains("u1")||descr.contains("i1") -> raw[dataStart+i].toInt()!=0
            else -> { val pos=dataStart+i*size;var any=false;for(j in 0 until size)if(raw[pos+j].toInt()!=0){any=true;break};any }
        }}
    }
    fun asStrings():Array<String>{
        val size=itemSize(descr);if(!(descr.contains("U")||descr.contains("S")))return emptyArray()
        val unicode=descr.contains("U");val unit=if(unicode)4 else 1;val chars=(size/unit).coerceAtLeast(1)
        val b=ByteBuffer.wrap(raw).order(if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        return Array(count){i->val out=StringBuilder();val base=dataStart+i*size
            for(k in 0 until chars){if(unicode){val cp=b.getInt(base+k*4);if(cp==0)break;if(Character.isValidCodePoint(cp))out.appendCodePoint(cp)}else{val cp=raw[base+k].toInt() and 255;if(cp==0)break;out.append(cp.toChar())}}
            out.toString().trim()
        }
    }
    private fun itemSize(d:String):Int{
        if(d.contains("U")){return (d.substringAfter("U").toIntOrNull()?:1)*4}
        if(d.contains("S")){return d.substringAfter("S").toIntOrNull()?:1}
        if(d.startsWith("|")){val tail=d.drop(1).dropWhile{!it.isDigit()};return tail.toIntOrNull()?:1}
        return when(d.takeLast(2)){"i1","u1","b1"->1;"i2","u2"->2;"i4","u4","f4"->4;"i8","u8","f8"->8;else->error("unknown dtype $d")}
    }
    companion object{
        fun parse(bytes:ByteArray):Npy{
            require(bytes.size>10&&bytes.copyOfRange(0,6).contentEquals(byteArrayOf(0x93.toByte(),78,85,77,80,89))) {"invalid NPY data"}
            val version=bytes[6].toInt() and 255
            val headerLen:Int;val headerStart:Int
            if(version<=1){headerLen=(bytes[8].toInt() and 255) or ((bytes[9].toInt() and 255) shl 8);headerStart=10}
            else{headerLen=(bytes[8].toInt() and 255) or ((bytes[9].toInt() and 255) shl 8) or ((bytes[10].toInt() and 255) shl 16) or ((bytes[11].toInt() and 255) shl 24);headerStart=12}
            require(headerLen>=0&&headerStart+headerLen<=bytes.size){"invalid NPY header"}
            val h=String(bytes,headerStart,headerLen,Charsets.US_ASCII)
            val descr=Regex("['\"]descr['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").find(h)?.groupValues?.get(1)?:error("NPY dtype missing")
            val fortran=Regex("['\"]fortran_order['\"]\\s*:\\s*(True|False)").find(h)?.groupValues?.get(1)=="True"
            require(!fortran){"Fortran-ordered NPZ array unsupported"}
            val shapeText=Regex("['\"]shape['\"]\\s*:\\s*\\(([^)]*)\\)").find(h)?.groupValues?.get(1)?:error("NPY shape missing")
            val shape=shapeText.split(',').mapNotNull{it.trim().takeIf{v->v.isNotEmpty()}?.toIntOrNull()}.toIntArray()
            val start=headerStart+headerLen
            return Npy(descr,shape,bytes,start)
        }
    }
}
