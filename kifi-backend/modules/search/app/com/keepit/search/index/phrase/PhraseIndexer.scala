package com.keepit.search.index.phrase

import com.keepit.search.index._
import com.keepit.model.{ Collection, Phrase }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.logging.Logging
import scala.concurrent.Await
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.search.Lang
import scala.concurrent.duration._

abstract class PhraseIndexer(indexDirectory: IndexDirectory) extends Indexer[Phrase, Phrase, PhraseIndexer](indexDirectory) {
  def update(): Int
}

class PhraseIndexerImpl(
    indexDirectory: IndexDirectory,
    override val airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient) extends PhraseIndexer(indexDirectory) with Logging {

  override val commitBatchSize = 1000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    var total = 0
    var done = false
    while (!done) {
      total += doUpdate("PhraseIndex") {
        val phrases = Await.result(shoeboxClient.getPhrasesChanged(sequenceNumber, commitBatchSize), 180 seconds)
        done = phrases.isEmpty
        phrases.iterator.map(PhraseIndexable(_))
      }
    }
    total
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos("PhraseIndex" + name)
  }

  override def onFailure(indexable: Indexable[Phrase, Phrase], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  override def onSuccess(indexable: Indexable[Phrase, Phrase]): Unit = {
    if (firstInBatch < 0) firstInBatch = indexable.id.id
    countInBatch += 1
    super.onSuccess(indexable)
  }

  private[this] var firstInBatch = -1L
  private[this] var countInBatch = 0

  override def onStart(batch: Seq[Indexable[Phrase, Phrase]]): Unit = {
    firstInBatch = -1L
    countInBatch = 0
  }
}

object PhraseFields {
  val phraseField = "p"
  val decoders = Map.empty[String, FieldDecoder]
}

class PhraseIndexable(
  override val id: Id[Phrase],
  override val sequenceNumber: SequenceNumber[Phrase],
  override val isDeleted: Boolean,
  phrase: String, lang: Lang)
    extends Indexable[Phrase, Phrase] with PhraseFieldBuilder {
  import PhraseFields._
  override def buildDocument = {
    val doc = super.buildDocument
    doc.add(buildPhraseField(phraseField, phrase, lang))
    doc
  }
}

object PhraseIndexable {
  def apply(phrase: Phrase) = new PhraseIndexable(phrase.id.get, phrase.seq, !phrase.isActive, phrase.phrase, phrase.lang)
}
