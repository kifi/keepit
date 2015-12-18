package com.keepit.eliza

import com.keepit.eliza.model.{ MessageSequencingPluginImpl, MessageSequencingPlugin }
import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule

case class ElizaDbSequencingModule() extends ScalaModule {
  def configure {
    bind[MessageSequencingPlugin].to[MessageSequencingPluginImpl].in[AppScoped]
  }
}
