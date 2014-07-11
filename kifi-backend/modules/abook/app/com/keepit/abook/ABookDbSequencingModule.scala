package com.keepit.abook

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule
import com.keepit.abook.model.{ EmailAccountSequencingPluginImpl, EmailAccountSequencingPlugin }

case class ABookDbSequencingModule() extends ScalaModule {
  def configure {
    bind[EmailAccountSequencingPlugin].to[EmailAccountSequencingPluginImpl].in[AppScoped]
  }
}
