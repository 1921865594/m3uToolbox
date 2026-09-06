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
        draggingChannel = null
        notifyDataSetChanged()
    }

    fun getFlatItems(): List<Any> = items.toList()

    fun getChannelPosition(channel: Channel): Int {
        return items.indexOf(channel)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || fromPosition >= items.size || toPosition < 0 || toPosition >= items.size) return
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)

        if (item is Channel && selectedChannels.contains(item)) {
            draggingChannel = item
        }
    }

    fun applyCurrentOrder() {
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
            }
        }

        m3uFile.categories.clear()
        m3uFile.categories.addAll(newCategories)
        selectedChannels.clear()
        draggingChannel = null
        notifyDataSetChanged()
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
                holder.tvTvgLogo.text = "tvg-logo: ${item.extinfAttributes["tvg-logo"] ?: "—"}"
                holder.tvGroupTitle.text = "group-title: ${item.extinfAttributes["group-title"] ?: "—"}"

                // 加载频道 logo
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

                // 播放按钮
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
        val tvTvgLogo: TextView = view.findViewById(R.id.tvTvgLogo)
        val tvGroupTitle: TextView = view.findViewById(R.id.tvGroupTitle)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val btnPlay: Button = view.findViewById(R.id.btnPlay)
    }
}