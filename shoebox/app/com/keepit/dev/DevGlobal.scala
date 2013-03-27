package com.keepit.dev

import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.inject._
import com.keepit.module.CommonModule
import com.keepit.search.{SearchGlobal, SearchModule}
import com.keepit.shoebox.{ShoeboxGlobal, ShoeboxModule}
import play.api.Application
import play.api.Mode._
import play.api.Play.current

object DevGlobal extends FortyTwoGlobal(Dev) {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new ShoeboxModule, new SearchModule).`with`(new DevModule))

  override def onStart(app: Application) {
    require(inject[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    super.onStart(app)
    ShoeboxGlobal.startServices()
    SearchGlobal.startServices()
  }
}

object SearchDevGlobal extends FortyTwoGlobal(Dev) {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new SearchModule).`with`(new DevCommonModule, new SearchDevModule))

  override def onStart(app: Application) {
    super.onStart(app)
    SearchGlobal.startServices()
  }
}

object ShoeboxDevGlobal extends FortyTwoGlobal(Dev) {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new ShoeboxModule).`with`(new DevCommonModule, new ShoeboxDevModule))

  override def onStart(app: Application) {
    super.onStart(app)
    ShoeboxGlobal.startServices()
  }
}
