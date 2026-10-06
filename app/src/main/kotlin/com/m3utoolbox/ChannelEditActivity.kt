package com.m3utoolbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ChannelEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_RESULT_CHANNEL = "extra_result_channel"
        const val EXTRA_TARGET_CATEGORY_NAME = "target_category_name"
    }

    private lateinit var editTvgId: EditText
    private lateinit var editTvgName: EditText
    private lateinit var editTvgLogo: EditText
    private lateinit var spinnerGroupTitle: Spinner
    private lateinit var btnAddNewCategory: Button
    private lateinit var editUrl: EditText
    private lateinit var editUserAgent: EditText
    private lateinit var editReferer: EditText
    private lateinit var editOrigin: EditText
    private lateinit var editHost: EditText
    private lateinit var editConnection: EditText
    private lateinit var customHeadersContainer: LinearLayout
    private lateinit var btnAddRequestHeader: Button
    private lateinit var btnParseRequestHeaders: Button
    private lateinit var saveButton: Button

    private var channel: Channel? = null
    private var isEditingExisting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_edit)

        editTvgId = findViewById(R.id.editTvgId)
        editTvgName = findViewById(R.id.editTvgName)
        editTvgLogo = findViewById(R.id.editTvgLogo)
        spinnerGroupTitle = findViewById(R.id.spinnerGroupTitle)
        btnAddNewCategory = findViewById(R.id.btnAddNewCategory)
        editUrl = findViewById(R.id.editUrl)
        editUserAgent = findViewById(R.id.editUserAgent)
        editReferer = findViewById(R.id.editReferer)
        editOrigin = findViewById(R.id.editOrigin)
        editHost = findViewById(R.id.editHost)
        editConnection = findViewById(R.id.editConnection)
        customHeadersContainer = findViewById(R.id.customHeadersContainer)
        btnAddRequestHeader = findViewById(R.id.btnAddRequestHeader)
        btnParseRequestHeaders = findViewById(R.id.btnParseRequestHeaders)
        saveButton = findViewById(R.id.saveButton)

        channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        }
        isEditingExisting = channel != null

        // 加载分类列表
        val categoryList = intent.getStringArrayListExtra("category_list")
        val categoryNames = categoryList?.toMutableList() ?: m3uFileCategories()

        val currentGroupTitle = channel?.extinfAttributes?.get("group-title") ?: ""
        val selectedIndex = if (currentGroupTitle.isNotEmpty() && currentGroupTitle in categoryNames) {
            categoryNames.indexOf(currentGroupTitle)
        } else {
            0
        }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categoryNames)
        spinnerGroupTitle.adapter = spinnerAdapter
        spinnerGroupTitle.setSelection(selectedIndex)

        // 加载频道数据
        channel?.let { ch ->
            editTvgId.setText(ch.extinfAttributes["tvg-id"].orEmpty())
            editTvgName.setText(ch.extinfAttributes["tvg-name"].orEmpty().ifEmpty { ch.displayName })
            editTvgLogo.setText(ch.extinfAttributes["tvg-logo"].orEmpty())
            editUrl.setText(ch.getFullUrl())

            val headers = ch.getRequestHeaders()
            editUserAgent.setText(findHeader(headers, "User-Agent").orEmpty())
            editReferer.setText(findHeader(headers, "Referer").orEmpty())
            editOrigin.setText(findHeader(headers, "Origin").orEmpty())
            editHost.setText(findHeader(headers, "Host").orEmpty())
            editConnection.setText(findHeader(headers, "Connection").orEmpty())

            ch.requestHeaders.forEach { header ->
                if (!isBuiltInRequestHeader(header.key)) {
                    addRequestHeaderRow(header.key, header.value)
                }
            }
        }

        // 新增请求头按钮
        btnAddRequestHeader.setOnClickListener {
            addRequestHeaderRow("", "")
        }

        // 请求头解析按钮：支持直接粘贴浏览器/抓包工具的原始请求头。
        btnParseRequestHeaders.setOnClickListener {
            showRequestHeaderParseDialog()
        }

        // 新增分类按钮
        btnAddNewCategory.setOnClickListener {
            val inputName = EditText(this).apply { hint = "分类名称" }
            AlertDialog.Builder(this)
                .setTitle("新增分类")
                .setView(inputName)
                .setPositiveButton("添加") { _, _ ->
                    val name = inputName.text.toString().trim()
                    if (name.isNotEmpty()) {
                        // 添加到 intent 的 category_list
                        val existingList = intent.getStringArrayListExtra("category_list")
                        val newList = existingList?.toMutableList() ?: mutableListOf()
                        newList.add(name)
                        // 更新 Spinner
                        val newAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, newList)
                        spinnerGroupTitle.adapter = newAdapter
                        spinnerGroupTitle.setSelection(newList.size - 1)
                        // 保存新的分类列表回 intent extra（供后续使用）
                        Toast.makeText(this, "分类已添加", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        saveButton.setOnClickListener { saveChannel() }
    }

    private fun findHeader(headers: Map<String, String>, name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun isBuiltInRequestHeader(name: String): Boolean {
        return when {
            name.equals("User-Agent", ignoreCase = true) -> true
            name.equals("Referer", ignoreCase = true) -> true
            name.equals("Referrer", ignoreCase = true) -> true
            name.equals("Origin", ignoreCase = true) -> true
            name.equals("Host", ignoreCase = true) -> true
            name.equals("Connection", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun addRequestHeaderRow(initialKey: String, initialValue: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "custom_request_header"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * resources.displayMetrics.density).toInt()
            }
        }

        val keyEdit = EditText(this).apply {
            hint = "请求头名称"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialKey)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueEdit = EditText(this).apply {
            hint = "请求头参数"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialValue)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                marginStart = (6 * resources.displayMetrics.density).toInt()
            }
        }

        val deleteButton = Button(this).apply {
            text = "删除"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { customHeadersContainer.removeView(row) }
        }

        row.addView(keyEdit)
        row.addView(valueEdit)
        row.addView(deleteButton)
        customHeadersContainer.addView(row)
    }

    /**
     * 打开批量请求头解析对话框。解析成功后会替换当前请求头编辑区，
     * 从而保证“解析结果 = 页面实际显示结果”，点击页面保存后再写回频道。
     */
    /**
     * 打开批量请求头解析对话框。
     *
     * 使用“固定高度 + 输入区 weight=1 + 底部按钮区 wrap_content”的自定义布局，
     * 不使用 AlertDialog 自带的底部按钮。这样无论粘贴多少请求头文本，
     * 输入框都会在自己的区域内滚动，"解析" / "取消" 始终固定显示并可点击。
     */
    private fun showRequestHeaderParseDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val input = EditText(this).apply {
            hint = "粘贴原始请求头，例如：\n:method: GET\n:authority: example.com\n:path: /live.m3u8?token=xxx\n:scheme: https\nuser-agent: Mozilla/5.0\nreferer: https://www.example.com/\norigin: https://www.example.com\naccept: */*"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val description = android.widget.TextView(this).apply {
            text = "支持直接粘贴浏览器、WebView、HTTP/2 抓包文本。:authority 会转换成 Host；:method、:path、:scheme 等 HTTP/2 伪请求头不会直接保存为普通请求头。解析结果会替换当前请求头编辑区。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8))
        }

        val cancelButton = Button(this).apply {
            text = "取消"
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        }

        val parseButton = Button(this).apply {
            text = "解析"
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            addView(cancelButton)
            addView(parseButton)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            addView(description)
            addView(input)
            addView(buttonRow)
        }

        // 高度固定，确保 EditText 只在中间区域滚动，底部按钮永远不会被文本顶走。
        val screenHeight = resources.displayMetrics.heightPixels
        val dialogHeight = minOf(dp(560), (screenHeight * 0.78f).toInt())
        root.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dialogHeight
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("请求头解析")
            .setView(root)
            .create()

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        parseButton.setOnClickListener {
            val parsed = parseRawRequestHeaders(input.text.toString())
            if (parsed.headers.isEmpty()) {
                Toast.makeText(this, "未识别到有效请求头，请检查粘贴内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 解析结果是“替换”，不是追加。先把当前所有请求头输入清空。
            editUserAgent.setText("")
            editReferer.setText("")
            editOrigin.setText("")
            editHost.setText("")
            editConnection.setText("")
            customHeadersContainer.removeAllViews()

            parsed.headers.forEach { (name, value) ->
                when {
                    name.equals("User-Agent", ignoreCase = true) -> editUserAgent.setText(value)
                    name.equals("Referer", ignoreCase = true) -> editReferer.setText(value)
                    name.equals("Origin", ignoreCase = true) -> editOrigin.setText(value)
                    name.equals("Host", ignoreCase = true) -> editHost.setText(value)
                    name.equals("Connection", ignoreCase = true) -> editConnection.setText(value)
                    else -> addRequestHeaderRow(name, value)
                }
            }

            val ignored = parsed.ignoredPseudoHeaders
            val suffix = if (ignored.isEmpty()) {
                ""
            } else {
                "，已忽略 HTTP/2 伪请求头：${ignored.joinToString(", ")}"
            }
            Toast.makeText(
                this,
                "已解析 ${parsed.headers.size} 个请求头$suffix；请点击页面“保存”正式写入频道",
                Toast.LENGTH_LONG
            ).show()
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                minOf((resources.displayMetrics.widthPixels * 0.94f).toInt(), dp(560)),
                dialogHeight
            )
            input.requestFocus()
        }
        dialog.show()
    }

    private data class ParsedRequestHeaders(
        val headers: LinkedHashMap<String, String>,
        val ignoredPseudoHeaders: List<String>
    )

    /**
     * 解析常见的 HTTP/1.1 与 HTTP/2 原始请求文本。
     * 最后一项同名请求头覆盖前一项，大小写不敏感。
     */
    private fun parseRawRequestHeaders(raw: String): ParsedRequestHeaders {
        val headers = linkedMapOf<String, String>()
        val ignoredPseudoHeaders = linkedSetOf<String>()

        fun putHeader(name: String, value: String) {
            val cleanName = name.trim()
            val cleanValue = value.trim()
            if (cleanName.isEmpty() || cleanValue.isEmpty()) return

            val canonicalName = when (cleanName.lowercase()) {
                "user-agent" -> "User-Agent"
                "referer", "referrer" -> "Referer"
                "origin" -> "Origin"
                "host" -> "Host"
                "connection" -> "Connection"
                else -> cleanName
            }

            val oldKey = headers.keys.firstOrNull { it.equals(canonicalName, ignoreCase = true) }
            if (oldKey != null) headers.remove(oldKey)
            headers[canonicalName] = cleanValue
        }

        raw.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { rawLine ->
            var line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("\uFEFF")) line = line.removePrefix("\uFEFF").trim()

            // 忽略请求行，例如：GET /xxx.m3u8 HTTP/1.1
            if (Regex("""^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|CONNECT|TRACE)\s+.+HTTP/\d(?:\.\d)?$""", RegexOption.IGNORE_CASE).matches(line)) {
                return@forEach
            }
            // 忽略响应状态行，例如：HTTP/1.1 200 OK
            if (Regex("""^HTTP/\d(?:\.\d)?\s+\d{3}\b.*$""", RegexOption.IGNORE_CASE).matches(line)) {
                return@forEach
            }

            // HTTP/2 伪请求头格式：:authority: host
            if (line.startsWith(":")) {
                val secondColon = line.indexOf(':', startIndex = 1)
                if (secondColon <= 1) return@forEach
                val pseudoName = line.substring(1, secondColon).trim().lowercase()
                val value = line.substring(secondColon + 1).trim()
                when (pseudoName) {
                    "authority" -> putHeader("Host", value)
                    else -> ignoredPseudoHeaders.add(":$pseudoName")
                }
                return@forEach
            }

            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach
            if (name.any { it.isWhitespace() }) return@forEach
            putHeader(name, value)
        }

        return ParsedRequestHeaders(headers, ignoredPseudoHeaders.toList())
    }

    private fun m3uFileCategories(): MutableList<String> {
        // 由于 ChannelEditActivity 无法直接访问 MainActivity 的 m3uFile，
        // 这里返回一个包含默认分类的列表
        return mutableListOf("未分类")
    }

    private fun getSelectedCategoryName(): String {
        return spinnerGroupTitle.selectedItem.toString()
    }

    private fun saveChannel() {
        val fullUrl = editUrl.text.toString().trim()
        if (fullUrl.isEmpty()) {
            Toast.makeText(this, "视频链接不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (!fullUrl.startsWith("http://", true) &&
            !fullUrl.startsWith("https://", true) &&
            !fullUrl.startsWith("rtsp://", true)
        ) {
            Toast.makeText(this, "视频链接必须是 HTTP/HTTPS/RTSP 地址", Toast.LENGTH_SHORT).show()
            return
        }

        val tvgId = editTvgId.text.toString().trim()
        val tvgName = editTvgName.text.toString().trim()
        val tvgLogo = editTvgLogo.text.toString().trim()
        val groupTitle = getSelectedCategoryName()
        val userAgent = editUserAgent.text.toString().trim()
        val referer = editReferer.text.toString().trim()
        val origin = editOrigin.text.toString().trim()
        val host = editHost.text.toString().trim()
        val connection = editConnection.text.toString().trim()

        val old = channel
        val updated = Channel(
            displayName = tvgName.ifEmpty { old?.displayName ?: "未命名" },
            url = ""
        )

        if (tvgId.isNotEmpty()) updated.extinfAttributes["tvg-id"] = tvgId
        if (tvgName.isNotEmpty()) updated.extinfAttributes["tvg-name"] = tvgName
        if (tvgLogo.isNotEmpty()) updated.extinfAttributes["tvg-logo"] = tvgLogo
        if (groupTitle.isNotEmpty()) updated.extinfAttributes["group-title"] = groupTitle
        // 保存时重新生成请求头集合，禁止携带旧频道残留请求头
        // 删除界面的参数必须真实删除，不能只从 UI 移除
        updated.requestHeaders.clear()
        updated.userAgent = userAgent.ifEmpty { null }
        updated.referer = referer.ifEmpty { null }

        updated.setRequestHeader("User-Agent", userAgent)
        updated.setRequestHeader("Referer", referer)
        updated.setRequestHeader("Origin", origin)
        updated.setRequestHeader("Host", host)
        updated.setRequestHeader("Connection", connection)

        val usedBuiltInNames = setOf(
            "user-agent", "referer", "referrer", "origin", "host", "connection"
        )
        for (index in 0 until customHeadersContainer.childCount) {
            val rowLayout = customHeadersContainer.getChildAt(index) as? LinearLayout ?: continue
            val keyEdit = rowLayout.getChildAt(0) as? EditText ?: continue
            val valueEdit = rowLayout.getChildAt(1) as? EditText ?: continue
            val key = keyEdit.text.toString().trim()
            val value = valueEdit.text.toString()
            if (key.isBlank() || value.isBlank()) {
                continue
            }
            if (key.lowercase() in usedBuiltInNames) {
                Toast.makeText(this, "自定义请求头不能重复内置请求头：$key", Toast.LENGTH_SHORT).show()
                return
            }
            updated.setRequestHeader(key, value)
        }

        M3UParser.parseUrlIntoChannel(fullUrl, updated)

        val resultIntent = Intent().apply {
            putExtra(EXTRA_RESULT_CHANNEL, updated)
            if (isEditingExisting) {
                // 使用稳定 uid 定位真实频道；索引可能因拖拽/分类变化而失效。
                val originalUid = intent.getStringExtra("original_channel_uid")
                if (!originalUid.isNullOrBlank()) {
                    putExtra("original_channel_uid", originalUid)
                }
                // 保留旧索引仅用于兼容旧调用方。
                putExtra("original_category_index", intent.getIntExtra("original_category_index", -1))
                putExtra("original_channel_index", intent.getIntExtra("original_channel_index", -1))
            }
            intent.getStringExtra(EXTRA_TARGET_CATEGORY_NAME)?.let {
                putExtra(EXTRA_TARGET_CATEGORY_NAME, it)
            }
        }
        setResult(Activity.RESULT_OK, resultIntent)
        Toast.makeText(this, "频道已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
