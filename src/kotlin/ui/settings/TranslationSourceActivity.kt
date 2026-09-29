package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.TranslateController
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TranslationSourceActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslationSource)

    private val languages by lazy { TranslateController.getLanguages() }
    private val suggested by lazy { TranslateController.getSuggestedLanguages(null) }
    private val idToCode = HashMap<Int, String>()

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        idToCode.clear()
        val current = InuConfig.TRANSLATE_SOURCE_LANGUAGE.value
        val autoId = 1
        idToCode[autoId] = ""
        items.add(
            UItem.asRadio(
                autoId,
                LocaleController.getString(R.string.InuTranslateAutoDetectLang),
                "",
            ).setChecked(current.isEmpty())
        )

        var nextId = 2
        for (lang in suggested) {
            val code = lang.code ?: continue
            val id = nextId++
            idToCode[id] = code
            val title = lang.displayName
            items.add(UItem.asRadio(id, title, lang.ownDisplayName).setChecked(code == current))
        }
        items.add(UItem.asShadow(null))

        for (lang in languages) {
            val code = lang.code ?: continue
            val id = nextId++
            idToCode[id] = code
            val title = lang.displayName
            items.add(UItem.asRadio(id, title, lang.ownDisplayName).setChecked(code == current))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val code = idToCode[item.id] ?: return
        InuConfig.TRANSLATE_SOURCE_LANGUAGE.value = code
        listView?.adapter?.update(true)
    }

    companion object {
        @JvmField val PAGE = SearchRegistry.Page(
            slug = "translation-source",
            titleRes = R.string.InuTranslationSource,
            iconRes = R.drawable.msg_translate,
            factory = ::TranslationSourceActivity,
        )
    }
}
