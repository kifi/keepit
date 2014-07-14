package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.google.inject.util.Modules
import play.api.Application
import com.keepit.maven.MavenServices

object MavenDevGlobal extends FortyTwoGlobal(Dev) with MavenServices {
  override val module = MavenDevModule()

  override def onStart(app: Application) {
    startMavenServices()
    super.onStart(app)
  }
}
