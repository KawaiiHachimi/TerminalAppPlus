/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.terminal.new2.core

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.os.StatFs
import android.os.SystemProperties
import android.system.virtualizationcommon.IGuestAgent
import android.system.virtualizationservice.DisplayConfig
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineException
import android.system.virtualmachine.VirtualMachineManager
import android.util.Log
import com.android.system.virtualmachine.flags.Flags
import com.android.virtualization.debian.aidl.IDebianService
import com.android.virtualization.terminal.AndroidToVmBridge
import com.android.virtualization.terminal.CertificateUtils
import com.android.virtualization.terminal.ConfigJson
import com.android.virtualization.terminal.GraphicsManager
import com.android.virtualization.terminal.InstalledImage
import com.android.virtualization.terminal.InstalledImage.Companion.roundUp
import com.android.virtualization.terminal.Logger
import com.android.virtualization.terminal.TerminalThreadFactory
import com.android.virtualization.terminal.new2.ui.main.SettingsViewModel
import com.android.virtualization.terminal.new2.util.LoggingMutableStateFlow
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object VmController {
    private val TAG = "VmController"

    private val _terminalConnection = MutableStateFlow<TerminalConnection>(TerminalConnection.Disconnected)
    val terminalConnection: StateFlow<TerminalConnection> = _terminalConnection.asStateFlow()
    private var terminalConnectionJob: Job? = null
    private var terminalTimeoutSecs = 60L
    private var runningImage: InstalledImage? = null
    private var runningProfile: VmProfile? = null
    private val _switching = MutableStateFlow(false)
    val switching = _switching.asStateFlow()
    private val lifecycleMutex = Mutex()

    private var terminalBridge: AndroidToVmBridge? = null
    private lateinit var context: Context
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _vmState = LoggingMutableStateFlow<VmState>(MutableStateFlow(VmState.Ready), TAG)
    val vmState: StateFlow<VmState> = _vmState.asStateFlow()

    private val _sessionDiscarded = MutableSharedFlow<String>()
    val sessionDiscarded: SharedFlow<String> = _sessionDiscarded.asSharedFlow()

    private val _guestAgentController = MutableStateFlow<GuestAgentController?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val ports: StateFlow<List<OpenPort>> =
        _guestAgentController
            .flatMapLatest { controller ->
                // If controller is null, emit a flow containing null
                controller?.ports ?: flowOf(emptyList())
            }
            .stateIn(
                scope = repositoryScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    // UI must not call vm.config: it shares AVF's lock with blocking connectVsock calls.
    @Volatile private var displayConfiguration: Pair<VirtualMachine, VirtualMachineCustomImageConfig>? = null
    fun displayConfigurationFor(vm: VirtualMachine): VirtualMachineCustomImageConfig? =
        displayConfiguration?.takeIf { it.first === vm }?.second

    private data class ResizeRequest(val vm: VirtualMachine, val width: Int, val height: Int, val dpi: Int, val refreshRate: Int)
    private val displayResizeRequests = kotlinx.coroutines.channels.Channel<ResizeRequest>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val displayResizeWorker = repositoryScope.launch {
        for (request in displayResizeRequests) {
            if (virtualMachine !== request.vm || _vmState.value != VmState.Running) continue
            applyDisplayResize(request)
        }
    }

    @Volatile var virtualMachine: VirtualMachine? = null
        private set

    fun initialize(context: Context) {
        this.context = context.applicationContext
        VmProfiles.initialize(this.context)
        val key = CertificateUtils.createOrGetKey()
        CertificateUtils.writeCertificateToFile(this.context, key.certificate)
    }

    fun reset() {
        if (
            _vmState.value == VmState.Stopped ||
                _vmState.value is VmState.Error ||
                _vmState.value == VmState.Rebooting
        ) {
            _vmState.value = VmState.Ready
        }
    }

    fun enablePortForwarding(port: Int, enable: Boolean) {
        _guestAgentController.value?.enablePortForwarding(port, enable)
    }

    val graphicsAccelerationType: GraphicsManager.AccelerationType
        get() = GraphicsManager.getInstance(context).accelerationType

    val isGraphicsAccelerationSupported: Boolean
        get() = GraphicsManager.getInstance(context).isGfxstreamSupported

    fun setGraphicsAccelerationType(type: GraphicsManager.AccelerationType) {
        GraphicsManager.getInstance(context).accelerationType = type
    }

    fun shutdownVm() {
        _guestAgentController.value?.shutdownVm()
    }

    fun pullClipboardFromGuest() {
        _guestAgentController.value?.pullClipboardFromGuest()
    }

    fun pushClipboardToGuest() {
        _guestAgentController.value?.pushClipboardToGuest()
    }

    fun requestSessionDiscard(sessionId: String) {
        repositoryScope.launch { _sessionDiscarded.emit(sessionId) }
    }

    // Never perform display Binder calls from a Surface callback on the main thread.
    fun resizeDisplay(width: Int, height: Int, dpi: Int, refreshRate: Int, expectedVm: VirtualMachine? = virtualMachine) {
        val vm = expectedVm ?: return
        displayResizeRequests.trySend(ResizeRequest(vm, width, height, dpi, refreshRate))
    }

    private fun applyDisplayResize(request: ResizeRequest) {
        val (vm, width, height, dpi, refreshRate) = request
        try {
            val displays = vm.getDisplays()
            if (displays.isNotEmpty()) {
                val oldDisplay = displays[0]
                if (
                    oldDisplay.config.width == width &&
                        oldDisplay.config.height == height &&
                        oldDisplay.config.horizontalDpi == dpi && oldDisplay.config.verticalDpi == dpi &&
                        oldDisplay.config.refreshRate == refreshRate
                ) {
                    return
                }
            }
            val config = DisplayConfig()
            config.width = width
            config.height = height
            config.horizontalDpi = dpi
            config.verticalDpi = dpi
            config.refreshRate = refreshRate
            vm.addDisplay(config)
            if (displays.isNotEmpty()) {
                val oldDisplay = displays[0]
                vm.removeDisplay(oldDisplay.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize display", e)
        }
    }

    @Synchronized fun start() {
        if (_vmState.value is VmState.Running || _vmState.value is VmState.Starting || _vmState.value is VmState.Stopping) return
        val requestedProfile = VmProfiles.selected.value
        _terminalConnection.value = TerminalConnection.Connecting
        _vmState.value = VmState.Starting

        val intent = Intent(context, VmService::class.java)
        context.startForegroundService(intent)
        repositoryScope.launch {
            lifecycleMutex.withLock {
                if (_vmState.value != VmState.Starting) return@withLock
                try {
                    val profile = requestedProfile
                    runningProfile = profile
                    TerminalSessionRepository.reset(openInitialTab = profile.screen != "console")
                    val configText = VmProfiles.readConfig(profile)
                    val document = VmConfigDocument.parse(configText)
                    VmConfigDocument.validateFiles(document, VmProfiles.payloadDirectory(profile))
                    val config = buildProfileConfig(profile, configText)
                    val vmm = context.getSystemService(VirtualMachineManager::class.java)!!
                    val vmName = config.customImageConfig!!.name!!

                    try {
                        vmm.get(vmName)?.let { // Clean up existing VM if it's not stopped
                            it.clearCallback()
                            if (it.status != VirtualMachine.STATUS_STOPPED) {
                                Log.e(TAG, "stopping vm because it is not stopped")
                                it.stop()
                            }
                            // TODO: revisit this to see if we can omit this step.
                            vmm.delete(vmName)
                        }
                    } catch (e: VirtualMachineException) {
                        // Ignore if VM doesn't exist
                    }

                    val vm = vmm.create(vmName, config)
                    displayConfiguration = vm to checkNotNull(config.customImageConfig)
                    virtualMachine = vm
                    com.android.virtualization.terminal.ForwarderHost.attach(vm)
                    com.android.virtualization.terminal.VmConsole.begin(vm)
                    val logExecutor = Executors.newFixedThreadPool(2)
                    try {
                        Logger.setup(context, vm, logExecutor) { bytes ->
                            com.android.virtualization.terminal.VmConsole.publish(vm, bytes)
                        }
                    } finally { logExecutor.shutdown() }

                    val callbackExecutor = Executors.newSingleThreadExecutor()
                    val callback =
                        object : VirtualMachineCallback {
                            override fun onPayloadStarted(vm: VirtualMachine) {}

                            override fun onPayloadReady(vm: VirtualMachine) {}

                            override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {}

                            override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
                                if (virtualMachine !== vm) return
                                Log.e(TAG, "VM error: $message ($errorCode)")
                                _vmState.value = VmState.Error(RuntimeException("VM error: $message"))
                                _guestAgentController.value?.stop()
                            }

                            override fun onStopped(vm: VirtualMachine, reason: Int) {
                                callbackExecutor.shutdown()
                                synchronized(VmController) {
                                    if (virtualMachine !== vm) return
                                    val requestedStop = _vmState.value == VmState.Stopping
                                    com.android.virtualization.terminal.VmConsole.end(vm)
                                    disconnectTerminal()
                                    virtualMachine = null
                                    _guestAgentController.value?.stop()
                                    Log.i("VmController", "VM stopped. reason: $reason")
                                    // Explicit stop publishes Stopped after its cleanup, before allowing a switch.
                                    if (requestedStop) return
                                    if (
                                        reason == VirtualMachineCallback.STOP_REASON_SHUTDOWN ||
                                            reason == VirtualMachineCallback.STOP_REASON_KILLED
                                    ) {
                                        _vmState.value = VmState.Stopped
                                    } else if (reason == VirtualMachineCallback.STOP_REASON_REBOOT) {
                                        _vmState.value = VmState.Rebooting
                                    } else {
                                        Log.e("VmController", "VM stopped unexpectedly. reason: $reason")
                                        _vmState.value =
                                            VmState.Error(
                                                RuntimeException("VM stopped unexpectedly: $reason")
                                            )
                                    }
                                }
                            }

                            override fun onGuestAgentRegistered(
                                vm: VirtualMachine,
                                guestAgent: IGuestAgent,
                            ) {
                                if (virtualMachine !== vm) return
                                Log.d(TAG, "Guest agent registered. Imply AIDL connection")

                                var binder: IBinder? = null
                                try {
                                    binder = vm.connectToVsockServer(IDebianService.VSOCK_PORT)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Guest agent unavailable; VM remains running", e)
                                    return
                                }
                                val debian_service = IDebianService.Stub.asInterface(binder)

                                val cid = vm!!.cid
                                _guestAgentController.value?.start(cid, guestAgent, debian_service)

                                Log.d(TAG, "Guest agent ready")
                            }
                        }

                    vm.setCallback(callbackExecutor, callback)
                    try { vm.run() } catch (e: Exception) {
                        callbackExecutor.shutdown()
                        throw e
                    }

                    runCatching { VmProfiles.markStarted(profile, configText) }
                    // Guest services are optional. AVF running is sufficient to expose console/display.
                    if (!_vmState.compareAndSet(VmState.Starting, VmState.Running)) return@withLock
                    retryTerminalConnection()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start VM", e)
                    if (_vmState.value != VmState.Stopping) _vmState.value = VmState.Error(e)
                    disconnectTerminal()
                    _guestAgentController.value?.stop()
                }
            }
        }
    }

    private fun buildProfileConfig(profile: VmProfile, text: String): android.system.virtualmachine.VirtualMachineConfig {
        val payload = VmProfiles.payloadDirectory(profile)
        val json = ConfigJson.fromText(context, text, payload.toPath(), profile.isManaged)
        terminalTimeoutSecs = json.getBootTimeoutSecs().toLong() * (if (IS_EMULATOR) 10 else 1)
        val configBuilder = json.toConfigBuilder(context)
        configBuilder.setVmConsoleInputSupported(true).setConnectVmConsole(false)
        val custom = json.toCustomImageConfigBuilder(context)
        if (!profile.isManaged) {
            val toolsDisk = com.android.virtualization.terminal.GuestToolsDisk.prepare(context)
            custom.addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(toolsDisk.absolutePath))
        }
        runningImage = if (profile.isManaged) InstalledImage.fromDirectory(payload) else null
        _guestAgentController.value = null
        runningImage?.let { image ->
            _guestAgentController.value = GuestAgentController(context, image.isAidlGuestAgent(), repositoryScope)
            truncateDiskIfNecessary(image)
            if (image.hasBackup()) custom.addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(image.backupFile.toString()))
            custom.addParam("debian_server_port=${_guestAgentController.value!!.startServer()}")
            if (!json.hasAudio()) custom.setAudioConfig(VirtualMachineCustomImageConfig.AudioConfig.Builder().setUseSpeaker(true).setUseMicrophone(true).build())
        }
        if (!json.hasDisplay()) setDisplayConfig(custom)
        configBuilder.setCustomImageConfig(custom.build())
        return configBuilder.build()
    }

    suspend fun <T> withStoppedProfile(profile: VmProfile, operation: suspend () -> T): T = lifecycleMutex.withLock {
        check(!(runningProfile?.id == profile.id && (virtualMachine?.status == VirtualMachine.STATUS_RUNNING || _vmState.value.isAlive))) {
            "请先关闭这台虚拟机，再克隆或删除"
        }
        check(!(_vmState.value == VmState.Starting && VmProfiles.selected.value.id == profile.id)) { "虚拟机正在启动" }
        operation()
    }

    /** Caller confirms forced power-off before switching a running VM. Disk files are retained. */
    suspend fun switchTo(profile: VmProfile) {
        check(_switching.compareAndSet(false, true)) { "正在切换虚拟机" }
        try {
            if (_vmState.value.isAlive || virtualMachine?.status == VirtualMachine.STATUS_RUNNING) {
                stop()
                kotlinx.coroutines.withTimeout(30_000) {
                    vmState.first { it != VmState.Stopping }
                }
                check(virtualMachine?.status != VirtualMachine.STATUS_RUNNING && _vmState.value == VmState.Stopped) {
                    "当前虚拟机未能停止，未切换镜像"
                }
            }
            VmProfiles.select(profile)
            if (profile.isDefault && !VmProfiles.isInstalled(profile)) {
                VmProfiles.requestOfficial()
                _vmState.value = VmState.Ready
            } else start()
        } finally {
            _switching.value = false
        }
    }

    private fun setGpuConfig(context: Context, builder: VirtualMachineCustomImageConfig.Builder) {
        if (GraphicsManager.getInstance(context).isGfxstreamEnabled()) {
            builder.addParam("gfxstream_enabled")
            builder.setGpuConfig(
                VirtualMachineCustomImageConfig.GpuConfig.Builder()
                    .setBackend("gfxstream")
                    .setRendererUseEgl(false)
                    .setRendererUseGles(false)
                    .setRendererUseGlx(false)
                    .setRendererUseSurfaceless(true)
                    .setRendererUseVulkan(true)
                    .setContextTypes(arrayOf<String>("gfxstream-vulkan", "gfxstream-composer"))
                    // TODO(b/492372616): check if we need to set these features for kernel 6.12
                    .setRendererFeatures(
                        "VulkanDisableCoherentMemoryAndEmulate:enabled;VulkanAllocateHostVisibleAsUdmabuf:enabled;ExternalBlob:enabled"
                    )
                    .build()
            )
        }
    }

    private fun setDisplayConfig(builder: VirtualMachineCustomImageConfig.Builder) {
        // Set a placeholder display config to prevent gfxstream from crashing when it's enabled.
        // It will be replaced with the actual resolution once the surface is created.
        builder
            .setDisplayConfig(
                VirtualMachineCustomImageConfig.DisplayConfig.Builder()
                    .setWidth(1280)
                    .setHeight(720)
                    .setHorizontalDpi(160)
                    .setVerticalDpi(160)
                    .setRefreshRate(60)
                    .build()
            )
            .useKeyboard(true)
            .useMouse(true)
            .useTouch(true)
            .useTrackpad(true)
    }

    private fun disconnectTerminal() {
        terminalConnectionJob?.cancel()
        terminalConnectionJob = null
        terminalBridge?.stop()
        terminalBridge = null
        _terminalConnection.value = TerminalConnection.Disconnected
    }

    @Synchronized fun retryTerminalConnection() {
        val vm = virtualMachine ?: return
        if (_vmState.value != VmState.Running || terminalConnectionJob?.isActive == true) return
        _terminalConnection.value = TerminalConnection.Connecting
        terminalConnectionJob = repositoryScope.launch {
            try {
                if (canUseTtydOverVsock()) {
                    terminalBridge?.stop()
                    val bridge = AndroidToVmBridge(vm)
                    terminalBridge = bridge
                    val port = bridge.start() ?: error("Failed to start terminal bridge")
                    if (virtualMachine !== vm || _vmState.value != VmState.Running) {
                        bridge.stop()
                        return@launch
                    }
                    _terminalConnection.value = TerminalConnection.Endpoint(
                        TerminalAddress("localhost", port, bridge.secretKey))
                    if (runningProfile?.isManaged == true) repositoryScope.launch {
                        runCatching {
                            com.android.virtualization.terminal.GuestScreenSetup.ensure(context, port, bridge.secretKey, VmProfiles.payloadDirectory(runningProfile!!)) {
                                virtualMachine === vm && vm.status == VirtualMachine.STATUS_RUNNING
                            }
                        }.onFailure { Log.w(TAG, "Guest capture setup failed", it) }
                    }
                } else {
                    discoverTerminal(vm)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Terminal unavailable; VM remains running", e)
                if (virtualMachine === vm && _vmState.value == VmState.Running) {
                    _terminalConnection.value = TerminalConnection.Unavailable(e.message ?: "Terminal unavailable")
                }
            }
        }
    }

    private suspend fun discoverTerminal(vm: VirtualMachine) {
        val executor = Executors.newSingleThreadExecutor(TerminalThreadFactory(context.applicationContext))
        val nsdManager = context.getSystemService(NsdManager::class.java)!!
        val queryInfo = NsdServiceInfo().apply {
            serviceType = "_http._tcp"
            serviceName = "ttyd"
        }
        val result = kotlinx.coroutines.CompletableDeferred<TerminalAddress>()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                result.completeExceptionally(IOException("Terminal discovery failed: $errorCode"))
            }
            override fun onServiceInfoCallbackUnregistered() {}
            override fun onServiceLost() {}
            override fun onServiceUpdated(info: NsdServiceInfo) {
                val address = info.hostAddresses.firstOrNull { !it.isLinkLocalAddress }?.hostAddress ?: return
                result.complete(TerminalAddress(address, info.port))
            }
        }
        try {
            nsdManager.registerServiceInfoCallback(queryInfo, executor, callback)
            val address = kotlinx.coroutines.withTimeoutOrNull(TimeUnit.SECONDS.toMillis(terminalTimeoutSecs)) {
                result.await()
            }
            if (virtualMachine === vm && _vmState.value == VmState.Running) {
                _terminalConnection.value = if (address != null) TerminalConnection.Endpoint(address)
                    else TerminalConnection.Unavailable("Timed out waiting for terminal service")
            }
        } finally {
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            executor.shutdown()
        }
    }

    private fun canUseTtydOverVsock(): Boolean {
        if (runningProfile?.isManaged == false) return true
        val buildId = runningImage?.buildInfo?.buildId ?: 0
        val FIRST_VERSION_SUPPORTS_TTYD_VSOCK = 5106
        return Flags.terminalVmCommunicationRefactoring() &&
            buildId >= FIRST_VERSION_SUPPORTS_TTYD_VSOCK
    }

    @Synchronized fun stop() {
        if (_vmState.value == VmState.Stopped || _vmState.value == VmState.Stopping) return
        _vmState.value = VmState.Stopping
        repositoryScope.launch {
            lifecycleMutex.withLock {
                disconnectTerminal()
                try {
                    virtualMachine?.stop()
                } catch (e: VirtualMachineException) {
                    Log.w("VmController", "Failed to stop VM", e)
                    if (virtualMachine?.status == VirtualMachine.STATUS_RUNNING) {
                        _vmState.value = VmState.Running
                        retryTerminalConnection()
                        return@withLock
                    }
                }
                synchronized(VmController) {
                    virtualMachine?.let { com.android.virtualization.terminal.VmConsole.end(it) }
                    virtualMachine = null
                    _guestAgentController.value?.stop()
                    _vmState.value = VmState.Stopped
                }
            }
        }
    }

    private fun calculateSparseDiskSize(): Long {
        // Create a sparse file with 95% of the total size for storage ballooning.
        val statFs = StatFs(context.filesDir.absolutePath)
        val hostSize = statFs.totalBytes
        return roundUp(hostSize * GUEST_SPARSE_DISK_SIZE_PERCENTAGE / 100)
    }

    private fun truncateDiskIfNecessary(image: InstalledImage) {
        val curSize = image.getApparentSize()
        val physicalSize = image.getPhysicalSize()

        val expectedSize = calculateSparseDiskSize()
        Log.d(
            TAG,
            "rootfs apparent size=$curSize, physical size=$physicalSize, expectedSize=$expectedSize",
        )

        if (curSize != expectedSize) {
            try {
                image.truncate(expectedSize)
            } catch (e: IOException) {
                throw RuntimeException("Failed to truncate a disk", e)
            }
        }
    }

    private const val GUEST_SPARSE_DISK_SIZE_PERCENTAGE = 95

    private val IS_EMULATOR: Boolean =
        {
            val deviceName = SystemProperties.get("ro.product.vendor.device", "")
            val cuttlefish = deviceName.startsWith("vsoc_")
            val goldfish = deviceName.startsWith("emu64")

            cuttlefish || goldfish
        }()
}
