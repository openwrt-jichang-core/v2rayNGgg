package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication.Companion.application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.handler.V2RayServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainRecyclerAdapter(val activity: MainActivity) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private var mActivity: MainActivity = activity
    private val share_method: Array<out String> by lazy {
        mActivity.resources.getStringArray(R.array.share_method)
    }
    private val share_method_more: Array<out String> by lazy {
        mActivity.resources.getStringArray(R.array.share_method_more)
    }
    var isRunning = false
    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)

    private val mmkv by lazy { MMKV.defaultMMKV() }

    private val isStrategyMode: Boolean
        get() = mActivity.currentStrategyTag != "default"

    // --- 修改点：直接返回真实数量，不加 1 (Auto Item) ---
    override fun getItemCount(): Int {
        return mActivity.mainViewModel.serversCache.size + 1 // +1 是底部的 footer
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == mActivity.mainViewModel.serversCache.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val guid: String
            val profile: ProfileItem

            // --- 修改点：直接使用 position，不再进行位移 ---
            val realIndex = position
            if (realIndex >= 0 && realIndex < mActivity.mainViewModel.serversCache.size) {
                val config = mActivity.mainViewModel.serversCache[realIndex]
                guid = config.guid
                profile = config.profile
            } else {
                return
            }

            val isCustom = profile.configType == EConfigType.CUSTOM

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.itemMainBinding.tvName.text = profile.remarks

            holder.itemMainBinding.tvStatistics.text = getAddress(profile)
            holder.itemMainBinding.tvType.text = profile.configType.name

            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString().orEmpty()
            if ((aff?.testDelayMillis ?: 0L) < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(mActivity, R.color.colorPingRed))
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(mActivity, R.color.colorPing))
            }

            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            // 按钮逻辑 (保留)
            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.VISIBLE
                val shareOptions = if (isCustom) share_method_more.asList().takeLast(3) else share_method_more.asList()
                holder.itemMainBinding.layoutMore.setOnClickListener {
                    shareServer(guid, profile, position, shareOptions, if (isCustom) 2 else 0)
                }
            } else {
                holder.itemMainBinding.layoutShare.visibility = View.VISIBLE
                holder.itemMainBinding.layoutEdit.visibility = View.VISIBLE
                holder.itemMainBinding.layoutRemove.visibility = View.VISIBLE
                holder.itemMainBinding.layoutMore.visibility = View.GONE
                val shareOptions = if (isCustom) share_method.asList().takeLast(1) else share_method.asList()
                holder.itemMainBinding.layoutShare.setOnClickListener {
                    shareServer(guid, profile, position, shareOptions, if (isCustom) 2 else 0)
                }
                holder.itemMainBinding.layoutEdit.setOnClickListener { editServer(guid, profile) }
                holder.itemMainBinding.layoutRemove.setOnClickListener { removeServer(guid, position) }
            }

            // --- 修改点：高亮逻辑 ---
            // 无论是主模式还是分流模式，高亮当前选中的节点
            val currentSelectedGuid = if (isStrategyMode) {
                mmkv?.decodeString("strategy_${mActivity.currentStrategyTag}")
            } else {
                MmkvManager.getSelectServer()
            }

            if (guid == currentSelectedGuid) {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.color.colorAccent)
                if (isStrategyMode) {
                    holder.itemView.setBackgroundColor(ContextCompat.getColor(mActivity, R.color.color_fab_inactive)) // 稍微灰一点表示这是分流选择
                }
            } else {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(0)
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                mActivity.handleNodeClick(guid, profile.remarks)
            }
        }
    }

    // ... (getAddress, getSubscriptionRemarks, shareServer 等辅助方法保持不变，省略以节省空间，直接复制原文件即可) ...
    private fun getAddress(profile: ProfileItem): String {
        return "${
            profile.server?.let {
                if (it.contains(":"))
                    it.split(":").take(2).joinToString(":", postfix = ":***")
                else
                    it.split('.').dropLast(1).joinToString(".", postfix = ".***")
            }
        } : ${profile.serverPort}"
    }

    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mActivity.mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
    }

    private fun shareServer(guid: String, profile: ProfileItem, position: Int, shareOptions: List<String>, skip: Int) {
        AlertDialog.Builder(mActivity).setItems(shareOptions.toTypedArray()) { _, i ->
            try {
                when (i + skip) {
                    0 -> showQRCode(guid)
                    1 -> share2Clipboard(guid)
                    2 -> shareFullContent(guid)
                    3 -> editServer(guid, profile)
                    4 -> removeServer(guid, position)
                    else -> mActivity.toast("else")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error when sharing server", e)
            }
        }.show()
    }

    private fun showQRCode(guid: String) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(mActivity))
        ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        if (share_method.isNotEmpty()) {
            ivBinding.ivQcode.contentDescription = share_method[0]
        } else {
            ivBinding.ivQcode.contentDescription = "QR Code"
        }
        AlertDialog.Builder(mActivity).setView(ivBinding.root).show()
    }

    private fun share2Clipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(mActivity, guid) == 0) {
            mActivity.toastSuccess(R.string.toast_success)
        } else {
            mActivity.toastError(R.string.toast_failure)
        }
    }

    private fun shareFullContent(guid: String) {
        mActivity.lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(mActivity, guid)
            launch(Dispatchers.Main) {
                if (result == 0) {
                    mActivity.toastSuccess(R.string.toast_success)
                } else {
                    mActivity.toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val intent = Intent().putExtra("guid", guid)
            .putExtra("isRunning", isRunning)
            .putExtra("createConfigType", profile.configType.value)
        if (profile.configType == EConfigType.CUSTOM) {
            mActivity.startActivity(intent.setClass(mActivity, ServerCustomConfigActivity::class.java))
        } else {
            mActivity.startActivity(intent.setClass(mActivity, ServerActivity::class.java))
        }
    }

    private fun removeServer(guid: String, position: Int) {
        if (guid != MmkvManager.getSelectServer()) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE) == true) {
                AlertDialog.Builder(mActivity).setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        removeServerSub(guid, position)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
            } else {
                removeServerSub(guid, position)
            }
        } else {
            application.toast(R.string.toast_action_not_allowed)
        }
    }

    private fun removeServerSub(guid: String, position: Int) {
        mActivity.mainViewModel.removeServer(guid)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, getItemCount() - position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (isStrategyMode) return false
        mActivity.mainViewModel.swapServer(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
    }

    override fun onItemDismiss(position: Int) {
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }
        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)
}
