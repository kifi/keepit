package com.keepit.search.graph.collection

import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.search.LangDetector
import com.keepit.search.index._
import com.keepit.search.line.LineFieldBuilder
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
    override val airbrake: AirbrakeNotifier)
  extends Indexer[User, Collection, CollectionNameIndexer](indexDirectory, CollectionNameFields.decoders) {

  override val commitBatchSize = 100
  private val fetchSize = commitBatchSize

  override def onFailure(indexable: Indexable[User, Collection], e: Throwable) {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = throw new UnsupportedOperationException("CollectionNameIndex should not be updated by update()")

  def update(name: String, collectionsChanged: Seq[Collection], collectionSearcher: CollectionSearcher): Int = updateLock.synchronized {
    val usersChanged = collectionsChanged.foldLeft(Map.empty[Id[User], SequenceNumber[Collection]]){ (m, c) => m + (c.userId -> c.seq) }.toSeq.sortBy(_._2)
    doUpdate("CollectionNameIndex" + name) {
      usersChanged.iterator.map(buildIndexable(_, collectionSearcher))
    }
  }

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber[Collection]), collectionSearcher: CollectionSearcher): CollectionNameIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val collections = collectionSearcher.getCollections(userId)
    new CollectionNameIndexable(id = userId,
      sequenceNumber = seq,
      isDeleted = collections.isEmpty,
      collections = collections)
  }

  class CollectionNameIndexable(
    override val id: Id[User],
    override val sequenceNumber: SequenceNumber[Collection],
    override val isDeleted: Boolean,
    val collections: Seq[(Id[Collection], String)]
  ) extends Indexable[User, Collection] with LineFieldBuilder {

    override def buildDocument = {
      val doc = super.buildDocument
      val sortedCollections = CollectionIdList.sortCollections(collections)
      val collectionIds = CollectionIdList.toByteArray(sortedCollections)
      val collectionIdField = buildBinaryDocValuesField(CollectionNameFields.collectionIdListField, collectionIds)

      doc.add(collectionIdField)

      val nameList = buildNameList(sortedCollections.toSeq, Lang("en")) // TODO: use user's primary language to bias the detection or do the detection upon bookmark creation?

      val names = buildLineField(CollectionNameFields.nameField, nameList){ (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.getAnalyzer(lang)
        new PhraseTokenStream(fieldName, text, analyzer, removeSingleTerms = false)
      }
      doc.add(names)

      val stemmedNames = buildLineField(CollectionNameFields.stemmedNameField, nameList){ (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
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
