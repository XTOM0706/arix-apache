package com.arix.app

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** 会话工厂：系统请求助手会话时创建 [XtomVoiceSession]。 */
class XtomVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = XtomVoiceSession(this)
}
