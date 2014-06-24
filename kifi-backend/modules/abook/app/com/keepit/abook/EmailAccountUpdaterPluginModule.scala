package com.keepit.abook

import net.codingwell.scalaguice.ScalaModule
import com.keepit.commanders.EmailAccountUpdaterPlugin
import com.keepit.inject.AppScoped

case class EmailAccountUpdaterPluginModule() extends ScalaModule {
  def configure() = {
    bind[EmailAccountUpdaterPlugin].in[AppScoped]
  }
}
