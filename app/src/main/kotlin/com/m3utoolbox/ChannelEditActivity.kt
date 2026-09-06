package com.m3utoolbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
            editUserAgent.setText(ch.userAgent.orEmpty())
            editReferer.setText(ch.referer.orEmpty())
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

        val old = channel
        val updated = Channel(
            displayName = tvgName.ifEmpty { old?.displayName ?: "未命名" },
            url = ""
        )

        if (tvgId.isNotEmpty()) updated.extinfAttributes["tvg-id"] = tvgId
        if (tvgName.isNotEmpty()) updated.extinfAttributes["tvg-name"] = tvgName
        if (tvgLogo.isNotEmpty()) updated.extinfAttributes["tvg-logo"] = tvgLogo
        if (groupTitle.isNotEmpty()) updated.extinfAttributes["group-title"] = groupTitle
        updated.userAgent = userAgent.ifEmpty { null }
        updated.referer = referer.ifEmpty { null }

        M3UParser.parseUrlIntoChannel(fullUrl, updated)

        val resultIntent = Intent().apply {
            putExtra(EXTRA_RESULT_CHANNEL, updated)
            if (isEditingExisting) {
                val categoryIndex = intent.getIntExtra("original_category_index", -1)
                val channelIndex = intent.getIntExtra("original_channel_index", -1)
                putExtra("original_category_index", categoryIndex)
                putExtra("original_channel_index", channelIndex)
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
