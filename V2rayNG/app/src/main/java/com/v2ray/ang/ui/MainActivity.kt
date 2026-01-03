package com.v2ray.ang.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mmkv by lazy { MMKV.defaultMMKV() }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startV2Ray()
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }

    private val fixedTabs = listOf(
        "所有节点" to "default",
        "奈飞" to "netflix",
        "YouTube" to "youtube",
        "Google" to "google",
        "AI" to "openai"
    )

    var currentStrategyTag: String = "default"

    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val index = tab?.position ?: 0
            currentStrategyTag = if (index in fixedTabs.indices) fixedTabs[index].second else "default"
            Log.i("AngTag", "Tab Switched: $currentStrategyTag")
            mainViewModel.subscriptionIdChanged("")
            adapter.notifyDataSetChanged()
        }
        override fun onTabUnselected(tab: TabLayout.Tab?) {}
        override fun onTabReselected(tab: TabLayout.Tab?) {
            val index = tab?.position ?: 0
            currentStrategyTag = if (index in fixedTabs.indices) fixedTabs[index].second else "default"
            mainViewModel.subscriptionIdChanged("")
        }
    }

    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            when (pendingAction) {
                Action.IMPORT_QR_CODE_CONFIG -> scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
                Action.READ_CONTENT_FROM_URI -> chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, getString(R.string.title_file_chooser)))
                else -> {}
            }
        } else {
            toast(R.string.toast_permission_denied)
        }
        pendingAction = Action.NONE
    }

    private var pendingAction: Action = Action.NONE
    enum class Action { NONE, IMPORT_QR_CODE_CONFIG, READ_CONTENT_FROM_URI, POST_NOTIFICATIONS }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) readContentFromUri(uri)
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = GridLayoutManager(this, if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) 2 else 1)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        val toggle = ActionBarDrawerToggle(this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        initGroupTab()
        setupViewModel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) adapter.notifyItemChanged(index) else adapter.notifyDataSetChanged()
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                setTestState(getString(R.string.connection_connected))
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                setTestState(getString(R.string.connection_not_connected))
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = true
        fixedTabs.forEach { (name, key) ->
            val tab = binding.tabGroup.newTab()
            tab.text = name
            tab.tag = key
            binding.tabGroup.addTab(tab)
        }
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(0))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        // 关键修复：延迟启动，确保配置重载
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    fun handleNodeClick(guid: String, remarks: String) {
        if (currentStrategyTag == "default") {
            // 普通模式选择主节点
            MmkvManager.setSelectServer(guid)
            if (mainViewModel.isRunning.value == true) restartV2Ray() else startV2Ray()
        } else {
            // 分流模式选择节点，保存到 strategy_xxx
            mmkv.encode("strategy_${currentStrategyTag}", guid)
            toast("已将 [${getStrategyName(currentStrategyTag)}] 分流至：$remarks")
            adapter.notifyDataSetChanged()
            // 重要：修改分流规则后立即重启服务
            if (mainViewModel.isRunning.value == true) restartV2Ray()
        }
    }

    private fun getStrategyName(tag: String): String = fixedTabs.find { it.second == tag }?.first ?: tag

    override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })
            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> { importQRcode(); true }
        R.id.import_clipboard -> { importClipboard(); true }
        R.id.import_local -> { importConfigLocal(); true }
        R.id.import_manually_vmess -> { importManually(EConfigType.VMESS.value); true }
        R.id.import_manually_vless -> { importManually(EConfigType.VLESS.value); true }
        R.id.import_manually_ss -> { importManually(EConfigType.SHADOWSOCKS.value); true }
        R.id.import_manually_socks -> { importManually(EConfigType.SOCKS.value); true }
        R.id.import_manually_http -> { importManually(EConfigType.HTTP.value); true }
        R.id.import_manually_trojan -> { importManually(EConfigType.TROJAN.value); true }
        R.id.import_manually_wireguard -> { importManually(EConfigType.WIREGUARD.value); true }
        R.id.import_manually_hysteria2 -> { importManually(EConfigType.HYSTERIA2.value); true }
        R.id.export_all -> { exportAll(); true }
        R.id.ping_all -> { toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count())); mainViewModel.testAllTcping(); true }
        R.id.real_ping_all -> { toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count())); mainViewModel.testAllRealPing(); true }
        R.id.service_restart -> { restartV2Ray(); true }
        R.id.del_all_config -> { delAllConfig(); true }
        R.id.del_duplicate_config -> { delDuplicateConfig(); true }
        R.id.del_invalid_config -> { delInvalidConfig(); true }
        R.id.sort_by_test_results -> { sortByTestResults(); true }
        R.id.sub_update -> { importConfigViaSub(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        startActivity(Intent().putExtra("createConfigType", createConfigType).putExtra("subscriptionId", mainViewModel.subscriptionId).setClass(this, ServerActivity::class.java))
    }

    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    private fun importClipboard(): Boolean {
        try {
            importBatchConfig(Utils.getClipboard(this))
        } catch (e: Exception) {
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> { toast(getString(R.string.title_import_config_count, count)); mainViewModel.reloadServerList() }
                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure); binding.pbWaiting.hide() }
            }
        }
    }

    private fun importConfigLocal(): Boolean {
        try { showFileChooser() } catch (e: Exception) { return false }
        return true
    }

    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) { toast(getString(R.string.title_update_config_count, count)); mainViewModel.reloadServerList() } else { toastError(R.string.toast_failure) }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0) toast(getString(R.string.title_export_config_count, ret)) else toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
            binding.pbWaiting.show()
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeAllServer()
                launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)); binding.pbWaiting.hide() }
            }
        }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
            binding.pbWaiting.show()
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeDuplicateServer()
                launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_duplicate_config_count, ret)); binding.pbWaiting.hide() }
            }
        }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm).setPositiveButton(android.R.string.ok) { _, _ ->
            binding.pbWaiting.show()
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeInvalidServer()
                launch(Dispatchers.Main) { mainViewModel.reloadServerList(); toast(getString(R.string.title_del_config_count, ret)); binding.pbWaiting.hide() }
            }
        }.setNegativeButton(android.R.string.cancel) { _, _ -> }.show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) { mainViewModel.reloadServerList(); binding.pbWaiting.hide() }
        }
    }

    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { importBatchConfig(it?.bufferedReader()?.readText()) }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content", e)
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> requestSubSettingActivity.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestSubSettingActivity.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> startActivity(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("isRunning", mainViewModel.isRunning.value == true))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}
