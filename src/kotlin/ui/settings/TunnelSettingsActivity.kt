package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.network.BuiltInTunnelHelper
import desu.inugram.helpers.network.v2ray.V2RayScraper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory
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
        NotificationCenter.getGlobalInstance().removeObserver(observer, NotificationCenter.proxySettingsChanged)
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

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTunnelServers)))
        items.add(UItem.asButton(
            BUTTON_SCAN,
            R.drawable.inu_tabler_server,
            LocaleController.getString(R.string.InuTunnelFindServers),
            scanStageText(),
        ))
        val selected = InuConfig.BUILT_IN_TUNNEL_SERVER.value
        for ((index, server) in V2RayScraper.servers().take(SERVER_IDS.size).withIndex()) {
            val label = String.format(
                LocaleController.getString(R.string.InuTunnelServerRow),
                server.pingMs,
                server.name.take(40),
            )
            items.add(UItem.asRadio(SERVER_IDS[index], label).also { it.checked = server.link == selected })
        }
        items.add(UItem.asShadow(scanInfoText()))

        items.add(UItem.asButton(BUTTON_LOGS, R.drawable.inu_tabler_terminal_2, LocaleController.getString(R.string.InuTunnelLogs)))
        items.add(UItem.asButton(BUTTON_COPY_LOGS, R.drawable.inu_tabler_terminal_2, LocaleController.getString(R.string.InuCopyLogs)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuCopyLogsInfo)))
    }

    private fun scanStageText(): String = when (V2RayScraper.stage) {
        V2RayScraper.STAGE_LISTS -> LocaleController.getString(R.string.InuTunnelScanLists)
        V2RayScraper.STAGE_PING -> LocaleController.getString(R.string.InuTunnelScanPing)
        V2RayScraper.STAGE_TEST -> LocaleController.getString(R.string.InuTunnelScanTest)
        else -> ""
    }

    private fun scanInfoText(): CharSequence {
        val text = when (V2RayScraper.error) {
            V2RayScraper.ERROR_ENGINE -> LocaleController.getString(R.string.InuTunnelScanFailEngine)
            V2RayScraper.ERROR_LISTS -> LocaleController.getString(R.string.InuTunnelScanFailLists)
            V2RayScraper.ERROR_UNREACHABLE -> LocaleController.getString(R.string.InuTunnelScanFailNone)
            else -> return LocaleController.getString(R.string.InuTunnelServersInfo)
        }
        return if (V2RayScraper.report.isEmpty()) text else text + "\n" + V2RayScraper.report
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

            item.id == BUTTON_SCAN -> {
                val ctx = parentActivity ?: return
                V2RayScraper.scanAsync(ctx)
                listView?.adapter?.update(true)
            }

            item.id in SERVER_IDS -> {
                val server = V2RayScraper.servers().getOrNull(SERVER_IDS.indexOf(item.id)) ?: return
                BuiltInTunnelHelper.selectServer(server.link)
                listView?.adapter?.update(true)
            }

            item.id == BUTTON_LOGS -> showLogsDialog()

            // entiny: one tap puts the full diagnostics report on the clipboard so it can be pasted straight into a chat
            item.id == BUTTON_COPY_LOGS -> {
                val report = BuiltInTunnelHelper.diagnosticsText()
                org.telegram.messenger.AndroidUtilities.addToClipboard(report)
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.done,
                    LocaleController.getString(R.string.InuLogsCopied)
                ).show()
            }
        }
    }

    private fun showLogsDialog() {
        val ctx = parentActivity ?: return
        val text = BuiltInTunnelHelper.logsText().ifBlank { "—" }
        AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuTunnelLogs))
            .setMessage(text.takeLast(4000))
            .setPositiveButton(LocaleController.getString(R.string.Done), null)
            .setNeutralButton(LocaleController.getString(R.string.Copy)) { _, _ ->
                org.telegram.messenger.AndroidUtilities.addToClipboard(text)
            }
            .show()
    }

    companion object {
        private val BUTTON_CONNECT = InuUtils.generateId()
        private val BUTTON_SCAN = InuUtils.generateId()
        private val BUTTON_LOGS = InuUtils.generateId()
        private val BUTTON_COPY_LOGS = InuUtils.generateId()
        private val SERVER_IDS = IntArray(V2RayScraper.TOP) { InuUtils.generateId() }

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
