package com.keepit.search.phrasedetector

import com.keepit.search.index.{ArchivedDirectory, Indexable, Indexer, IndexDirectory}
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.model.{Collection, Phrase}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.logging.Logging
import scala.concurrent.Await
import scala.slick.util.CloseableIterator
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.search.Lang
import scala.concurrent.duration._

abstract class PhraseIndexer(indexDirectory: IndexDirectory, indexWriterConfig: IndexWriterConfig) extends Indexer[Phrase](indexDirectory, indexWriterConfig) {
  def update(): Int
}

class PhraseIndexerImpl(
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient) extends PhraseIndexer(indexDirectory, indexWriterConfig) with Logging  {

  final val commitBatchSize = 1000
  private[this] val updateLock = new AnyRef

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    log.info("updating Phrases")
    var total = 0
    var done = false
    while (!done) {
      total += update {
        val phrases = Await.result(shoeboxClient.getPhrasesChanged(sequenceNumber, commitBatchSize), 180 seconds)
        done = phrases.isEmpty
        phrases
      }
    }
    total
  }

  private def update(phrasesChanged: => Seq[Phrase]): Int = {
    try {
      val cnt = successCount
      val changed = phrasesChanged
      indexDocuments(changed.iterator.map(PhraseIndexable(_)), commitBatchSize)
      successCount - cnt
    } catch { case e: Throwable =>
      log.error("error in Phrase update", e)
      throw e
    }
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
}


class PhraseIndexable(
  override val id: Id[Phrase],
  override val sequenceNumber: SequenceNumber,
  override val isDeleted: Boolean,
  phrase: String, lang: Lang)
extends Indexable[Phrase] with PhraseFieldBuilder {
  override def buildDocument = {
    val doc = super.buildDocument
    doc.add(buildPhraseField("p", phrase, lang))
    doc
  }
}

object PhraseIndexable {
  def apply(phrase: Phrase) = new PhraseIndexable(phrase.id.get, phrase.seq, !phrase.isActive, phrase.phrase, phrase.lang)
}