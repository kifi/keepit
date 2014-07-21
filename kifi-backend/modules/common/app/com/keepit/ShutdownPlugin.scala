package com.keepit

import play.api.{ Plugin, Application }

class ShutdownPlugin(app: Application) extends Plugin {
  override def onStop() {
    try {
      app.global.asInstanceOf[FortyTwoGlobal].announceStopping(app)
    } catch {
      case t: Throwable => t.printStackTrace()
    }
  }
}
