package com.keepit.search.graph.collection

import org.apache.lucene.index.IndexWriterConfig
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.search.LangDetector
import com.keepit.search.index._
import com.keepit.search.line.LineFieldBuilder
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.phrasedetector.PhraseTokenStream
import com.keepit.search.Lang

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
    indexDirectory: IndexDirectory,
    indexWriterConfig: IndexWriterConfig,
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[User](indexDirectory, indexWriterConfig, CollectionNameFields.decoders) {

  override val commitBatchSize = 100
  private val fetchSize = commitBatchSize

  override def onFailure(indexable: Indexable[User], e: Throwable) {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = throw new UnsupportedOperationException("CollectionNameIndex should not be updated by update()")

  def update(collectionsChanged: Seq[Collection], collectionSearcher: CollectionSearcher): Int = updateLock.synchronized {
    val usersChanged = collectionsChanged.foldLeft(Map.empty[Id[User], SequenceNumber]){ (m, c) => m + (c.userId -> c.seq) }.toSeq.sortBy(_._2)
    doUpdate("CollectionNameIndex") {
      usersChanged.iterator.map(buildIndexable(_, collectionSearcher))
    }
  }

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber), collectionSearcher: CollectionSearcher): CollectionNameIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val collections = collectionSearcher.getCollections(userId)
    new CollectionNameIndexable(id = userId,
      sequenceNumber = seq,
      isDeleted = collections.isEmpty,
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
