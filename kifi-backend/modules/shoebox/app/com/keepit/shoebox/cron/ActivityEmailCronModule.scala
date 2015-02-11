package com.keepit.shoebox.cron

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule

case class ActivityEmailCronModule() extends ScalaModule() {

  override def configure(): Unit = {
    bind[ActivityEmailCronPlugin].to[ActivityEmailCronPluginImpl].in[AppScoped]
  }

}
