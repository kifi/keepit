package com.keepit.search.index

import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version

import net.codingwell.scalaguice.ScalaModule

import com.keepit.common.db.Id
import com.keepit.model.Phrase
import com.keepit.search.phrasedetector.{PhraseIndexable, PhraseIndexer}

class FakePhraseIndexer extends PhraseIndexer(new RAMDirectory, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)) {
  def reload() = {}
  def reload(indexableIterator: Iterator[PhraseIndexable], refresh: Boolean = true) = {}
  def buildIndexable(data: Phrase): Indexable[Phrase] = throw new UnsupportedOperationException
  def buildIndexable(id: Id[Phrase]): Indexable[Phrase] = throw new UnsupportedOperationException
}

case class FakePhraseIndexerModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[PhraseIndexer].to[FakePhraseIndexer]
  }
}

