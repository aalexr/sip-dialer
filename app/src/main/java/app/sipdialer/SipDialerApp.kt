package app.sipdialer

import android.app.Application
import app.sipdialer.call.CallNotifications
import app.sipdialer.sip.SipEngine
import app.sipdialer.telecom.TelecomBridge

class SipDialerApp : Application() {

    lateinit var sip: SipEngine
        private set
    lateinit var telecom: TelecomBridge
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CallNotifications.createChannels(this)
        sip = SipEngine(this).also { it.start() }
        telecom = TelecomBridge(this, sip).also { it.start() }
    }

    companion object {
        lateinit var instance: SipDialerApp
            private set
    }
}
