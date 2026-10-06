package com.m3utoolbox

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var importButton: Button
    private lateinit var exportButton: Button
    private lateinit var dedupButton: Button
    private lateinit var addHeaderButton: Button
    private lateinit var addCategoryButton: Button
    private lateinit var addChannelButton: Button
    private lateinit var selectButton: Button
    private lateinit var searchButton: Button
    private lateinit var searchBar: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchNextButton: Button
    private lateinit var searchCloseButton: Button

    private lateinit var adapter: M3UAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val m3uFile = M3UFile()

    private var lastSearchMatchIndex: Int = -1

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importM3U(it) }
    }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { exportM3U(it) }
    }

    private val channelEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val editedChannel = result.data?.getSerializableExtra(ChannelEditActivity.EXTRA_RESULT_CHANNEL) as? Channel
            val originalUid = result.data?.getStringExtra("original_channel_uid")
            val originalCategoryIndex = result.data?.getIntExtra("original_category_index", -1) ?: -1
            val originalChannelIndex = result.data?.getIntExtra("original_channel_index", -1) ?: -1

            if (editedChannel != null) {
                val newGroupTitle = editedChannel.extinfAttributes["group-title"] ?: "未分类"

                // 首选稳定 uid 定位真实的 Channel 实例。
                // 这样即使 RecyclerView/拖拽/分类移动改变了索引，也一定更新正在导出的对象。
                var existingCategoryIndex = -1
                var existingChannelIndex = -1
                if (!originalUid.isNullOrBlank()) {
                    loop@ for (ci in m3uFile.categories.indices) {
                        val idx = m3uFile.categories[ci].channels.indexOfFirst { it.uid == originalUid }
                        if (idx >= 0) {
                            existingCategoryIndex = ci
                            existingChannelIndex = idx
                            break@loop
                        }
                    }
                }

                // 兼容没有 uid 的旧编辑结果。
                if (existingCategoryIndex < 0) {
                    val valid = originalCategoryIndex >= 0 && originalChannelIndex >= 0 &&
                            originalCategoryIndex < m3uFile.categories.size &&
                            originalChannelIndex < m3uFile.categories[originalCategoryIndex].channels.size
                    if (valid) {
                        existingCategoryIndex = originalCategoryIndex
                        existingChannelIndex = originalChannelIndex
                    }
                }

                if (existingCategoryIndex >= 0) {
                    val existingCategory = m3uFile.categories[existingCategoryIndex]
                    val existingChannel = existingCategory.channels[existingChannelIndex]
                    val oldCategoryName = existingCategory.name

                    // 原地覆盖：selectedChannels、RecyclerView、m3uFile、导出全部继续引用这个实例。
                    existingChannel.overwriteFrom(editedChannel)

                    if (oldCategoryName == newGroupTitle) {
                        adapter.refreshItems()
                    } else {
                        existingCategory.channels.removeAt(existingChannelIndex)
                        addChannelToCategory(existingChannel, newGroupTitle)
                    }
                } else {
                    // 编辑已有频道时绝不能静默新增，否则旧频道（包括旧请求头）会继续存在。
                    if (!originalUid.isNullOrBlank()) {
                        Toast.makeText(this, "保存失败：找不到原频道，未创建重复频道", Toast.LENGTH_LONG).show()
                    } else {
                        addChannelToCategory(editedChannel, newGroupTitle)
                    }
                }
            }
        }
    }

    private val createDocumentLauncherForSelected = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            try {
                val outputStream = contentResolver.openOutputStream(it)
                val writer = OutputStreamWriter(outputStream)
                writer.write(pendingExportContent ?: "")
                writer.close()
                Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var pendingExportContent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        importButton = findViewById(R.id.importButton)
        exportButton = findViewById(R.id.exportButton)
        dedupButton = findViewById(R.id.dedupButton)
        addHeaderButton = findViewById(R.id.addHeaderButton)
        addCategoryButton = findViewById(R.id.addCategoryButton)
        addChannelButton = findViewById(R.id.addChannelButton)
        selectButton = findViewById(R.id.selectButton)
        searchButton = findViewById(R.id.searchButton)
        searchBar = findViewById(R.id.searchBar)
        searchEditText = findViewById(R.id.searchEditText)
        searchNextButton = findViewById(R.id.searchNextButton)
        searchCloseButton = findViewById(R.id.searchCloseButton)

        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = M3UAdapter(
            m3uFile,
            onHeaderParamClick = { editHeaderParam(it) },
            onHeaderParamLongClick = { deleteHeaderParam(it) },
            onCategoryClick = { editCategoryName(it) },
            onCategoryLongClick = { deleteCategory(it) },
            onChannelClick = { editChannel(it) },
            onChannelLongClick = { channel, view -> showChannelLongClickMenu(channel, view) },
            onPlayClick = { channel -> playChannel(channel) }
        )
        recyclerView.adapter = adapter

        setupItemTouchHelper()

        importButton.setOnClickListener { showImportDialog() }
        exportButton.setOnClickListener { createDocumentLauncher.launch("导出的IPTV") }
        dedupButton.setOnClickListener { startDedupCheck() }
        addHeaderButton.setOnClickListener { showAddHeaderDialog() }
        addCategoryButton.setOnClickListener { showAddCategoryDialog() }
        addChannelButton.setOnClickListener { showAddChannelDialog() }
        selectButton.setOnClickListener {
            val currentlySelection = adapter.isSelectionMode()
            adapter.setSelectionMode(!currentlySelection)
            selectButton.text = if (currentlySelection) "勾选" else "取消勾选"
        }

        // 查找功能
        searchButton.setOnClickListener {
            if (searchBar.visibility == View.VISIBLE) {
                closeSearchBar()
            } else {
                searchBar.visibility = View.VISIBLE
                searchEditText.requestFocus()
            }
        }

        searchEditText.addTextChangedListener { text ->
            val query = text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                lastSearchMatchIndex = -1
                findAndLocate(query, searchNext = false)
            }
        }

        searchNextButton.setOnClickListener {
            val query = searchEditText.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findAndLocate(query, searchNext = true)
        }

        searchCloseButton.setOnClickListener {
            closeSearchBar()
        }
    }

    private fun closeSearchBar() {
        searchEditText.setText("")
        lastSearchMatchIndex = -1
        searchBar.visibility = View.GONE
    }

    private fun findAndLocate(query: String, searchNext: Boolean) {
        val flatItems = adapter.getFlatItems()
        val lowerQuery = query.lowercase()

        val matches = flatItems.indices.filter { i ->
            val item = flatItems[i]
            item is Channel && item.displayName.lowercase().contains(lowerQuery)
        }

        if (matches.isEmpty()) {
            Toast.makeText(this, "未找到包含「$query」的频道", Toast.LENGTH_SHORT).show()
            return
        }

        val targetIndex: Int
        if (searchNext && lastSearchMatchIndex >= 0) {
            val posInMatches = matches.indexOf(lastSearchMatchIndex)
            targetIndex = if (posInMatches == -1 || posInMatches == matches.size - 1) {
                matches[0]
            } else {
                matches[posInMatches + 1]
            }
        } else {
            targetIndex = matches[0]
        }

        lastSearchMatchIndex = targetIndex
        recyclerView.scrollToPosition(targetIndex)
        Toast.makeText(
            this,
            "已定位: ${(flatItems[targetIndex] as Channel).displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun playChannel(channel: Channel) {
        val url = channel.getFullUrl()
        if (url.isEmpty()) {
            Toast.makeText(this, "视频链接为空", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("video_url", url)
            putExtra("user_agent", channel.userAgent)
            putExtra("referer", channel.referer)
            putExtra("request_headers", HashMap(channel.getRequestHeaders()))
        }
        startActivity(intent)
    }

    private fun showImportDialog() {
        val options = arrayOf("本地导入", "在线导入")
        AlertDialog.Builder(this)
            .setTitle("选择导入方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDocumentLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                    1 -> showOnlineImportDialog()
                }
            }
            .show()
    }

    private fun showOnlineImportDialog() {
        val inputUrl = EditText(this).apply {
            hint = "请输入.m3u链接"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("在线导入")
            .setView(inputUrl)
            .setPositiveButton("导入") { _, _ ->
                val url = inputUrl.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                importFromUrl(url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFromUrl(url: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage("正在下载解析...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                val code = connection.responseCode
                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }
                    runOnUiThread {
                        progressDialog.dismiss()
                        importM3UContent(content)
                    }
                } else {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this, "下载失败: HTTP $code", Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showAddChannelDialog() {
        val categoryNames = m3uFile.categories.map { it.name }.toMutableList()
        if (categoryNames.isEmpty()) {
            categoryNames.add("未分类")
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val label = TextView(this).apply {
            text = "选择分类："
            setPadding(0, 0, 0, 8)
        }

        val spinner = Spinner(this)
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categoryNames)
        spinner.adapter = spinnerAdapter

        layout.addView(label)
        layout.addView(spinner)

        val addTextButton = Button(this).apply {
            text = "粘贴原数据自动解析"
            setPadding(16, 12, 16, 12)
            setOnClickListener {
                showAddChannelFromTextDialog(selectedCategoryName = spinner.selectedItem.toString())
            }
        }
        layout.addView(addTextButton)

        AlertDialog.Builder(this)
            .setTitle("新增频道")
            .setView(layout)
            .setPositiveButton("手动填写") { _, _ ->
                val selectedCategoryName = spinner.selectedItem.toString()
                val intent = Intent(this, ChannelEditActivity::class.java).apply {
                    putExtra(ChannelEditActivity.EXTRA_TARGET_CATEGORY_NAME, selectedCategoryName)
                    putStringArrayListExtra("category_list", ArrayList(categoryNames))
                }
                channelEditLauncher.launch(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddChannelFromTextDialog(selectedCategoryName: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val editText = EditText(this).apply {
            hint = "粘贴频道参数，例如：\n#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" tvg-logo=\"http://logo.png\" group-title=\"央视\",CCTV1\nhttp://example.com/cctv1.m3u8"
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP
        }

        val label = TextView(this).apply {
            text = "选择分类：$selectedCategoryName"
            setPadding(0, 0, 0, 8)
        }

        layout.addView(label)
        layout.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("新增频道(粘贴文本)")
            .setView(layout)
            .setPositiveButton("确认") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "请输入频道参数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val channel = parseChannelFromText(text)
                if (channel == null) {
                    Toast.makeText(this, "解析失败，请检查格式", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                addChannelToCategory(channel, selectedCategoryName)
                Toast.makeText(this, "新增成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun parseChannelFromText(text: String): Channel? {
        val parsed = runCatching { M3UParser.parse(text) }.getOrNull() ?: return null
        return parsed.categories.firstOrNull()?.channels?.firstOrNull()
    }

    private fun startDedupCheck() {
        if (m3uFile.categories.isEmpty()) {
            Toast.makeText(this, "没有频道数据", Toast.LENGTH_SHORT).show()
            return
        }

        val urlMap = mutableMapOf<String, MutableList<DedupInfo>>()

        for (category in m3uFile.categories) {
            for (channel in category.channels) {
                val fullUrl = channel.getFullUrl().trim()
                if (fullUrl.isNotEmpty()) {
                    if (!urlMap.containsKey(fullUrl)) {
                        urlMap[fullUrl] = mutableListOf()
                    }
                    urlMap[fullUrl]!!.add(DedupInfo(category.name, channel))
                }
            }
        }

        val duplicates = urlMap.filter { it.value.size > 1 }

        if (duplicates.isEmpty()) {
            Toast.makeText(this, "没有发现重复的频道", Toast.LENGTH_SHORT).show()
            return
        }

        showDedupSelectionDialog(duplicates)
    }

    private fun showDedupSelectionDialog(duplicates: Map<String, List<DedupInfo>>) {
        val displayItems = mutableListOf<Any>()

        val dupEntries = duplicates.entries.toList()

        for (groupIndex in dupEntries.indices) {
            val entry = dupEntries[groupIndex]
            val channels = entry.value

            if (channels.size < 2) continue

            val channelNames = channels.map { it.channel.displayName }
            val separatorText = if (channels.size == 2) {
                "--- 重复组 ${groupIndex + 1}: ${channelNames[0]} 与 ${channelNames[1]} 重复 ---"
            } else {
                "--- 重复组 ${groupIndex + 1}: (${channelNames.joinToString("、")}) 链接重复 ---"
            }

            displayItems.add(separatorText)
            for (channelInfo in channels) {
                displayItems.add(channelInfo)
            }
        }

        if (displayItems.isEmpty()) {
            Toast.makeText(this, "没有发现重复的频道", Toast.LENGTH_SHORT).show()
            return
        }

        val listView = ListView(this)

        val customAdapter = object : BaseAdapter() {
            override fun getCount(): Int = displayItems.size
            override fun getItem(position: Int): Any = displayItems[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getItemViewType(position: Int): Int {
                return if (displayItems[position] is String) 0 else 1
            }

            override fun getViewTypeCount(): Int = 2
            override fun isEnabled(position: Int): Boolean = displayItems[position] is DedupInfo

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = displayItems[position]
                val inflater = LayoutInflater.from(this@MainActivity)

                return when (item) {
                    is String -> {
                        val view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                        val textView = view.findViewById<TextView>(android.R.id.text1)
                        textView.text = item
                        textView.setTextColor(Color.GRAY)
                        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        textView.gravity = Gravity.CENTER
                        view
                    }
                    is DedupInfo -> {
                        val view = inflater.inflate(android.R.layout.simple_list_item_multiple_choice, parent, false)
                        val textView = view.findViewById<TextView>(android.R.id.text1)
                        val fullUrl = item.channel.getFullUrl()
                        textView.text = "${item.categoryName}: ${item.channel.displayName}\n$fullUrl"
                        textView.setTextColor(Color.BLACK)
                        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        textView.gravity = Gravity.START
                        view
                    }
                    else -> {
                        val view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                        val textView = view.findViewById<TextView>(android.R.id.text1)
                        textView.text = item.toString()
                        view
                    }
                }
            }
        }

        listView.adapter = customAdapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        AlertDialog.Builder(this)
            .setTitle("发现重复频道，请勾选要删除的频道")
            .setView(listView)
            .setPositiveButton("删除选中") { _, _ ->
                val selectedPositions = listView.checkedItemPositions
                val toDelete = mutableListOf<DedupInfo>()

                for (i in 0 until selectedPositions.size()) {
                    val pos = selectedPositions.keyAt(i)
                    if (selectedPositions.get(pos)) {
                        val item = displayItems[pos]
                        if (item is DedupInfo) {
                            toDelete.add(item)
                        }
                    }
                }

                for (dedupInfo in toDelete) {
                    for (category in m3uFile.categories) {
                        if (category.channels.remove(dedupInfo.channel)) {
                            break
                        }
                    }
                }

                val emptyCategories = m3uFile.categories.filter { it.channels.isEmpty() }
                m3uFile.categories.removeAll(emptyCategories)

                adapter.refreshItems()
                Toast.makeText(this, "已删除${toDelete.size}个重复频道", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
            .also { it.show() }
    }

    data class DedupInfo(
        val categoryName: String,
        val channel: Channel
    )

    // ===================== 拖拽支持 =====================

    private fun setupItemTouchHelper() {
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!adapter.isSelectionMode()) return false
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                    return false
                }

                val flatItems = adapter.getFlatItems()
                if (fromPosition >= flatItems.size || toPosition >= flatItems.size) return false

                val fromItem = flatItems[fromPosition]
                // 只允许拖拽被勾选的频道
                if (fromItem !is Channel || !adapter.getSelectedChannels().contains(fromItem)) {
                    return false
                }

                adapter.moveItem(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = false

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 放下：聚拢其他选中项 + 重建分类 + 刷新
                adapter.finalizeMove()
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (!adapter.isSelectionMode()) return 0
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return 0
                val flatItems = adapter.getFlatItems()
                if (pos >= flatItems.size) return 0
                val item = flatItems[pos]
                if (item is Channel && adapter.getSelectedChannels().contains(item)) {
                    return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                }
                return 0
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun startDragForChannel(channel: Channel) {
        val position = adapter.getChannelPosition(channel)
        if (position == -1) {
            Toast.makeText(this, "频道不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {
            itemTouchHelper.startDrag(viewHolder)
            Toast.makeText(this, "可拖动到任意位置", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    // ===================== 长按菜单 =====================

    /**
     * 长按频道：
     * - 未进入勾选模式 / 未勾选当前频道：只显示"复制"
     * - 已进入勾选模式且当前频道被勾选：显示"复制 + 批量删除 / 移动（拖拽）/ 导出"
     */
    private fun showChannelLongClickMenu(channel: Channel, view: View) {
        val popup = PopupMenu(this, view)
        val inSelectionMode = adapter.isSelectionMode()
        val isSelected = adapter.getSelectedChannels().contains(channel)
        val selectedCount = adapter.getSelectedChannels().size

        // 任何情况下都允许复制
        popup.menu.add("复制")

        if (inSelectionMode && isSelected) {
            if (selectedCount > 1) {
                popup.menu.add("删除选中(${selectedCount})")
                popup.menu.add("移动选中(${selectedCount})")
                popup.menu.add("导出选中(${selectedCount})")
            } else {
                popup.menu.add("删除")
                popup.menu.add("移动")
                popup.menu.add("导出")
            }
        }

        popup.setOnMenuItemClickListener { item: MenuItem ->
            val title = item.title.toString()
            when {
                title.startsWith("删除") -> deleteSelectedChannels()
                title.startsWith("移动") -> startDragForChannel(channel)
                title.startsWith("导出") -> exportSelectedChannels()
                title == "复制" -> copyChannel(channel)
            }
            true
        }
        popup.show()
    }

    private fun copyChannel(channel: Channel) {
        val copy = Channel(
            displayName = channel.displayName,
            url = channel.url,
            originalUrl = channel.originalUrl,
            userAgent = channel.userAgent,
            referer = channel.referer
        )
        copy.extinfAttributes.putAll(channel.extinfAttributes)
        copy.urlParams.putAll(channel.urlParams)
        copy.requestHeaders.addAll(channel.requestHeaders.map { HeaderParam(it.key, it.value) })

        val category = m3uFile.categories.find { it.channels.contains(channel) }
        if (category == null) {
            Toast.makeText(this, "找不到频道所在分类", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = category.channels.indexOf(channel)
        category.channels.add(idx + 1, copy)
        adapter.refreshItems()
        Toast.makeText(this, "已复制频道", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedChannels() {
        val selected = adapter.getSelectedChannels()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选频道", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除频道")
            .setMessage("确定删除选中的 ${selected.size} 个频道?")
            .setPositiveButton("删除") { _, _ ->
                for (channel in selected) {
                    for (category in m3uFile.categories) {
                        if (category.channels.remove(channel)) {
                            break
                        }
                    }
                }
                adapter.setSelectionMode(false)
                selectButton.text = "勾选"
                adapter.refreshItems()
                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportSelectedChannels() {
        val selected = adapter.getSelectedChannels()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选频道", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedM3U = M3UFile()
        selectedM3U.headerParams.addAll(m3uFile.headerParams)
        val exportCategory = Category("导出频道")
        selectedM3U.categories.add(exportCategory)
        exportCategory.channels.addAll(selected)
        val content = M3UParser.generate(selectedM3U)
        pendingExportContent = content
        createDocumentLauncherForSelected.launch("导出的IPTV频道")
    }

    private fun importM3U(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val content = reader.readText()
            reader.close()
            importM3UContent(content)
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importM3UContent(content: String) {
        try {
            val parsed = M3UParser.parse(content)
            m3uFile.headerParams.clear()
            m3uFile.headerParams.addAll(parsed.headerParams)
            m3uFile.categories.clear()
            m3uFile.categories.addAll(parsed.categories)
            adapter.refreshItems()
            adapter.setSelectionMode(false)
            selectButton.text = "勾选"
            Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportM3U(uri: Uri) {
        try {
            val content = M3UParser.generate(m3uFile)
            val outputStream = contentResolver.openOutputStream(uri)
            val writer = OutputStreamWriter(outputStream)
            writer.write(content)
            writer.close()
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddHeaderDialog() {
        val inputKey = EditText(this).apply { hint = "参数名" }
        val inputValue = EditText(this).apply { hint = "参数值" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(inputKey)
            addView(inputValue)
        }
        AlertDialog.Builder(this)
            .setTitle("新增头部参数")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val key = inputKey.text.toString().trim()
                val value = inputValue.text.toString().trim()
                if (key.isNotEmpty()) {
                    m3uFile.headerParams.add(HeaderParam(key, value))
                    adapter.refreshItems()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editHeaderParam(param: HeaderParam) {
        val inputKey = EditText(this).apply { setText(param.key) }
        val inputValue = EditText(this).apply { setText(param.value) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(inputKey)
            addView(inputValue)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑头部参数")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val key = inputKey.text.toString().trim()
                val value = inputValue.text.toString().trim()
                if (key.isNotEmpty()) {
                    param.key = key
                    param.value = value
                    adapter.refreshItems()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteHeaderParam(param: HeaderParam) {
        AlertDialog.Builder(this)
            .setTitle("删除参数")
            .setMessage("确定删除 ${param.key}?")
            .setPositiveButton("删除") { _, _ ->
                m3uFile.headerParams.remove(param)
                adapter.refreshItems()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddCategoryDialog() {
        val inputName = EditText(this).apply { hint = "分类名称" }
        AlertDialog.Builder(this)
            .setTitle("新增分类")
            .setView(inputName)
            .setPositiveButton("添加") { _, _ ->
                val name = inputName.text.toString().trim()
                if (name.isNotEmpty()) {
                    m3uFile.categories.add(Category(name))
                    adapter.refreshItems()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editCategoryName(category: Category) {
        val inputName = EditText(this).apply { setText(category.name) }
        AlertDialog.Builder(this)
            .setTitle("编辑分类名")
            .setView(inputName)
            .setPositiveButton("保存") { _, _ ->
                val name = inputName.text.toString().trim()
                if (name.isNotEmpty()) {
                    category.name = name
                    adapter.refreshItems()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCategory(category: Category) {
        AlertDialog.Builder(this)
            .setTitle("删除分类")
            .setMessage("确定删除分类 ${category.name} 及其所有频道?")
            .setPositiveButton("删除") { _, _ ->
                m3uFile.categories.remove(category)
                adapter.refreshItems()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editChannel(channel: Channel) {
        val categoryIndex = m3uFile.categories.indexOfFirst { it.channels.contains(channel) }
        val channelIndex = if (categoryIndex >= 0) {
            m3uFile.categories[categoryIndex].channels.indexOf(channel)
        } else {
            -1
        }
        val categoryNames = m3uFile.categories.map { it.name }.toMutableList()
        val intent = Intent(this, ChannelEditActivity::class.java).apply {
            putExtra(ChannelEditActivity.EXTRA_CHANNEL, channel)
            putExtra("original_category_index", categoryIndex)
            putExtra("original_channel_index", channelIndex)
            putExtra("original_channel_uid", channel.uid)
            putStringArrayListExtra("category_list", ArrayList(categoryNames))
        }
        channelEditLauncher.launch(intent)
    }

    private fun addChannelToCategory(channel: Channel, categoryName: String?) {
        val targetCategoryName = categoryName ?: "未分类"
        var category = m3uFile.categories.find { it.name == targetCategoryName }
        if (category == null) {
            category = Category(targetCategoryName)
            m3uFile.categories.add(category)
        }
        category.channels.add(channel)
        adapter.refreshItems()
    }
}