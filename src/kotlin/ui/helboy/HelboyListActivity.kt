package desu.inugram.ui.helboy

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.helboy.HelboyChannel
import desu.inugram.helpers.helboy.HelboyChannelHealth
import desu.inugram.helpers.helboy.HelboyKind
import desu.inugram.helpers.helboy.HelboyPlayer
import desu.inugram.helpers.helboy.HelboyStore
import desu.inugram.helpers.helboy.helboyFlag
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

class HelboyListActivity : SettingsPageActivity() {

    private enum class Mode { COUNTRIES, CHANNELS, FAVORITES }

    private var mode: Mode = Mode.COUNTRIES
    private var kind: HelboyKind = HelboyKind.TV
    private var country: String = ""

    private var query = ""
    private val countryCodes = HashMap<Int, String>()
    private val channelsById = HashMap<Int, HelboyChannel>()
    private val healthIds = HashMap<Int, String>()
    private var healthUiVersion = 0
    // entiny: probe channel streams when a list opens so each row shows ● Online 320ms or ● قطع;
    // HelboyChannelHealth batches them and calls back once when the first batch settles.
    private fun startHealthProbe(list: List<HelboyChannel>) {
        if (kind != HelboyKind.TV) return
        HelboyChannelHealth.ensurePersianRefresh()
        HelboyChannelHealth.probe(list) {
            healthUiVersion++
            listView?.adapter?.update(false)
        }
    }

    private fun configure(mode: Mode, kind: HelboyKind, country: String): HelboyListActivity = apply {
        this.mode = mode
        this.kind = kind
        this.country = country
    }

    override fun getTitle(): CharSequence = when (mode) {
        Mode.COUNTRIES -> HelboyActivity.kindTitle(kind)
        Mode.CHANNELS -> "$country ${helboyFlag(country)}"
        Mode.FAVORITES -> LocaleController.getString(R.string.InuHelboySectionFavorites)
    }

    override fun createView(context: Context): View {
        return super.createView(context).also {
            val searchItem = actionBar.createMenu().addItem(0, R.drawable.outline_header_search)
                .setIsSearchField(true)
            searchItem.setSearchFieldHint(LocaleController.getString(R.string.InuHelboySearchHint))
            searchItem.setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(searchField: EditText) {
                    query = searchField.text.toString().trim().lowercase(Locale.getDefault())
                    listView?.adapter?.update(false)
                }
            })
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        countryCodes.clear()
        channelsById.clear()
        when (mode) {
            Mode.COUNTRIES -> {
                for ((code, count) in HelboyStore.countries(kind)) {
                    if (!matches(countryName(code), code)) continue
                    val id = InuUtils.generateId()
                    countryCodes[id] = code
                    items.add(UItem.asRadio(id, "$code ${helboyFlag(code)}", count.toString()).also { it.checked = false })
                }
            }

            Mode.CHANNELS -> addChannels(items, HelboyStore.channelsByCountry(kind, country))
            Mode.FAVORITES -> {
                val favs = HelboyStore.favoriteChannels()
                if (favs.isEmpty()) {
                    items.add(UItem.asShadow(LocaleController.getString(R.string.InuHelboyNoFavorites)))
                } else {
                    addChannels(items, favs)
                }
            }
        }
    }

    private fun addChannels(items: ArrayList<UItem>, list: List<HelboyChannel>) {
        if (list.isNotEmpty() && healthUiVersion == 0 && mode == Mode.CHANNELS) startHealthProbe(list)
        for (ch in list) {
            if (!matches(ch.name, ch.country)) continue
            val id = InuUtils.generateId()
            channelsById[id] = ch
            val star = if (HelboyStore.isFavorite(ch.id)) "★ " else ""
            // helboy: status subtitle — ● Online <ping>ms / ● قطع (offline) / … probing
            val subtitle = when (val st = HelboyChannelHealth.statusOf(ch)) {
                null -> helboyFlag(ch.country)
                else -> if (st.online) "● ${LocaleController.getString(R.string.InuHelboyOnline)} ${st.pingMs}ms" +
                        (if (ch.country.isNotBlank()) "  ${helboyFlag(ch.country)}" else "")
                        else "● ${LocaleController.getString(R.string.InuHelboyOffline)}  ${helboyFlag(ch.country)}"
            }
            items.add(UItem.asRadio(id, star + ch.name, subtitle).also { it.checked = false })
        }
    }

    private fun matches(vararg parts: String): Boolean {
        if (query.isEmpty()) return true
        for (p in parts) if (p.lowercase(Locale.getDefault()).contains(query)) return true
        return false
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        countryCodes[item.id]?.let { code ->
            presentFragment(HelboyListActivity().configure(Mode.CHANNELS, kind, code))
            return
        }
        channelsById[item.id]?.let { ch ->
            showChannelDialog(view.context, ch)
        }
    }

    private fun showChannelDialog(context: Context, ch: HelboyChannel) {
        val favorite = HelboyStore.isFavorite(ch.id)
        val status = HelboyChannelHealth.statusOf(ch)
        val statusLine = when {
            status == null -> ""
            status.online -> "\n● ${LocaleController.getString(R.string.InuHelboyOnline)} — ${status.pingMs}ms"
            else -> "\n● ${LocaleController.getString(R.string.InuHelboyOffline)}"
        }
        val items = arrayOf(
            LocaleController.getString(R.string.InuHelboyPlay),
            LocaleController.getString(R.string.InuHelboyNativePlayer),
            LocaleController.getString(if (favorite) R.string.InuHelboyRemoveFavorite else R.string.InuHelboyAddFavorite),
        )
        AlertDialog.Builder(context)
            .setTitle(ch.name + statusLine)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> HelboyPlayer.play(this, ch)
                    1 -> HelboyPlayer.playNative(this, ch)
                    else -> {
                        HelboyStore.toggleFavorite(ch)
                        listView?.adapter?.update(false)
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private var countryNames: Map<String, String>? = null

    private fun countryName(code: String): String {
        var map = countryNames
        if (map == null) {
            map = HashMap()
            val locale = Locale.getDefault()
            for (c in Locale.getISOCountries()) {
                map[c] = Locale(locale.language, c).getDisplayCountry(locale)
            }
            countryNames = map
        }
        return map[code] ?: code
    }

    companion object {
        @JvmStatic
        fun forCountries(kind: HelboyKind) = HelboyListActivity().configure(Mode.COUNTRIES, kind, "")

        @JvmStatic
        fun favorites() = HelboyListActivity().configure(Mode.FAVORITES, HelboyKind.TV, "")
    }
}
