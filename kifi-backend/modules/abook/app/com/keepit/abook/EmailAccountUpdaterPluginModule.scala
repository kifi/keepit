package com.keepit.abook

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.abook.commanders.EmailAccountUpdaterPlugin

case class EmailAccountUpdaterPluginModule() extends ScalaModule {
  def configure() = {
    bind[EmailAccountUpdaterPlugin].in[AppScoped]
  }
}
