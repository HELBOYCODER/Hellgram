package desu.inugram.ui.helboy

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.helboy.HelboyKind
import desu.inugram.helpers.helboy.HelboyStore
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class HelboyActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuHelboyTv)

    private val kindById = HashMap<Int, HelboyKind>()
    private var favoritesId = 0

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        kindById.clear()
        favoritesId = 0
        items.add(mkTwoLineCheckItem(TOGGLE, R.string.InuHelboyTv, R.string.InuHelboyTvInfo, InuConfig.HELBOY_TV.value))
        items.add(UItem.asShadow(null))
        for (kind in HelboyKind.entries) {
            val id = InuUtils.generateId()
            kindById[id] = kind
            items.add(UItem.asButton(id, iconFor(kind), kindTitle(kind), countLabel(kind)))
        }
        if (HelboyStore.favoriteIds().isNotEmpty()) {
            favoritesId = InuUtils.generateId()
            items.add(UItem.asButton(
                favoritesId,
                R.drawable.inu_tabler_star,
                LocaleController.getString(R.string.InuHelboySectionFavorites),
                HelboyStore.favoriteIds().size.toString(),
            ))
        }
    }

    private fun countLabel(kind: HelboyKind): String =
        if (HelboyStore.ready()) HelboyStore.counts(kind).toString() else ""

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE -> {
                InuConfig.HELBOY_TV.value = !InuConfig.HELBOY_TV.value
                listView?.adapter?.update(true)
            }

            kindById[item.id] != null -> {
                presentFragment(HelboyListActivity.forCountries(kindById[item.id]!!))
            }

            favoritesId != 0 && item.id == favoritesId -> {
                presentFragment(HelboyListActivity.favorites())
            }
        }
    }

    companion object {
        private val TOGGLE = InuUtils.generateId()

        @JvmStatic
        fun kindTitle(kind: HelboyKind): CharSequence = when (kind) {
            HelboyKind.TV -> LocaleController.getString(R.string.InuHelboySectionTv)
            HelboyKind.RADIO -> LocaleController.getString(R.string.InuHelboySectionRadio)
            HelboyKind.WEBCAM -> LocaleController.getString(R.string.InuHelboySectionWebcams)
        }

        @JvmStatic
        fun iconFor(kind: HelboyKind): Int = when (kind) {
            HelboyKind.TV -> R.drawable.inu_tabler_device_tv
            HelboyKind.RADIO -> R.drawable.inu_tabler_radio
            HelboyKind.WEBCAM -> R.drawable.inu_tabler_device_cctv
        }

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "helboy-tv",
            titleRes = R.string.InuHelboyTv,
            iconRes = R.drawable.inu_tabler_device_tv,
            factory = ::HelboyActivity,
            entries = listOf(
                SearchRegistry.Entry("helboy-tv-toggle", R.string.InuHelboyTvInfo, TOGGLE),
            ),
        )
    }
}
