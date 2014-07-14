package com.keepit.abook

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule
import com.keepit.abook.model.{EContactSequencingPluginImpl, EContactSequencingPlugin, EmailAccountSequencingPluginImpl, EmailAccountSequencingPlugin}

case class ABookDbSequencingModule() extends ScalaModule {
  def configure {
    bind[EmailAccountSequencingPlugin].to[EmailAccountSequencingPluginImpl].in[AppScoped]
    bind[EContactSequencingPlugin].to[EContactSequencingPluginImpl].in[AppScoped]
  }
}
