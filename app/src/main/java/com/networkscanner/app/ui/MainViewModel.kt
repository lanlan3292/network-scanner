package com.networkscanner.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.networkscanner.app.NetworkScannerApp
import com.networkscanner.app.data.*
import com.networkscanner.app.data.repository.DeviceCustomizationRepository
import com.networkscanner.app.util.NetworkInterfaceOption
import com.networkscanner.app.util.NetworkUtils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import com.networkscanner.app.util.IpUtils

/**
 * ViewModel for the main device list screen.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = (application as NetworkScannerApp).scanner
    private val customizationRepository = (application as NetworkScannerApp).deviceCustomizationRepository

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _onlineDevices = MutableStateFlow<List<Device>>(emptyList())
    val onlineDevices: StateFlow<List<Device>> = _onlineDevices.asStateFlow()

    private val _offlineDevices = MutableStateFlow<List<Device>>(emptyList())
    val offlineDevices: StateFlow<List<Device>> = _offlineDevices.asStateFlow()

    private val _scanProgress = MutableStateFlow(ScanProgress(ScanPhase.INITIALIZING, 0f, "", 0))
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    private val _networkInfo = MutableStateFlow<NetworkInfo?>(null)
    val networkInfo: StateFlow<NetworkInfo?> = _networkInfo.asStateFlow()

    private val _availableInterfaces = MutableStateFlow<List<NetworkInterfaceOption>>(emptyList())
    val availableInterfaces: StateFlow<List<NetworkInterfaceOption>> = _availableInterfaces.asStateFlow()

    private val _selectedInterfaceName = MutableStateFlow<String?>(null)
    val selectedInterfaceName: StateFlow<String?> = _selectedInterfaceName.asStateFlow()

    private val _errorMessage = Channel<String>(Channel.BUFFERED)
    val errorMessage = _errorMessage.receiveAsFlow()

    // 新增状态：自定义范围、对话框显示等
    private val _customRange = MutableStateFlow<Pair<Long, Long>?>(null)
    val customRange: StateFlow<Pair<Long, Long>?> = _customRange.asStateFlow()

    private val _showLargeSubnetDialog = MutableStateFlow(false)
    val showLargeSubnetDialog: StateFlow<Boolean> = _showLargeSubnetDialog.asStateFlow()

    private val _showCustomRangeDialog = MutableStateFlow(false)
    val showCustomRangeDialog: StateFlow<Boolean> = _showCustomRangeDialog.asStateFlow()

    // 缓存当前网络信息，用于大子网检测
    private var currentNetworkInfo: NetworkInfo? = null

    init {
        viewModelScope.launch {
            scanner.devices.collectLatest { deviceList ->
                updateDeviceLists(deviceList)
            }
        }

        viewModelScope.launch {
            scanner.scanProgress.collectLatest { progress ->
                _scanProgress.value = progress
            }
        }

        // React to customization changes — refresh list display names
        viewModelScope.launch {
            customizationRepository.customizations.collectLatest {
                val current = _devices.value
                if (current.isNotEmpty()) updateDeviceLists(current)
            }
        }

        refreshInterfaces()
    }

    /**
     * Get custom name for a device, or null if none set.
     */
    fun getCustomName(deviceId: String): String? =
        customizationRepository.getCustomization(deviceId)?.customName

    /**
     * Get custom icon for a device, or null if none set.
     */
    fun getCustomIcon(deviceId: String): String? =
        customizationRepository.getCustomization(deviceId)?.customIcon

    /**
     * Refresh active network interfaces and keep selection valid.
     */
    fun refreshInterfaces() {
        val interfaces = NetworkUtils.getAvailableInterfaces()
        _availableInterfaces.value = interfaces

        val current = _selectedInterfaceName.value
        _selectedInterfaceName.value = when {
            interfaces.isEmpty() -> null
            current != null && interfaces.any { it.name == current } -> current
            else -> interfaces.first().name
        }

        _networkInfo.value = _networkInfo.value
            ?: NetworkUtils.getNetworkInfo(getApplication(), _selectedInterfaceName.value)
    }

    fun onInterfaceSelected(interfaceName: String) {
        _selectedInterfaceName.value = interfaceName
        _networkInfo.value = NetworkUtils.getNetworkInfo(getApplication(), interfaceName)
    }

    /**
     * Start network scan.
     */
    fun startScan() {
        if (_uiState.value is UiState.Scanning) return

        viewModelScope.launch {
            refreshInterfaces()
            val interfaceName = _selectedInterfaceName.value
            if (interfaceName == null) {
                _uiState.value = UiState.NoWifi
                return@launch
            }

            // 获取网络信息
            val netInfo = NetworkUtils.getNetworkInfo(getApplication(), interfaceName)
            if (netInfo == null) {
                _uiState.value = UiState.NoWifi
                return@launch
            }
            currentNetworkInfo = netInfo

            // 检测大子网
            val prefix = netInfo.networkPrefix
            if (IpUtils.isLargeSubnet(prefix)) {
                // 如果尚未设置自定义范围，则弹出大子网警告对话框
                if (_customRange.value == null) {
                    _showLargeSubnetDialog.value = true
                    return@launch
                }
            }

            // 否则直接执行扫描（使用现有 customRange 或 null）
            performScan(interfaceName, _customRange.value)
        }
    }

    /**
     * 实际执行扫描的私有方法
     */
    private suspend fun performScan(interfaceName: String, range: Pair<Long, Long>?) {
        _uiState.value = UiState.Scanning
        try {
            val result = if (range != null) {
                scanner.scan(interfaceName, range)
            } else {
                scanner.scan(interfaceName)
            }
            _networkInfo.value = result.networkInfo
            updateDeviceLists(result.devices)

            when (result.scanStatus) {
                ScanStatus.COMPLETED -> {
                    if (result.devices.isEmpty()) {
                        _uiState.value = UiState.Empty
                    } else {
                        _uiState.value = UiState.Success(result)
                    }
                }
                ScanStatus.ERROR -> {
                    _uiState.value = UiState.Error(result.error ?: "Unknown error")
                    _errorMessage.trySend(result.error ?: "Unknown error")
                }
                else -> {
                    _uiState.value = UiState.Idle
                }
            }
        } catch (e: Exception) {
            _uiState.value = UiState.Error(e.message ?: "Scan failed")
            _errorMessage.trySend(e.message ?: "Scan failed")
        }
    }

    /**
     * Cancel ongoing scan.
     */
    fun cancelScan() {
        scanner.cancel()
        _uiState.value = UiState.Idle
    }

    /**
     * 大子网选项枚举
     */
    enum class LargeSubnetOption {
        SCAN_24,      // 仅扫描 /24
        SCAN_CUSTOM,  // 自定义范围
        SCAN_ALL      // 扫描整个大子网（警告）
    }

    /**
     * 处理大子网对话框的用户选择
     */
    fun onLargeSubnetOption(option: LargeSubnetOption) {
        _showLargeSubnetDialog.value = false
        val netInfo = currentNetworkInfo ?: return
        val interfaceName = _selectedInterfaceName.value ?: return

        when (option) {
            LargeSubnetOption.SCAN_24 -> {
                // 使用 /24 默认范围
                val defaultRange = IpUtils.getDefaultRangeForNetwork(netInfo)
                if (defaultRange != null) {
                    _customRange.value = defaultRange
                    viewModelScope.launch { performScan(interfaceName, defaultRange) }
                } else {
                    // 回退到原始 /24 范围（以防解析失败）
                    viewModelScope.launch { performScan(interfaceName, null) }
                }
            }
            LargeSubnetOption.SCAN_CUSTOM -> {
                // 显示自定义范围输入对话框
                _showCustomRangeDialog.value = true
            }
            LargeSubnetOption.SCAN_ALL -> {
                // 扫描整个大子网（真实范围）
                val realRange = IpUtils.getSubnetRange(
                    IpUtils.ipToLong(netInfo.ipAddress),
                    netInfo.networkPrefix
                )
                _customRange.value = realRange
                viewModelScope.launch { performScan(interfaceName, realRange) }
            }
        }
    }

    /**
     * 自定义范围确认回调
     */
    fun onCustomRangeConfirmed(startIp: String, endIp: String) {
        _showCustomRangeDialog.value = false
        // 解析并验证 IP 范围
        val start = IpUtils.ipToLong(startIp)
        val end = IpUtils.ipToLong(endIp)
        if (start > end) {
            _errorMessage.trySend("起始 IP 不能大于结束 IP")
            return
        }
        // 可选：限制 IP 数量，防止过大
        val count = end - start + 1
        if (count > 10000) {  // 可调整阈值
            _errorMessage.trySend("IP 数量过多（${count}个），请缩小范围")
            return
        }
        _customRange.value = start to end
        val interfaceName = _selectedInterfaceName.value ?: return
        viewModelScope.launch { performScan(interfaceName, start to end) }
    }

    fun cancelCustomRange() {
        _showCustomRangeDialog.value = false
    }

    fun openCustomRangeDialog() {
        _showCustomRangeDialog.value = true
    }

    fun dismissLargeSubnetDialog() {
        _showLargeSubnetDialog.value = false
    }

    /**
     * Get device by unique ID (MAC or IP).
     */
    fun getDeviceById(id: String): Device? {
        return _devices.value.find { it.uniqueId == id }
    }

    private fun updateDeviceLists(deviceList: List<Device>) {
        // Apply custom names from repository
        val customized = deviceList.map { device ->
            val custom = customizationRepository.getCustomization(device.uniqueId)
            if (custom != null) device.copy(customName = custom.customName) else device
        }

        _devices.value = customized

        val sorted = customized.sortedWith(
            compareBy(
                { !it.isCurrentDevice },
                { !it.isOnline },
                {
                    it.ipAddress
                        .split('.')
                        .take(4)
                        .fold(0L) { acc, part ->
                            (acc shl 8) or ((part.toLongOrNull() ?: 0L) and 0xFF)
                        }
                }
            )
        )

        _onlineDevices.value = sorted.filter { it.isOnline }
        _offlineDevices.value = sorted.filter { !it.isOnline }
    }

    override fun onCleared() {
        super.onCleared()
        scanner.cancel()
    }

    /**
     * UI state sealed class.
     */
    sealed class UiState {
        data object Idle : UiState()
        data object Scanning : UiState()
        data object NoWifi : UiState()
        data object Empty : UiState()
        data class Success(val result: ScanResult) : UiState()
        data class Error(val message: String) : UiState()
    }
}
