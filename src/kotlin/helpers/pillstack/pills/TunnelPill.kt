package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.helpers.network.BuiltInTunnelHelper
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity
import desu.inugram.ui.settings.TunnelSettingsActivity

// helboy: tunnel pill sitting next to the proxy pill on the chats screen. Tap = tunnel settings,
// double-purpose: shows live tunnel state (off / connecting / ping) so the shield next to the
// proxy shield is never a mystery.
@SuppressLint("ViewConstructor")
class TunnelPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)

    init {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(10f), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 2f, 0f))

        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setIncludeFontPadding(false)
        textView.setTypeface(AndroidUtilities.bold())
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)
        onUpdateData(false)
    }

    override fun getPillId(): Int = PillType.TUNNEL.id

    override fun getRefreshInterval(): Long = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onUpdateData(true)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.proxySettingsChanged) onUpdateData(true)
    }

    override fun onUpdateData(force: Boolean) {
        val enabled = desu.inugram.InuConfig.BUILT_IN_TUNNEL.value
        val active = BuiltInTunnelHelper.isActive()
        val starting = BuiltInTunnelHelper.isStarting()
        val previous = textView.text?.toString().orEmpty()

        val text: String = when {
            !enabled -> {
                iconView.setImageResource(R.drawable.inu_tabler_shield_lock)
                stopLoading()
                LocaleController.getString(R.string.InuPillStackTunnel)
            }
            active -> {
                iconView.setImageResource(R.drawable.inu_tabler_shield_lock)
                stopLoading()
                val rtt = BuiltInTunnelHelper.rttMs
                if (rtt > 0) "$rtt ms" else LocaleController.getString(R.string.InuPillStackTunnelOn)
            }
            starting -> {
                iconView.setImageResource(R.drawable.inu_tabler_shield_lock)
                startLoading()
                LocaleController.getString(R.string.InuTunnelStatusConnecting)
            }
            else -> {
                iconView.setImageResource(R.drawable.inu_tabler_shield_lock)
                stopLoading()
                LocaleController.getString(R.string.InuPillStackTunnelOff)
            }
        }

        if (force || previous != text) {
            if (force) animateSizeChange()
            textView.setText(text, force)
        }
        updateColors()
    }

    override fun onPillClicked() {
        LaunchActivity.getSafeLastFragment()?.presentFragment(TunnelSettingsActivity())
    }

    override fun onPillLongClicked(): Boolean = false

    override fun drawableHotspotChanged(x: Float, y: Float) {
        if (loading) return
        super.drawableHotspotChanged(x, y)
        layout.drawableHotspotChanged(x - layout.left, y - layout.top)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(if (loading) false else pressed)
        layout.isPressed = if (loading) false else pressed
    }

    override fun updateColors() {
        val active = BuiltInTunnelHelper.isActive()
        val color = if (active) getThemedColor(Theme.key_windowBackgroundWhiteGreenText)
        else getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            AndroidUtilities.dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(color, 0.09f),
            Theme.multAlpha(color, 0.1f)
        )
        textView.setTextColor(color)
        iconView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        updateLoadingColors()
    }
}
