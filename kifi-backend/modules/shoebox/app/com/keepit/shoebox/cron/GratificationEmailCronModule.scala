package com.keepit.shoebox.cron

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule

case class GratificationEmailCronModule() extends ScalaModule() {

  override def configure(): Unit = {
    bind[GratificationEmailCronPlugin].to[GratificationEmailCronPluginImpl].in[AppScoped]
  }

}