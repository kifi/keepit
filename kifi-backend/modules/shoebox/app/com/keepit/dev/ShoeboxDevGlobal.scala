package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.shoebox.{ShoeboxModule, ShoeboxServices}
import com.google.inject.util.Modules
import com.keepit.module.CommonModule
import play.api.Application


object ShoeboxDevGlobal extends FortyTwoGlobal(Dev) with ShoeboxServices {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new ShoeboxModule).`with`(new DevCommonModule, new ShoeboxDevModule))

  override def onStart(app: Application) {
    startShoeboxServices()
    super.onStart(app)
  }
}