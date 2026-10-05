package com.m3utoolbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class M3UAdapter(
    private val m3uFile: M3UFile,
    private val onHeaderParamClick: (HeaderParam) -> Unit,
    private val onHeaderParamLongClick: (HeaderParam) -> Unit,
    private val onCategoryClick: (Category) -> Unit,
    private val onCategoryLongClick: (Category) -> Unit,
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: (Channel, View) -> Unit,
    private val onPlayClick: (Channel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CATEGORY = 1
        const val TYPE_CHANNEL = 2
    }

    private val items = mutableListOf<Any>()
    private var selectionMode = false
    private val selectedChannels = mutableSetOf<Channel>()

    /** 当前正在被 ItemTouchHelper 拖拽的频道（用于放下时聚拢其他选中项） */
    var draggingChannel: Channel? = null

    init {
        refreshItems()
    }

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode != enabled) {
            selectionMode = enabled
            if (!enabled) selectedChannels.clear()
            notifyDataSetChanged()
        }
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun getSelectedChannels(): Set<Channel> = selectedChannels

    fun toggleChannelSelection(channel: Channel) {
        if (selectedChannels.contains(channel)) {
            selectedChannels.remove(channel)
        } else {
            selectedChannels.add(channel)
        }
        notifyDataSetChanged()
    }

    fun refreshItems() {
        items.clear()
        m3uFile.headerParams.forEach { items.add(it) }
        m3uFile.categories.forEach { category ->
            items.add(category)
            category.channels.forEach { channel ->
                items.add(channel)
            }
        }
        notifyDataSetChanged()
    }

    fun getFlatItems(): List<Any> = items.toList()

    fun getChannelPosition(channel: Channel): Int = items.indexOf(channel)

    /** 供 ItemTouchHelper 调用，把 items[from] 移动到 items[to]。 */
    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
        if (item is Channel && selectedChannels.contains(item)) {
            draggingChannel = item
        }
    }

    /**
     * 从 items 重建 m3uFile.categories。
     * 拖拽可能会把 Channel 移动到别的分类甚至分类外，需要重建分类结构。
     */
    private fun rebuildCategoriesFromItems() {
        val newCategories = mutableListOf<Category>()
        var currentCategory: Category? = null

        for (item in items) {
            when (item) {
                is Category -> {
                    currentCategory = Category(item.name)
                    newCategories.add(currentCategory)
                }
                is Channel -> {
                    if (currentCategory == null) {
                        currentCategory = Category("未分类")
                        newCategories.add(currentCategory)
                    }
                    currentCategory.channels.add(item)
                }
                else -> {
                    // HeaderParam 等不参与分类重建
                }
            }
        }

        m3uFile.categories.clear()
        m3uFile.categories.addAll(newCategories)
    }

    /**
     * 拖拽结束时的收尾：
     * - 若拖拽的是被勾选频道，且勾选了多个，则把其他被勾选频道聚拢到被拖拽频道旁边；
     * - 根据当前 items 顺序重建分类；
     * - 刷新列表。
     */
    fun finalizeMove() {
        val dragged = draggingChannel
        draggingChannel = null

        if (dragged == null) {
            return
        }

        val selected = selectedChannels
        if (selected.size > 1) {
            val draggedIdx = items.indexOf(dragged)
            if (draggedIdx >= 0) {
                // 移除其他选中项
                val others = selected.filter { it !== dragged }
                items.removeAll(others)
                // 重新计算被拖拽项位置，再把其他选中项紧跟其后插入
                val newIdx = items.indexOf(dragged)
                if (newIdx >= 0) {
                    items.addAll(newIdx + 1, others)
                }
            }
        }

        rebuildCategoriesFromItems()
        refreshItems()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is HeaderParam -> TYPE_HEADER
            is Category -> TYPE_CATEGORY
            is Channel -> TYPE_CHANNEL
            else -> throw IllegalArgumentException()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_CATEGORY -> {
                val view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                CategoryViewHolder(view)
            }
            TYPE_CHANNEL -> {
                val view = inflater.inflate(R.layout.item_m3u_entry, parent, false)
                ChannelViewHolder(view)
            }
            else -> throw IllegalArgumentException()
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                val item = items[position] as HeaderParam
                holder.textView.text = "头部参数: ${item.key} = ${item.value}"
                holder.itemView.setOnClickListener { onHeaderParamClick(item) }
                holder.itemView.setOnLongClickListener { onHeaderParamLongClick(item); true }
            }
            is CategoryViewHolder -> {
                val item = items[position] as Category
                holder.textView.text = "=== ${item.name} ==="
                holder.itemView.setOnClickListener { onCategoryClick(item) }
                holder.itemView.setOnLongClickListener { onCategoryLongClick(item); true }
            }
            is ChannelViewHolder -> {
                val item = items[position] as Channel
                holder.tvDisplayName.text = item.displayName
                holder.tvTvgId.text = "tvg-id: ${item.extinfAttributes["tvg-id"] ?: "—"}"
                holder.tvTvgName.text = "tvg-name: ${item.extinfAttributes["tvg-name"] ?: "—"}"
                holder.tvGroupTitle.text = "group-title: ${item.extinfAttributes["group-title"] ?: "—"}"

                val logoUrl = item.extinfAttributes["tvg-logo"]
                if (!logoUrl.isNullOrEmpty()) {
                    Glide.with(holder.itemView.context)
                        .load(logoUrl)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .into(holder.ivLogo)
                } else {
                    holder.ivLogo.setImageResource(android.R.drawable.ic_menu_gallery)
                }

                holder.btnPlay.setOnClickListener {
                    onPlayClick(item)
                }

                holder.checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.checkBox.isChecked = selectedChannels.contains(item)

                holder.itemView.setOnClickListener {
                    if (selectionMode) {
                        toggleChannelSelection(item)
                    } else {
                        onChannelClick(item)
                    }
                }
                holder.itemView.setOnLongClickListener { view ->
                    onChannelLongClick(item, view)
                    true
                }
                holder.checkBox.setOnClickListener {
                    toggleChannelSelection(item)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivLogo: ImageView = view.findViewById(R.id.ivLogo)
        val tvDisplayName: TextView = view.findViewById(R.id.tvDisplayName)
        val tvTvgId: TextView = view.findViewById(R.id.tvTvgId)
        val tvTvgName: TextView = view.findViewById(R.id.tvTvgName)
        val tvGroupTitle: TextView = view.findViewById(R.id.tvGroupTitle)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val btnPlay: Button = view.findViewById(R.id.btnPlay)
    }
}