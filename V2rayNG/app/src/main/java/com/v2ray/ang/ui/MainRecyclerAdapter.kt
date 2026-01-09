package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AngApplication.Companion.application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.handler.V2RayServiceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainRecyclerAdapter(val activity: MainActivity) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private var mActivity: MainActivity = activity
    var isRunning = false

    /**
     * Gets the total number of items in the adapter (servers count + footer view)
     * @return The total item count
     */
    override fun getItemCount() = mActivity.mainViewModel.serversCache.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val guid = mActivity.mainViewModel.serversCache[position].guid
            val profile = mActivity.mainViewModel.serversCache[position].profile

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            //Name
            holder.itemMainBinding.tvName.text = profile.remarks

            //TestResult
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString().orEmpty()
            if ((aff?.testDelayMillis ?: 0L) < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(mActivity, R.color.colorPingRed))
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(mActivity, R.color.colorPing))
            }

            //layoutIndicator
            if (guid == MmkvManager.getSelectServer()) {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.color.colorAccent)
            } else {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(0)
            }

            // Click listener - Select
            holder.itemMainBinding.infoContainer.setOnClickListener {
                setSelectServer(guid)
            }

            // Long Click listener - Edit Name
            holder.itemMainBinding.infoContainer.setOnLongClickListener {
                showEditNameDialog(guid, profile, position)
                true
            }
        }
    }

    private fun showEditNameDialog(guid: String, profile: ProfileItem, position: Int) {
        val etParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        etParams.setMargins(50, 0, 50, 0)
        val etName = EditText(mActivity)
        etName.layoutParams = etParams
        etName.setText(profile.remarks)
        etName.hint = "Config Name"

        val container = android.widget.LinearLayout(mActivity)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.addView(etName)

        AlertDialog.Builder(mActivity)
            .setTitle("Edit Config Name")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = etName.text.toString()
                if (newName.isNotEmpty()) {
                    profile.remarks = newName
                    MmkvManager.encodeServerConfig(guid, profile)
                    notifyItemChanged(position)
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                removeServer(guid, position)
            }
            .show()
    }


    /**
     * Removes server configuration
     * Handles confirmation dialog and related checks
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServer(guid: String, position: Int) {
        if (guid != MmkvManager.getSelectServer()) {
             removeServerSub(guid, position)
        } else {
            application.toast(R.string.toast_action_not_allowed)
        }
    }

    /**
     * Executes the actual server removal process
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServerSub(guid: String, position: Int) {
        mActivity.mainViewModel.removeServer(guid)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, mActivity.mainViewModel.serversCache.size)
    }

    /**
     * Sets the selected server
     * Updates UI and restarts service if needed
     * @param guid The server unique identifier to select
     */
    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            if (!TextUtils.isEmpty(selected)) {
                notifyItemChanged(mActivity.mainViewModel.getPosition(selected.orEmpty()))
            }
            notifyItemChanged(mActivity.mainViewModel.getPosition(guid))
            if (isRunning) {
                V2RayServiceManager.stopVService(mActivity)
                mActivity.lifecycleScope.launch {
                    try {
                        delay(500)
                        V2RayServiceManager.startVService(mActivity)
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Failed to restart V2Ray service", e)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == mActivity.mainViewModel.serversCache.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
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

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mActivity.mainViewModel.swapServer(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }
}
