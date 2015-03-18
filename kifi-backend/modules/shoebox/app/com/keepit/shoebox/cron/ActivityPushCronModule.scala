package com.keepit.shoebox.cron

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule

case class ActivityPushCronModule() extends ScalaModule() {

  override def configure(): Unit = {

    bind[ActivityPushSchedualer].in[AppScoped]
  }

}
