package com.keepit.search.phrasedetector

import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.db.Id
import com.keepit.model.Phrase
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.VolatileIndexDirectoryImpl

class FakePhraseIndexer extends PhraseIndexer(new VolatileIndexDirectoryImpl,
    new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.defaultAnalyzer)) {
  def update() = 0
  def getCommitBatchSize() = 0
}

case class FakePhraseIndexerModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[PhraseIndexer].to[FakePhraseIndexer]
  }
}

