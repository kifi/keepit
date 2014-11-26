package com.keepit.curator

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule
import com.keepit.curator.model.{ CuratorLibraryInfoSequencingPluginImpl, CuratorLibraryInfoSequencingPlugin, RawSeedItemSequencingPluginImpl, RawSeedItemSequencingPlugin }

case class CuratorDbSequencingModule() extends ScalaModule {
  def configure {
    bind[RawSeedItemSequencingPlugin].to[RawSeedItemSequencingPluginImpl].in[AppScoped]
    bind[CuratorLibraryInfoSequencingPlugin].to[CuratorLibraryInfoSequencingPluginImpl].in[AppScoped]
  }
}
