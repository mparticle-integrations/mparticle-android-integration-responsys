package com.mparticle.kits

import android.content.Context
import android.content.Intent
import com.mparticle.MPEvent
import com.mparticle.MParticle.EventType
import com.mparticle.MParticle.IdentityType
import com.mparticle.commerce.CommerceEvent
import com.mparticle.commerce.Product
import com.mparticle.identity.MParticleUser
import com.mparticle.kits.KitIntegration.*
import com.pushio.manager.PIOLogger
import com.pushio.manager.PushIOBroadcastReceiver
import com.pushio.manager.PushIOManager
import com.pushio.manager.exception.ValidationException
import com.pushio.manager.preferences.PushIOPreference
import java.math.BigDecimal

class ResponsysKit : KitIntegration(), PushListener, KitIntegration.EventListener, CommerceListener,
    IdentityListener {

    private var mPushIOManager: PushIOManager? = null

    override fun getInstance(): PushIOManager? = mPushIOManager

    override fun onKitCreate(
        settings: Map<String, String>,
        context: Context
    ): List<ReportingMessage> {
        PIOLogger.d("Responsys Kit detected")
        PIOLogger.v("RK oKC")
        val apiKey = settings["apiKey"]
        require(!KitUtils.isEmpty(apiKey)) { "Responsys API Key is empty" }
        val accountToken = settings["accountToken"]
        require(!KitUtils.isEmpty(accountToken)) { "Responsys Account Token is empty" }
        val conversionUrl = settings["conversionUrl"]
        require(!KitUtils.isEmpty(conversionUrl)) { "Responsys Conversion Url is empty" }
        val riAppId = settings["riAppId"]
        require(!KitUtils.isEmpty(riAppId)) { "Responsys RI App Id is empty" }
        val senderId = settings["senderId"]
        require(!KitUtils.isEmpty(senderId)) { "GCM/FCM Sender ID is empty" }
        mPushIOManager = PushIOManager.getInstance(context)
        checkNotNull(mPushIOManager) { "Responsys SDK initialization failed" }
        PIOLogger.v("RK oKC Configuring Kit with...")
        PIOLogger.v("RK oKC apiKey: $apiKey")
        PIOLogger.v("RK oKC accountToken: $accountToken")
        PIOLogger.v("RK oKC senderId: $senderId")
        PIOLogger.v("RK oKC conversionUrl: $conversionUrl")
        PIOLogger.v("RK oKC riAppId: $riAppId")
        mPushIOManager?.let {
            val isSDKConfigured =
                it.configure(apiKey, accountToken, senderId, conversionUrl, riAppId)
            check(isSDKConfigured) { "Responsys SDK configuration failed" }
        }
        return emptyList()
    }

    override fun getName(): String = KIT_NAME

    override fun setOptOut(optedOut: Boolean): List<ReportingMessage> = emptyList()

    override fun logLtvIncrease(
        bigDecimal: BigDecimal,
        bigDecimal1: BigDecimal,
        s: String,
        map: Map<String, String>
    ): List<ReportingMessage> = emptyList()

    override fun logEvent(commerceEvent: CommerceEvent): List<ReportingMessage>? {
        val productAction = commerceEvent.productAction
        if (KitUtils.isEmpty(productAction)) {
            return null
        }
        PIOLogger.v("RK lE cevent pA: $productAction")
        var responsysEvent: String? = null
        when (productAction) {
            Product.ADD_TO_CART -> responsysEvent = "\$AddedItemToCart"
            Product.REMOVE_FROM_CART -> responsysEvent = "\$RemovedItemFromCart"
            Product.PURCHASE -> responsysEvent = "\$PurchasedCart"
            Product.DETAIL -> responsysEvent = "\$Browsed"
            Product.CHECKOUT -> responsysEvent = "\$UpdatedStageOfCart"
        }
        if (!KitUtils.isEmpty(responsysEvent)) {
            commerceEvent.products?.let {
                for (product in it) {
                    val eventProperties = HashMap<String, Any?>()
                    eventProperties["Pid"] = product.sku
                    eventProperties["Pc"] = product.category
                    val customProperties = commerceEvent.customAttributeStrings
                    if (customProperties != null) {
                        eventProperties.putAll(customProperties)
                    }
                    mPushIOManager?.trackEvent(responsysEvent, eventProperties)
                    if (productAction.equals(Product.PURCHASE, true)) {
                        mPushIOManager?.trackEngagement(
                            PushIOManager.PUSHIO_ENGAGEMENT_METRIC_PURCHASE,
                            customProperties, null
                        )
                    }
                }
                val reportingMessages = ArrayList<ReportingMessage>()
                reportingMessages.add(ReportingMessage.fromEvent(this, commerceEvent))
                return reportingMessages
            }
        }
        return null
    }

    override fun leaveBreadcrumb(s: String): List<ReportingMessage> = emptyList()

    override fun logError(s: String, map: Map<String, String>): List<ReportingMessage> = emptyList()

    override fun logException(
        e: Exception,
        map: Map<String, String>,
        s: String
    ): List<ReportingMessage> = emptyList()

    override fun logEvent(mpEvent: MPEvent): List<ReportingMessage>? {
        val reportingMessages = processCustomFlags(mpEvent)
        val eventType = mpEvent.eventType
        PIOLogger.v("RK lE event type: $eventType")

        mPushIOManager?.let {
            when (eventType) {
                EventType.Search -> {
                    it.trackEvent("\$Searched")
                    reportingMessages.add(ReportingMessage.fromEvent(this, mpEvent))
                }
                EventType.UserPreference -> {
                    val eventInfo = mpEvent.customAttributeStrings
                    if (eventInfo != null) {
                        for ((key, value) in eventInfo) {
                            try {
                                it.declarePreference(
                                    key,
                                    key,
                                    PushIOPreference.Type.STRING
                                )
                                it.setPreference(key, value)
                            } catch (e: ValidationException) {
                                PIOLogger.v("RK lE Invalid preference: " + e.message)
                            }
                        }
                    } else {
                        return null
                    }
                }
                else -> {
                    return null
                }
            }
        }
        return if (reportingMessages.isEmpty()) null else reportingMessages
    }

    override fun logScreen(s: String, map: Map<String, String>): List<ReportingMessage> =
        emptyList()

    override fun willHandlePushMessage(intent: Intent): Boolean {
        PIOLogger.v("RK wHPM")
        return isResponsysPush(intent)
    }

    override fun onPushMessageReceived(context: Context, intent: Intent) {
        PIOLogger.v("RK oPMR")
        val newIntent = Intent(intent)
        PushIOBroadcastReceiver().onReceive(getContext(), newIntent)
    }

    override fun onPushRegistration(instanceId: String, senderId: String): Boolean {
        PIOLogger.v("RK oPR Instance ID: $instanceId, Sender ID: $senderId")
        mPushIOManager?.let {
            it.setDeviceToken(instanceId)
            it.registerApp()
        }
        return true
    }

    override fun onIdentifyCompleted(
        mParticleUser: MParticleUser,
        filteredIdentityApiRequest: FilteredIdentityApiRequest
    ) {
    }

    override fun onLoginCompleted(
        mParticleUser: MParticleUser,
        filteredIdentityApiRequest: FilteredIdentityApiRequest
    ) {
        PIOLogger.v("RK oLiC")
        registerUserId(mParticleUser)
    }

    override fun onLogoutCompleted(
        mParticleUser: MParticleUser,
        filteredIdentityApiRequest: FilteredIdentityApiRequest
    ) {
        PIOLogger.v("RK oLoC")
        mPushIOManager?.unregisterUserId()
    }

    override fun onModifyCompleted(
        mParticleUser: MParticleUser,
        filteredIdentityApiRequest: FilteredIdentityApiRequest
    ) {
    }

    override fun onUserIdentified(mParticleUser: MParticleUser) {}

    private fun registerUserId(mParticleUser: MParticleUser) {
        mPushIOManager?.let {
            val userId = getUserId(mParticleUser.userIdentities)
            if (!KitUtils.isEmpty(userId)) {
                it.registerUserId(userId)
            }
        }
    }

    private fun getUserId(identities: Map<IdentityType, String>?): String? {
        var userId: String? = null
        if (identities != null && identities.containsKey(IdentityType.CustomerId)) {
            userId = identities[IdentityType.CustomerId]
        }
        return userId
    }

    private fun isResponsysPush(intent: Intent?): Boolean {
        return intent != null && intent.hasExtra("ei") &&
                !KitUtils.isEmpty(intent.getStringExtra("ei"))
    }

    private fun processCustomFlags(mpEvent: MPEvent): MutableList<ReportingMessage> {
        val reportingMessages: MutableList<ReportingMessage> = ArrayList()
        val customFlags = mpEvent.customFlags
        if (customFlags != null) {
            if (customFlags.containsKey(CUSTOM_FLAG_IAM)) {
                mPushIOManager?.trackEvent(mpEvent.eventName)
                reportingMessages.add(ReportingMessage.fromEvent(this, mpEvent))
            }
            if (customFlags.containsKey(CUSTOM_FLAG_ENGAGEMENT)) {
                val values = customFlags[CUSTOM_FLAG_ENGAGEMENT]
                if (!values.isNullOrEmpty()) {
                    val engagementType = values[0]
                    try {
                        mPushIOManager?.trackEngagement(engagementType.toInt())
                        reportingMessages.add(ReportingMessage.fromEvent(this, mpEvent))
                    } catch (e: NumberFormatException) {
                        PIOLogger.e("Invalid engagement type")
                        PIOLogger.e(
                            "Supported engagement types can be accessed from PushIOManager and are of type: " +
                                    "PushIOManager.PUSHIO_ENGAGEMENT_METRIC_***"
                        )
                    }
                }
            }
        }
        return reportingMessages
    }

    companion object {
        const val CUSTOM_FLAG_IAM = "Responsys.Custom.iam"
        const val CUSTOM_FLAG_ENGAGEMENT = "Responsys.Custom.e"
        const val KIT_NAME = "Responsys"
    }
}
