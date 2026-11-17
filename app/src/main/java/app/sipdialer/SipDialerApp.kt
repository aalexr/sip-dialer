package app.sipdialer

import android.app.Application
import app.sipdialer.background.KeepAlive
import app.sipdialer.background.KeepAliveService
import app.sipdialer.call.CallNotifications
import app.sipdialer.sip.SipEngine
import app.sipdialer.telecom.TelecomBridge
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SipDialerApp : Application() {
    lateinit var sip: SipEngine
        private set
    lateinit var telecom: TelecomBridge
        private set
    lateinit var keepAlive: KeepAlive
        private set

    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        instance = this
        CallNotifications.createChannels(this)
        KeepAliveService.createChannel(this)
        sip = SipEngine(this).also { it.start() }
        telecom = TelecomBridge(this, sip).also { it.start() }
        keepAlive = KeepAlive(this)

        // Stay connected in the background whenever there is an account and the user wants it.
        scope.launch {
            combine(sip.account, keepAlive.enabled) { account, enabled -> account != null && enabled }
                .distinctUntilChanged()
                .collect { keepAlive.apply(it) }
        }
    }

    fun shouldKeepAlive(): Boolean = sip.account.value != null && keepAlive.enabled.value

    companion object {
        lateinit var instance: SipDialerApp
            private set
    }
}
