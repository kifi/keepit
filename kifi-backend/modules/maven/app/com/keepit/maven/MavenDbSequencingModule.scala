package com.keepit.maven

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule
import com.keepit.maven.model.{RawSeedItemSequencingPluginImpl, RawSeedItemSequencingPlugin}

case class MavenDbSequencingModule() extends ScalaModule {
  def configure {
    bind[RawSeedItemSequencingPlugin].to[RawSeedItemSequencingPluginImpl].in[AppScoped]
  }
}
