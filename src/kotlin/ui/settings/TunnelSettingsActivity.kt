package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import android.widget.EditText
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.network.BuiltInTunnelHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TunnelSettingsActivity : SettingsPageActivity() {

    private val observer = NotificationCenter.NotificationCenterDelegate { _, _, _ ->
        listView?.adapter?.update(false)
    }

    override fun createView(context: android.content.Context): View {
        return super.createView(context).also {
            NotificationCenter.getGlobalInstance().addObserver(observer, NotificationCenter.proxySettingsChanged)
        }
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(observer)
        super.onFragmentDestroy()
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuBuiltInTunnel)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val connected = BuiltInTunnelHelper.isActive()
        val starting = BuiltInTunnelHelper.isStarting()
        items.add(UItem.asButton(
            BUTTON_CONNECT,
            R.drawable.inu_tabler_shield_lock,
            LocaleController.getString(if (connected || starting) R.string.InuTunnelDisconnect else R.string.InuTunnelConnect),
        ))
        items.add(UItem.asShadow(statusLine()))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTunnelProtocol)))
        for ((index, labelRes) in PROTOCOL_LABELS.withIndex()) {
            val selected = InuConfig.BUILT_IN_TUNNEL_PROTOCOL.value == index
            items.add(
                UItem.asRadio(PROTOCOL_BASE + index, LocaleController.getString(labelRes))
                    .also { it.checked = selected }
            )
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTunnelScan)))
        for ((index, labelRes) in SCAN_LABELS.withIndex()) {
            val selected = InuConfig.BUILT_IN_TUNNEL_SCAN.value == index
            items.add(
                UItem.asRadio(SCAN_BASE + index, LocaleController.getString(labelRes))
                    .also { it.checked = selected }
            )
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTunnelScanInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTunnelPeer)))
        val peer = InuConfig.BUILT_IN_TUNNEL_PEER.value
        items.add(UItem.asButton(BUTTON_PEER, R.drawable.inu_tabler_server, LocaleController.getString(R.string.InuTunnelPeerSet), if (peer.isEmpty()) "—" else peer))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTunnelPeerInfo)))

        items.add(UItem.asButton(BUTTON_LOGS, R.drawable.inu_tabler_terminal_2, LocaleController.getString(R.string.InuTunnelLogs)))
    }

    private fun statusLine(): CharSequence = when {
        BuiltInTunnelHelper.isActive() ->
            String.format(LocaleController.getString(R.string.InuTunnelStatusConnected), BuiltInTunnelHelper.statusText())
        BuiltInTunnelHelper.isStarting() -> LocaleController.getString(R.string.InuTunnelStatusConnecting)
        InuConfig.BUILT_IN_TUNNEL.value ->
            String.format(LocaleController.getString(R.string.InuTunnelStatusFailed), BuiltInTunnelHelper.statusText())
        else -> LocaleController.getString(R.string.InuTunnelStatusOff)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_CONNECT -> {
                if (BuiltInTunnelHelper.isActive() || BuiltInTunnelHelper.isStarting()) {
                    BuiltInTunnelHelper.disconnect()
                } else {
                    BuiltInTunnelHelper.connect()
                }
                listView?.adapter?.update(true)
            }

            item.id == BUTTON_PEER -> showPeerDialog()

            item.id == BUTTON_LOGS -> showLogsDialog()

            item.id in PROTOCOL_BASE until PROTOCOL_BASE + PROTOCOL_LABELS.size -> {
                val protocol = item.id - PROTOCOL_BASE
                if (InuConfig.BUILT_IN_TUNNEL_PROTOCOL.value != protocol) {
                    InuConfig.BUILT_IN_TUNNEL_PROTOCOL.value = protocol
                    BuiltInTunnelHelper.restartIfNeeded()
                    listView?.adapter?.update(true)
                }
            }

            item.id in SCAN_BASE until SCAN_BASE + SCAN_LABELS.size -> {
                val scan = item.id - SCAN_BASE
                if (InuConfig.BUILT_IN_TUNNEL_SCAN.value != scan) {
                    InuConfig.BUILT_IN_TUNNEL_SCAN.value = scan
                    BuiltInTunnelHelper.restartIfNeeded()
                    listView?.adapter?.update(true)
                }
            }
        }
    }

    private fun showPeerDialog() {
        val input = EditText(this)
        input.hint = "188.116.33.145:894"
        input.setText(InuConfig.BUILT_IN_TUNNEL_PEER.value)
        input.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(this)
            .setTitle(LocaleController.getString(R.string.InuTunnelPeer))
            .setView(input)
            .setPositiveButton(LocaleController.getString(R.string.InuTunnelPeerSave)) { _, _ ->
                InuConfig.BUILT_IN_TUNNEL_PEER.value = input.text.toString().trim()
                BuiltInTunnelHelper.restartIfNeeded()
                listView?.adapter?.update(true)
            }
            .setNeutralButton(LocaleController.getString(R.string.InuTunnelPeerClear)) { _, _ ->
                InuConfig.BUILT_IN_TUNNEL_PEER.value = ""
                BuiltInTunnelHelper.restartIfNeeded()
                listView?.adapter?.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun showLogsDialog() {
        val text = BuiltInTunnelHelper.logsText().ifBlank { "—" }
        AlertDialog.Builder(this)
            .setTitle(LocaleController.getString(R.string.InuTunnelLogs))
            .setMessage(text.take(4000))
            .setPositiveButton(LocaleController.getString(R.string.Done), null)
            .show()
    }

    companion object {
        private val BUTTON_CONNECT = InuUtils.generateId()
        private val BUTTON_PEER = InuUtils.generateId()
        private val BUTTON_LOGS = InuUtils.generateId()
        private val PROTOCOL_BASE = InuUtils.generateId()
        private val SCAN_BASE = InuUtils.generateId()
        private val PROTOCOL_LABELS = intArrayOf(
            R.string.InuTunnelProtoMasque,
            R.string.InuTunnelProtoWireGuard,
            R.string.InuTunnelProtoGool,
            R.string.InuTunnelProtoAuto,
            R.string.InuTunnelProtoMim,
        )
        private val SCAN_LABELS = intArrayOf(
            R.string.InuTunnelScanTurbo,
            R.string.InuTunnelScanBalanced,
            R.string.InuTunnelScanThorough,
            R.string.InuTunnelScanStealth,
            R.string.InuTunnelScanIronclad,
        )

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "built-in-tunnel",
            titleRes = R.string.InuBuiltInTunnel,
            iconRes = R.drawable.inu_tabler_shield_lock,
            factory = ::TunnelSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("tunnel", R.string.InuBuiltInTunnel, BUTTON_CONNECT),
            ),
        )
    }
}
