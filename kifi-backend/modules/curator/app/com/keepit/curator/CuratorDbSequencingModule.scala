package com.keepit.curator

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule
import com.keepit.curator.model.{ CuratorLibraryInfoSequencingPluginImpl, CuratorLibraryInfoSequencingPlugin }

case class CuratorDbSequencingModule() extends ScalaModule {
  def configure {
    bind[CuratorLibraryInfoSequencingPlugin].to[CuratorLibraryInfoSequencingPluginImpl].in[AppScoped]
  }
}
