package com.keepit.search.graph

import java.io.StringReader
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.Version
import com.keepit.common.db._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder
import com.keepit.search.index.{DefaultAnalyzer, Indexable, Indexer}
import com.keepit.search.index.Indexable.IteratorTokenStream
import com.keepit.search.index.Searcher
import com.keepit.search.line.LineField
import com.keepit.search.line.LineFieldBuilder
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.search.phrasedetector.PhraseTokenStream

object CollectionNameFields {
  val nameField = "cn_name"
  val stemmedNameField = "cn_stemmed"
  val collectionIdListField = "cn_ids"

  def decoders() = Map(
    nameField -> DocUtil.LineFieldDecoder,
    stemmedNameField -> DocUtil.LineFieldDecoder,
    collectionIdListField -> DocUtil.CollectionIdListDecoder
  )
}

class CollectionNameIndexer(
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[User](indexDirectory, indexWriterConfig, CollectionNameFields.decoders) {

  private[this] val commitBatchSize = 3000
  private[this] val fetchSize = commitBatchSize

  private[this] val updateLock = new AnyRef

  override def onFailure(indexable: Indexable[User], e: Throwable) {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(AirbrakeError(message = Some(msg)))
    super.onFailure(indexable, e)
  }

  def update(collectionsChanged: Seq[Collection], collectionSearcher: CollectionSearcher): Int = updateLock.synchronized {
    log.info("updating CollectionNameIndex")
    try {
      val usersChanged = collectionsChanged.foldLeft(Map.empty[Id[User], SequenceNumber]){ (m, c) => m + (c.userId -> c.seq) }.toSeq.sortBy(_._2)

      val cnt = successCount
      indexDocuments(usersChanged.iterator.map(buildIndexable(_, collectionSearcher)), commitBatchSize)
      successCount - cnt
    } catch { case e: Throwable =>
      log.error("error in CollectionNameIndex update", e)
      throw e
    }
  }

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber), collectionSearcher: CollectionSearcher): CollectionNameIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val collections = collectionSearcher.getCollections(userId)
    new CollectionNameIndexable(id = userId,
      sequenceNumber = seq,
      isDeleted = false,
      collections = collections)
  }

  class CollectionNameIndexable(
    override val id: Id[User],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val collections: Seq[(Id[Collection], String)]
  ) extends Indexable[User] with LineFieldBuilder {

    override def buildDocument = {
      val doc = super.buildDocument
      val sortedCollections = CollectionIdList.sortCollections(collections)
      val collectionIds = CollectionIdList.toByteArray(sortedCollections)
      val collectionIdField = buildBinaryDocValuesField(CollectionNameFields.collectionIdListField, collectionIds)

      doc.add(collectionIdField)

      val nameList = buildNameList(sortedCollections.toSeq, Lang("en")) // TODO: use user's primary language to bias the detection or do the detection upon bookmark creation?

      val names = buildLineField(CollectionNameFields.nameField, nameList){ (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.forIndexing(lang)
        new PhraseTokenStream(fieldName, text, analyzer, removeSingleTerms = false)
      }
      doc.add(names)

      val stemmedNames = buildLineField(CollectionNameFields.stemmedNameField, nameList){ (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
        new PhraseTokenStream(fieldName, text, analyzer, removeSingleTerms = false)
      }
      doc.add(stemmedNames)

      doc
    }

    private def buildNameList(collections: Seq[(Id[Collection], String)], preferedLang: Lang): ArrayBuffer[(Int, String, Lang)] = {
      var lineNo = 0
      var names = new ArrayBuffer[(Int, String, Lang)]
      collections.foreach{ c =>
        val name = c._2
        val lang = LangDetector.detect(name, preferedLang)
        names += ((lineNo, name, lang))
        lineNo += 1
      }
      names
    }
  }
}
