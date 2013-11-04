package com.keepit.search.phrasedetector

import com.keepit.search.index.{Indexable, Indexer, IndexDirectory}
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.model.Phrase
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.logging.Logging
import scala.concurrent.Await
import scala.slick.util.CloseableIterator
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.search.Lang
import scala.concurrent.duration._

abstract class PhraseIndexer(indexDirectory: IndexDirectory, indexWriterConfig: IndexWriterConfig) extends Indexer[Phrase](indexDirectory, indexWriterConfig) {
  def reload(): Unit
  def reload(indexableIterator: Iterator[PhraseIndexable], refresh: Boolean = true): Unit
}

class PhraseIndexerImpl(
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient) extends PhraseIndexer(indexDirectory, indexWriterConfig) with Logging  {

  final val BATCH_SIZE = 200000

  def reload() {
    log.info("[PhraseIndexer] reloading phrase index")
    val indexableIterator = new Iterator[PhraseIndexable] {
      var cache = collection.mutable.Queue[PhraseIndexable]()
      var page = 0
      private def update() = {
        if(cache.size <= 1) {
          cache = cache ++ Await.result(shoeboxClient.getPhrasesByPage(page, BATCH_SIZE), 5 seconds).map(p => new PhraseIndexable(p.id.get, p.phrase, p.lang))
          page += 1
        }
      }

      def next() = {
        update()
        cache.dequeue
      }
      def hasNext() = {
        update()
        cache.size > 0
      }
    }
    //val indexableIterator = phraseRepo.allIterator.map(p => new PhraseIndexable(p.id.get, p.phrase, p.lang))
    log.info("[PhraseIndexer] Iterator created")
    reload(indexableIterator, refresh = false)
    log.info("[PhraseIndexer] refreshing searcher")
    refreshSearcher()
    log.info("[PhraseIndexer] phrase import complete")
  }

  def reloadWithCloseableIterator(indexableIterator: CloseableIterator[PhraseIndexable], refresh: Boolean) {
    try { reload(indexableIterator, refresh) }
    finally { indexableIterator.close }
  }

  def reload(indexableIterator: Iterator[PhraseIndexable], refresh: Boolean = true) {
    deleteAllDocuments(refresh = false)
    indexDocuments(indexableIterator, BATCH_SIZE, refresh = false)
    if (refresh) refreshSearcher()
  }

  override def onFailure(indexable: Indexable[Phrase], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  override def onSuccess(indexable: Indexable[Phrase]): Unit = {
    if (firstInBatch < 0) firstInBatch = indexable.id.id
    countInBatch += 1
    super.onSuccess(indexable)
  }

  private[this] var firstInBatch = -1L
  private[this] var countInBatch = 0

  override def onStart(batch: Seq[Indexable[Phrase]]): Unit = {
    firstInBatch = -1L
    countInBatch = 0
  }

  override def onCommit(successful: Seq[Indexable[Phrase]]): Unit = {
    log.info(s"[PhraseIndexer] imported ${countInBatch} phrases. First in batch id: ${firstInBatch}")
  }
}


class PhraseIndexable(override val id: Id[Phrase], phrase: String, lang: Lang) extends Indexable[Phrase] with PhraseFieldBuilder {
  override val sequenceNumber = SequenceNumber.ZERO
  override val isDeleted = false
  override def buildDocument = {
    val doc = super.buildDocument
    doc.add(buildPhraseField("p", phrase, lang))
    doc
  }
}