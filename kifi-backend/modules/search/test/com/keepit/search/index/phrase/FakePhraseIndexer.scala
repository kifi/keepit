package com.keepit.search.index.phrase

import com.keepit.common.healthcheck.AirbrakeNotifier
import net.codingwell.scalaguice.ScalaModule
import com.keepit.search.index.VolatileIndexDirectory

class FakePhraseIndexer(override val airbrake: AirbrakeNotifier) extends PhraseIndexer(new VolatileIndexDirectory) {
  def update() = 0
  def getCommitBatchSize() = 0
}

case class FakePhraseIndexerModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[PhraseIndexer].to[FakePhraseIndexer]
  }
}

