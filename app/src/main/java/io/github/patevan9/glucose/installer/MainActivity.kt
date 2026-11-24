package io.github.patevan9.glucose.installer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.flyfishxu.kadb.exception.AdbPairAuthException
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ClearBG Installer — Step 1 proof of concept: "Connect and Identify".
 *
 *   1. Find the watch on Wi-Fi (or type its address).
 *   2. Pair with the 6-digit code shown on the watch.
 *   3. Identify the watch (read-only).
 *
 * Nothing is installed, removed or changed on the watch.
 * Big text and high contrast on purpose.
 */
class MainActivity : androidx.activity.ComponentActivity() {

    private lateinit var finder: WatchFinder
    private var finderJob: Job? = null

    private lateinit var status: TextView      // step 3 (Identify) messages
    private lateinit var findMsg: TextView     // step 1 messages
    private lateinit var quickPair: LinearLayout
    private lateinit var quickTitle: TextView
    private lateinit var quickCode: EditText
    private lateinit var quickMsg: TextView
    private var quickTarget: MdnsEndpoint? = null
    private var lastState: com.flyfishxu.kadb.mdns.MdnsDiscoveryState? = null
    private val checked = mutableSetOf<String>()   // "host:port" already handshake-tested
    private var watchStatus: String? = null        // step 1 message about YOUR watch
    private lateinit var pairMsg: TextView     // step 2 messages
    private lateinit var test2Msg: TextView    // install / update messages
    private lateinit var foundList: LinearLayout
    private lateinit var pairHost: EditText
    private lateinit var pairPort: EditText
    private lateinit var pairCode: EditText
    private lateinit var connectHost: EditText
    private lateinit var connectPort: EditText
    private lateinit var result: TextView
    private lateinit var finishHeading: TextView
    private lateinit var updateButton: Button
    private lateinit var updateResult: TextView
    private var savedCopy = false
    private var plan: List<WatchLink.PlanItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchLink.setUp(this)
        finder = WatchFinder(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            setBackgroundColor(Color.BLACK)
        }

        page.addView(heading("ClearBG Installer"))
        page.addView(text("Installs and updates ClearBG Watch and its free watch face on your Wear OS watch. " +
            "It never removes anything from your watch.\n\nBy Lippy Robotics Labs."))

        // ---- Before you start ----
        page.addView(heading("Before you start"))
        page.addView(text(
            "• Phone and watch on the same Wi-Fi.\n" +
            "• Keep Bluetooth ON. Your glucose connection needs it.\n" +
            "• On the watch: Settings → Developer options → Wireless debugging → On."
        ))
        page.addView(text("Never used Developer options? Follow this picture once:"))
        page.addView(guideImage(R.drawable.guide_devoptions,
            "Picture: turn on Developer options, one time only. Samsung Galaxy Watch: Settings, About watch, " +
            "Software information, then tap Software version 7 times. Google Pixel Watch and most others: " +
            "Settings, System, About, Versions, then tap Build number 7 times. Then on every watch: " +
            "Settings, Developer options, turn ADB debugging on, then turn Wireless debugging on, " +
            "with the watch on the same Wi-Fi as your phone. Keep Bluetooth on."))

        // ---- Step 1: find ----
        page.addView(heading("1. Find my watch"))
        page.addView(button("Search Wi-Fi for my watch") { startSearch() })
        findMsg = msgView(); page.addView(findMsg)

        // Shown only while the watch is on its "Pair new device" screen:
        // just type the code here, no scrolling.
        quickPair = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(28, 28, 28))
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        quickTitle = text("").apply { setTextColor(GREEN); textSize = 21f; setTypeface(typeface, Typeface.BOLD) }
        quickPair.addView(quickTitle)
        quickCode = labeledField(quickPair, "3", GREEN, "6-digit code from the watch", "example 465438", Kind.CODE)
        quickPair.addView(button("Pair my watch") { doQuickPair() })
        quickMsg = msgView(); quickPair.addView(quickMsg)
        page.addView(quickPair)

        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(foundList)

        // ---- Step 2: pair ----
        page.addView(heading("2. Pair"))
        page.addView(text("Only if step 1 says your watch needs pairing, and no pairing box appeared above.\n\n" +
            "On the watch tap \"Pair new device\". It shows a 6-digit code, an IP address and a port. " +
            "Type them here (the picture below shows where they are)."))
        page.addView(text("Tip: if you paste the whole line (like 10.0.0.192:44747) in box 1, the app splits it for you."))
        pairHost = labeledField(page, "1", BLUE, "IP address", "example 10.0.0.192", Kind.IP)
        pairPort = labeledField(page, "2", YELLOW, "Pairing port", "example 44747", Kind.PORT)
        pairCode = labeledField(page, "3", GREEN, "6-digit code", "example 465438", Kind.CODE)
        page.addView(button("Pair") { doPair() })
        pairMsg = msgView(); page.addView(pairMsg)
        page.addView(guideImage(R.drawable.guide_pair,
            "Picture: the watch's Pair new device screen. 1, IP address 10.0.0.192 goes in the IP address box. " +
            "2, pairing port 44747 goes in the Pairing port box. 3, code 465438 goes in the 6-digit code box. " +
            "Your numbers will be different."))

        // ---- Step 3: identify ----
        page.addView(heading("3. Identify my watch"))
        page.addView(text("Use \"IP address and port\" on the main Wireless debugging screen (NOT the pairing port), like this:"))
        page.addView(guideImage(R.drawable.guide_connect,
            "Picture: the watch's Wireless debugging screen. 1, IP address 10.0.0.192 goes in the IP address box. " +
            "2, port 36801 goes in the Port box. Not the pairing port. Your numbers will be different."))
        connectHost = labeledField(page, "1", BLUE, "IP address", "example 10.0.0.192", Kind.IP)
        connectPort = labeledField(page, "2", YELLOW, "Port", "example 36801", Kind.PORT)
        page.addView(button("Identify watch (read-only)") { doIdentify() })

        status = msgView()
        page.addView(status)
        result = text("")
        page.addView(result)

        // ---- Step 4: install or update ----
        page.addView(heading("4. Install or update ClearBG Watch"))
        page.addView(text(
            "Do step 3 first (same IP address and port). Watch battery above 50%.\n\n" +
            "Tap \"Check my watch\" to see what will be installed. If ClearBG Watch is already on " +
            "your watch, your login, settings and readings are kept. Nothing is ever removed."
        ))
        page.addView(button("Check my watch") { doCheckUpdate() })
        updateButton = button("Install / update ClearBG Watch") { confirmUpdate() }.apply { isEnabled = false }
        page.addView(updateButton)
        test2Msg = msgView(); page.addView(test2Msg)
        updateResult = text("")
        page.addView(updateResult)
        page.addView(text("Optional: already have ClearBG Watch? You can first save a copy of the version on your watch to this phone's Downloads."))
        page.addView(button("Save a copy of what is on my watch") { confirmSaveCopy() })

        // ---- Step 4: finish (always shown, highlighted when the watch is done) ----
        finishHeading = heading("5. Finish: turn off Wireless debugging")
        page.addView(finishHeading)
        page.addView(text(
            "When you are done, on the watch:\n" +
            "  1. Open Settings\n" +
            "  2. Tap Developer options\n" +
            "  3. Tap Wireless debugging and turn it OFF\n\n" +
            "Keep Bluetooth ON. Your glucose connection needs it.\n\n" +
            "Optional: you can also turn Developer options off completely, with the switch at the top " +
            "of the Developer options screen. You would need to turn it on again for future updates."
        ))

        // Typing "10.0.0.192:44747" in an IP box fills both boxes.
        autoSplit(pairHost, pairPort)
        autoSplit(connectHost, connectPort)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(page)
        }
        // Android 15+ draws under the title and navigation bars.
        // Pad the page so no text is hidden behind them.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
    }

    // ---------------- Actions ----------------

    private fun startSearch() {
        findMsg.say("Searching… make sure Wireless debugging is on.")
        watchStatus = null
        checked.clear()
        finder.start()
        finderJob?.cancel()
        finderJob = lifecycleScope.launch {
            // If nothing shows up within 30 seconds, say so.
            launch {
                kotlinx.coroutines.delay(30_000)
                if (lastState?.allDevices.isNullOrEmpty()) {
                    findMsg.say("Nothing found yet. Check that Wireless debugging is on and both are on the same Wi-Fi, or type the numbers in step 2 and 3.")
                }
            }
            finder.state.collect { state ->
                lastState = state
                updateQuickPair(state)
                checkKnownWatch(state)
                updateFindMsg(state)
                foundList.removeAllViews()
                // Your known watch first; port 5555 devices (usually TVs / TV boxes) last.
                state.pairDevices.sortedBy(::rank).forEach { ep -> foundList.addView(endpointButton("Pair: ", ep, pairing = true)) }
                state.connectDevices.sortedBy(::rank).forEach { ep -> foundList.addView(endpointButton("Ready: ", ep, pairing = false)) }
                if (state.allDevices.isNotEmpty()) {
                    foundList.addView(text("Not sure which is your watch? On the watch, the Wireless debugging screen shows its IP address. Pick the one that matches."))
                }
            }
        }
    }

    private fun rank(ep: MdnsEndpoint) = when {
        knownWatch(ep) != null -> 0
        ep.port == 5555 -> 2
        else -> 1
    }

    private fun updateFindMsg(state: com.flyfishxu.kadb.mdns.MdnsDiscoveryState) {
        val n = state.allDevices.size
        findMsg.say(when {
            watchStatus != null -> watchStatus!!
            n == 0 -> "Searching… make sure Wireless debugging is on."
            state.allDevices.any { knownWatch(it) != null } -> "Found your watch. Checking if it is still paired…"
            else -> "Found $n device${if (n == 1) "" else "s"}. Pick the one that matches the IP address on your watch's Wireless debugging screen."
        })
    }

    /** Shows the easy pairing box while a watch is on its "Pair new device" screen. */
    private fun updateQuickPair(state: com.flyfishxu.kadb.mdns.MdnsDiscoveryState) {
        val target = state.pairDevices.filter { it.port != 5555 }.minByOrNull(::rank)
        if (target == null) {
            if (quickMsg.text.isNullOrEmpty()) quickPair.visibility = View.GONE
            return
        }
        quickTarget = target
        val who = knownWatch(target) ?: "Your watch"
        quickTitle.text = "⌚ $who is showing a pairing code.\nType the 6 digits from the watch:"
        quickPair.visibility = View.VISIBLE
    }

    private fun doQuickPair() {
        val target = quickTarget ?: return quickMsg.say("Open \"Pair new device\" on the watch first.")
        val code = quickCode.text.toString().trim()
        if (code.length != 6 || !code.all { it.isDigit() }) return quickMsg.say("The code must be 6 digits.")
        quickMsg.say("Pairing…")
        lifecycleScope.launch {
            try {
                WatchLink.pair(target.host, target.port, code)
                quickMsg.say("✅ Paired!")
                quickCode.setText("")
                // Fill in step 3 if the watch's normal address is known.
                val serial = serialOf(target)
                val ready = lastState?.connectDevices?.firstOrNull { serialOf(it) == serial }
                watchStatus = if (ready != null) {
                    connectHost.setText(ready.host); connectPort.setText(ready.port.toString())
                    "✅ Paired. Step 3 is filled in for you. Tap \"Identify watch\"."
                } else {
                    "✅ Paired. Now do step 3."
                }
                findMsg.say(watchStatus!!)
                checked.clear()
            } catch (e: com.flyfishxu.kadb.exception.AdbPairAuthException) {
                quickMsg.say("Pairing failed: the code may be wrong or expired. Open \"Pair new device\" again.")
            } catch (e: Exception) {
                quickMsg.say("Pairing failed: ${plain(e)}")
            }
        }
    }

    /** For a watch this phone knows: quietly test the handshake (read-only). */
    private fun checkKnownWatch(state: com.flyfishxu.kadb.mdns.MdnsDiscoveryState) {
        val ep = state.connectDevices.firstOrNull { knownWatch(it) != null } ?: return
        val key = "${ep.host}:${ep.port}"
        if (!checked.add(key)) return
        lifecycleScope.launch {
            when (WatchLink.isPaired(ep.host, ep.port)) {
                true -> {
                    connectHost.setText(ep.host); connectPort.setText(ep.port.toString())
                    watchStatus = "✅ Found your watch, and it is already paired.\nStep 3 is filled in for you: skip step 2 and tap \"Identify watch\"."
                }
                false -> watchStatus = "Found your watch, but it needs pairing.\nOn the watch tap \"Pair new device\". A pairing box will appear here."
                null -> watchStatus = "Found your watch. Tap the green button."
            }
            findMsg.say(watchStatus!!)
        }
    }

    /** The device's serial number, from its Wi-Fi name "adb-SERIAL-xxxxxx". */
    private fun serialOf(ep: MdnsEndpoint): String =
        ep.name.removePrefix("adb-").let { n -> if (n.length > 7 && n[n.length - 7] == '-') n.dropLast(7) else n }

    private val prefs by lazy { getSharedPreferences("known_watches", MODE_PRIVATE) }

    /** "Samsung SM-R870" if this phone has identified that watch before, else null. */
    private fun knownWatch(ep: MdnsEndpoint): String? =
        serialOf(ep).takeIf { it.isNotBlank() }?.let { prefs.getString(it, null) }

    private fun endpointButton(label: String, ep: MdnsEndpoint, pairing: Boolean): View {
        val known = knownWatch(ep)
        val what = when {
            known != null -> "⌚ YOUR WATCH: $known"
            ep.port == 5555 -> "Probably a TV or TV box (not a watch)"
            else -> "Watch or phone (not identified yet)"
        }
        return button("$label${ep.host}:${ep.port}\n$what") {}.apply {
            if (known != null) setTextColor(GREEN)
            contentDescription = "$label ${ep.host} port ${ep.port}. $what"
            setOnClickListener {
                if (pairing) {
                    pairHost.setText(ep.host); pairPort.setText(ep.port.toString())
                    pairCode.requestFocus()
                    pairMsg.say("Now type the 6-digit code shown on the watch.")
                } else {
                    connectHost.setText(ep.host); connectPort.setText(ep.port.toString())
                    status.say("Address filled in. Tap \"Identify watch\".")
                }
            }
        }
    }

    private fun doPair() {
        val host = pairHost.text.toString().trim()
        val port = pairPort.text.toString().toIntOrNull()
        val code = pairCode.text.toString().trim()
        if (!validHost(host) || port == null || port !in 1..65535) return pairMsg.say("Please check the IP address and pairing port.")
        if (code.length != 6 || !code.all { it.isDigit() }) return pairMsg.say("The code must be 6 digits.")
        pairMsg.say("Pairing…")
        lifecycleScope.launch {
            try {
                WatchLink.pair(host, port, code)
                connectHost.setText(host)
                pairMsg.say("✅ Paired! Now do step 3.")
            } catch (e: AdbPairAuthException) {
                pairMsg.say("Pairing failed: the code may be wrong or expired. Open \"Pair new device\" again.")
            } catch (e: Exception) {
                pairMsg.say("Pairing failed: ${plain(e)}")
            }
        }
    }

    private fun doIdentify() {
        val host = connectHost.text.toString().trim()
        val port = connectPort.text.toString().toIntOrNull()
        if (!validHost(host) || port == null || port !in 1..65535) return status.say("Please check the IP address and port.")
        status.say("Connecting… if the watch asks \"Allow debugging?\", tap Allow.")
        result.text = ""
        lifecycleScope.launch {
            try {
                val w = WatchLink.identify(host, port)
                // Remember this watch (on this phone only) so the search list can label it.
                if (w.isWatch && w.serial.isNotBlank()) {
                    val name = "${w.manufacturer.replaceFirstChar { it.uppercase() }} ${w.model}"
                    prefs.edit().putString(w.serial, name).apply()
                }
                status.say("✅ Watch identified. Nothing was changed.\nNext: step 4 below, \"Check my watch\".")
                finishHeading.setTextColor(Color.YELLOW)
                result.text = buildString {
                    appendLine("Watch: ${w.manufacturer} ${w.model} (${w.deviceName})")
                    appendLine("System: ${w.wearOs}, Android ${w.androidVersion} (API ${w.sdkLevel})")
                    appendLine("Is a watch: ${if (w.isWatch) "Yes" else "No — this may not be a watch"}")
                    appendLine("Processor: ${w.cpu}")
                    appendLine()
                    appendLine("ClearBG Watch app: ${w.appVersion ?: "not installed"}")
                    appendLine("ClearBG Watch Face: ${w.faceVersion ?: "not installed"}")
                    appendLine("Watch face supported: ${if (w.faceSupported) "Yes" else "No (needs Wear OS 4 or newer)"}")
                }
            } catch (e: Exception) {
                status.say("Could not connect: ${plain(e)}\nIs the watch paired and Wireless debugging still on?")
            }
        }
    }

    // ---------------- Test 2 ----------------

    /** IP and port from step 3, or null (and a message) if not filled in. */
    private fun step3Address(): Pair<String, Int>? {
        val host = connectHost.text.toString().trim()
        val port = connectPort.text.toString().toIntOrNull()
        if (!validHost(host) || port == null || port !in 1..65535) {
            test2Msg.say("Please fill in step 3 (IP address and Port) first.")
            return null
        }
        return host to port
    }

    private fun confirmSaveCopy() {
        val (host, port) = step3Address() ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Save a copy?")
            .setMessage("This copies the ClearBG Watch app and watch face FROM your watch to this phone's Downloads, " +
                "folder \"${PhoneDownloads.FOLDER}\".\n\nNothing on the watch is changed.")
            .setPositiveButton("Save copy") { _, _ ->
                test2Msg.say("Saving a copy… keep the watch nearby and awake.")
                lifecycleScope.launch {
                    try {
                        val files = WatchLink.saveCopies(this@MainActivity, host, port)
                        if (files.isEmpty()) {
                            test2Msg.say("ClearBG Watch is not on this watch, so there is nothing to save.")
                        } else {
                            savedCopy = true
                            test2Msg.say("✅ Copy saved. Nothing on the watch was changed.")
                            updateResult.text = "Saved in Downloads → ${PhoneDownloads.FOLDER}:\n• " + files.joinToString("\n• ")
                        }
                    } catch (e: Exception) {
                        test2Msg.say("Could not save a copy: ${plain(e)}\nNothing on the watch was changed.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doCheckUpdate() {
        val (host, port) = step3Address() ?: return
        test2Msg.say("Checking versions… (read-only)")
        updateButton.isEnabled = false
        lifecycleScope.launch {
            try {
                plan = WatchLink.checkUpdate(this@MainActivity, host, port)
                updateResult.text = buildString {
                    appendLine("ClearBG Watch files checked: genuine ✅\n")
                    for (p in plan) {
                        val line = when (p.action) {
                            WatchLink.Action.UPDATE -> "${p.onWatchName} → ${p.newName}  (update)"
                            WatchLink.Action.INSTALL -> "not installed → ${p.newName}  (install)"
                            WatchLink.Action.SAME -> "${p.onWatchName}  (already up to date)"
                            WatchLink.Action.WATCH_IS_NEWER -> "${p.onWatchName} on watch is newer. Will NOT change."
                            WatchLink.Action.NOT_SUPPORTED -> "needs Wear OS 4 or newer. Skipped. (You can still add your glucose to other watch faces.)"
                        }
                        appendLine("${p.bundled.label}:\n   $line")
                    }
                }
                val anything = plan.any { it.action == WatchLink.Action.UPDATE || it.action == WatchLink.Action.INSTALL }
                updateButton.isEnabled = anything
                test2Msg.say(if (anything) "Ready. Tap \"Install / update ClearBG Watch\" when you are ready." else "Your watch is up to date. Nothing to do.")
            } catch (e: Exception) {
                test2Msg.say("Could not check: ${plain(e)}\nNothing on the watch was changed.")
            }
        }
    }

    private fun confirmUpdate() {
        val (host, port) = step3Address() ?: return
        val changes = plan.filter { it.action == WatchLink.Action.UPDATE || it.action == WatchLink.Action.INSTALL }
            .joinToString("\n") { "• ${it.bundled.label}: ${it.onWatchName ?: "new"} → ${it.newName}" }
        val isUpdate = plan.any { it.action == WatchLink.Action.UPDATE }
        val extra = if (isUpdate) "\n\nYour login and settings are kept.\n\n" +
            "The watch may switch to another watch face during the update. If it does, choose ClearBG Watch Face again."
            else ""
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isUpdate) "Update ClearBG Watch?" else "Install ClearBG Watch?")
            .setMessage("$changes$extra")
            .setPositiveButton(if (isUpdate) "Update" else "Install") { _, _ -> doUpdate(host, port) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doUpdate(host: String, port: Int) {
        updateButton.isEnabled = false
        val firstInstall = plan.firstOrNull()?.action == WatchLink.Action.INSTALL
        test2Msg.say("Working… keep the watch nearby and awake. Do not close this app.")
        updateResult.text = ""
        lifecycleScope.launch {
            try {
                WatchLink.runUpdate(this@MainActivity, host, port) { line ->
                    runOnUiThread { updateResult.append(line + "\n") }
                }
                if (firstInstall) {
                    test2Msg.say("✅ ClearBG Watch is installed!")
                    updateResult.append(
                        "\nNow on the watch:\n" +
                        "1. Open ClearBG from the app list. Choose your CGM and sign in.\n" +
                        "2. Watch face: press and hold your watch face, swipe to ClearBG Watch Face and tap it.\n" +
                        "3. Then do step 5 below."
                    )
                } else {
                    test2Msg.say("✅ ClearBG Watch is updated!")
                    updateResult.append(
                        "\nNow check on the watch:\n" +
                        "1. Open ClearBG: your glucose shows.\n" +
                        "2. Watch face: if it changed, press and hold it and choose ClearBG Watch Face again.\n" +
                        "3. Then do step 5 below."
                    )
                }
                finishHeading.setTextColor(Color.YELLOW)
            } catch (e: Exception) {
                test2Msg.say("Stopped: ${plain(e)}\nNothing was removed. Anything not marked ✅ was not changed.")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        finderJob?.cancel()
        finder.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        finder.close()
    }

    // ---------------- Helpers ----------------

    /** A picture that shows which numbers on the watch go in which box. */
    private fun guideImage(res: Int, description: String) = android.widget.ImageView(this).apply {
        setImageResource(res)
        adjustViewBounds = true
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setPadding(0, dp(4), 0, dp(16))
    }

    /** If someone types "IP:port" into the IP box, move the port into the port box. */
    private fun autoSplit(hostField: EditText, portField: EditText) {
        // If an IP address is typed in the PORT box by mistake (it has dots),
        // move it to the IP address box and keep typing there.
        portField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString() ?: return
                if (!t.contains('.')) return
                val m = Regex("""^\s*([0-9.]+)\s*(?::\s*([0-9]{0,5}))?\s*$""").find(t)
                val ip = m?.groupValues?.get(1) ?: t.filter { it.isDigit() || it == '.' }
                val port = m?.groupValues?.getOrNull(2).orEmpty()
                portField.setText(port)
                hostField.setText(ip)
                hostField.requestFocus()
                hostField.setSelection(hostField.text.length)
                (if (portField === pairPort) pairMsg else status)
                    .say("That looks like an IP address, so it was moved to the IP address box (1).")
            }
        })
        hostField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString() ?: return
                val m = Regex("""^\s*([0-9.]+)\s*:\s*([0-9]{1,5})\s*$""").find(t) ?: return
                hostField.setText(m.groupValues[1])
                hostField.setSelection(hostField.text.length)
                portField.setText(m.groupValues[2])
            }
        })
    }

    /** Only allows an IP address or simple host name, nothing else. */
    private fun validHost(h: String) = h.isNotEmpty() && h.length <= 253 && h.all { it.isLetterOrDigit() || it in ".:-%" }

    private fun plain(e: Exception) = e.message?.take(160) ?: e.javaClass.simpleName

    /** Each step shows its own messages right under its own button. */
    private fun TextView.say(msg: String) { text = msg }

    private fun msgView() = text("").apply { setTextColor(Color.YELLOW); textSize = 19f }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(t: String) = TextView(this).apply {
        text = t; textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun text(t: String) = TextView(this).apply {
        text = t; textSize = 19f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(4))
    }

    private enum class Kind { IP, PORT, CODE }

    /**
     * A box with a label that stays visible while typing (the grey hint
     * disappears), numbered and colored like the guide pictures.
     */
    private fun labeledField(page: LinearLayout, num: String, color: Int, label: String, hint: String, kind: Kind): EditText {
        val title = android.text.SpannableStringBuilder()
        val start = title.length
        title.append(" $num ")
        title.setSpan(android.text.style.BackgroundColorSpan(color), start, title.length, 0)
        title.setSpan(android.text.style.ForegroundColorSpan(Color.BLACK), start, title.length, 0)
        title.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, title.length, 0)
        val labelStart = title.length
        title.append("  $label")
        title.setSpan(android.text.style.ForegroundColorSpan(color), labelStart, title.length, 0)
        title.setSpan(android.text.style.StyleSpan(Typeface.BOLD), labelStart, title.length, 0)
        page.addView(TextView(this).apply {
            text = title; textSize = 20f; setPadding(0, dp(14), 0, 0)
        })
        val box = EditText(this).apply {
            this.hint = hint; textSize = 22f
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            contentDescription = "$num, $label"
            when (kind) {
                // Accept digits, dots and a colon. Keyboard shows dots and the colon.
                Kind.IP -> {
                    keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.:")
                    // Number keys with a dot, like the other boxes.
                    setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                }
                // Number pad; a typed dot is accepted so an IP typed here can be moved (see autoSplit).
                Kind.PORT -> {
                    keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.:")
                    setRawInputType(InputType.TYPE_CLASS_NUMBER)
                }
                Kind.CODE -> inputType = InputType.TYPE_CLASS_NUMBER
            }
        }
        page.addView(box)
        return box
    }

    private companion object {
        val BLUE = Color.rgb(124, 200, 255)
        val YELLOW = Color.rgb(255, 225, 77)
        val GREEN = Color.rgb(110, 231, 160)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 19f; isAllCaps = false
        setOnClickListener { onClick() }
    }
}
