package tn.loukious.facebookappadsremover.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import tn.loukious.facebookappadsremover.R

/**
 * Settings screen — the toggle surface for every feature the module ports.
 * Refactored to follow Full MVVM & Unidirectional Data Flow (UDF) architecture.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel by viewModels<SettingsViewModel>()

    private lateinit var serviceHint: TextView
    private lateinit var togglesList: LinearLayout
    private val rows = HashMap<String, MaterialSwitch>()
    private var keywordInput: EditText? = null
    private var launcherSwitch: MaterialSwitch? = null

    private val pickSessionFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.processIntent(this, SettingsUserIntent.ImportSessionFilePicked(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupViews()
        viewModel.initService(this)

        observeStateAndEffects()
    }

    private fun setupViews() {
        serviceHint = TextView(this).apply {
            text = getString(R.string.service_waiting)
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }

        togglesList = findViewById(R.id.toggles)
        togglesList.addView(serviceHint)

        for (section in TOGGLE_SECTIONS) {
            addSectionHeader(togglesList, section.title)
            for (toggle in section.toggles) {
                addToggleRow(togglesList, toggle)
            }
            when (section.extra) {
                SectionExtra.KEYWORDS -> addKeywordInput(togglesList)
                SectionExtra.SESSION -> addSessionButtons(togglesList)
                SectionExtra.LAUNCHER -> addLauncherIconRow(togglesList)
                SectionExtra.NONE -> {}
            }
        }
    }

    private fun observeStateAndEffects() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.uiEffect.collect { effect ->
                        handleEffect(effect)
                    }
                }
            }
        }
    }

    private fun renderState(state: SettingsUiState) {
        serviceHint.text = getString(
            if (state.isServiceBound) R.string.service_ready else R.string.service_waiting
        )

        for (spec in TOGGLE_SECTIONS.flatMap { it.toggles }) {
            val switch = rows[spec.key] ?: continue
            val stateChecked = state.toggleValues[spec.key] ?: spec.default
            switch.isEnabled = state.isServiceBound
            if (switch.isChecked != stateChecked) {
                switch.isChecked = stateChecked
            }
        }

        keywordInput?.let { input ->
            input.isEnabled = state.isServiceBound
            if (input.text.toString() != state.keywords) {
                input.setText(state.keywords)
            }
        }

        launcherSwitch?.let { switch ->
            if (switch.isChecked != state.isLauncherIconVisible) {
                switch.isChecked = state.isLauncherIconVisible
            }
        }
    }

    private fun handleEffect(effect: SettingsUiEffect) {
        when (effect) {
            is SettingsUiEffect.ShowToast -> {
                Toast.makeText(this, effect.message, Toast.LENGTH_SHORT).show()
            }
            is SettingsUiEffect.ShowHideLauncherIconConfirmDialog -> {
                AlertDialog.Builder(this)
                    .setTitle("Hide the launcher icon?")
                    .setMessage(
                        "The icon disappears from your app drawer. Facebook keeps working, and so do its hooks.\n\n" +
                            "To open this screen again:\n" +
                            "•  Xposed/Vector → Modules → Facebook App Ads Remover → open settings\n" +
                            "•  adb shell am start -n tn.loukious.facebookappadsremover/.ui.MainActivity"
                    )
                    .setNegativeButton("Cancel") { _, _ ->
                        launcherSwitch?.isChecked = true
                    }
                    .setOnCancelListener {
                        launcherSwitch?.isChecked = true
                    }
                    .setPositiveButton("Hide") { _, _ ->
                        viewModel.processIntent(this, SettingsUserIntent.ConfirmHideLauncherIcon)
                    }
                    .show()
            }
        }
    }

    private fun addSectionHeader(parent: ViewGroup, title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 13f
        tv.isAllCaps = true
        tv.letterSpacing = 0.1f
        tv.setPadding(dp(4), dp(24), dp(4), dp(8))
        parent.addView(tv)
    }

    private fun addToggleRow(parent: ViewGroup, spec: ToggleSpec) {
        val switch = buildToggleRow(parent, spec.title, spec.subtitle)
        switch.isChecked = spec.default
        switch.isEnabled = false
        switch.setOnCheckedChangeListener { _, checked ->
            viewModel.processIntent(this, SettingsUserIntent.ToggleChanged(spec.key, checked))
        }
        rows[spec.key] = switch
    }

    private fun buildToggleRow(parent: ViewGroup, title: String, subtitle: String): MaterialSwitch {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(10), dp(4), dp(10))

        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 16f
        text.addView(titleView)

        val subtitleView = TextView(this)
        subtitleView.text = subtitle
        subtitleView.textSize = 13f
        subtitleView.setPadding(0, dp(2), 0, 0)
        text.addView(subtitleView)
        row.addView(text)

        val switch = MaterialSwitch(this)
        row.addView(switch)

        row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
        parent.addView(row)
        return switch
    }

    private fun addKeywordInput(parent: ViewGroup) {
        val layout = TextInputLayout(
            this, null,
            com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox,
        ).apply {
            hint = "e.g. giveaway, crypto, follow me on"
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }

        val input = TextInputEditText(layout.context)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        input.setSingleLine(false)
        input.minLines = 1
        input.maxLines = 3
        input.isEnabled = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                if (viewModel.uiState.value.keywords != text) {
                    viewModel.processIntent(this@MainActivity, SettingsUserIntent.KeywordsChanged(text))
                }
            }
        })
        keywordInput = input
        layout.addView(input)
        parent.addView(layout)
    }

    private fun addSessionButtons(parent: ViewGroup) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(4), dp(12), dp(4), dp(4))

        val export = Button(this)
        export.text = "Export session"
        export.setOnClickListener {
            viewModel.processIntent(this, SettingsUserIntent.ExportSessionRequested)
        }
        row.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val spacer = View(this)
        row.addView(spacer, LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.WRAP_CONTENT))

        val import = Button(this)
        import.text = "Import latest"
        import.setOnClickListener {
            viewModel.processIntent(this, SettingsUserIntent.ImportLatestSessionRequested)
        }
        row.addView(import, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        parent.addView(row)

        val fromFile = Button(this)
        fromFile.text = "Import from file…"
        fromFile.setOnClickListener {
            pickSessionFile.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        parent.addView(fromFile)

        val caption = TextView(this)
        caption.text = "Export copies the login (authentication/logged_in) files + cookies to Download/FacebookAppAdsRemover. \"Import latest\" restores the newest export; \"Import from file\" lets you browse to any FBAR-Session-*.json (e.g. one you copied to another folder or device). Force-stop Facebook afterwards."
        caption.textSize = 13f
        caption.setPadding(dp(4), 0, dp(4), dp(8))
        parent.addView(caption)
    }

    private fun addLauncherIconRow(parent: ViewGroup) {
        val switch = buildToggleRow(
            parent,
            "Show launcher icon",
            "Hide this app's icon from the launcher. Everything keeps working — reopen this screen from the Xposed module list, or with the adb command shown before it's hidden",
        )
        switch.isChecked = LauncherIcon.isVisible(this)
        switch.isEnabled = true

        switch.setOnCheckedChangeListener { _, checked ->
            if (switch.isPressed) {
                viewModel.processIntent(
                    this,
                    SettingsUserIntent.LauncherIconToggleRequested(checked)
                )
            }
        }
        launcherSwitch = switch
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
