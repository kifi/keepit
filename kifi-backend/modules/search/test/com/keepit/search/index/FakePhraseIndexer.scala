package com.keepit.search.index

import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version

import net.codingwell.scalaguice.ScalaModule

import com.keepit.common.db.Id
import com.keepit.model.Phrase
import com.keepit.search.phrasedetector.{PhraseIndexable, PhraseIndexer}

class FakePhraseIndexer extends PhraseIndexer(new VolatileIndexDirectoryImpl, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)) {
  def update() = 0
  def getCommitBatchSize() = 0
}

case class FakePhraseIndexerModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[PhraseIndexer].to[FakePhraseIndexer]
  }
}

