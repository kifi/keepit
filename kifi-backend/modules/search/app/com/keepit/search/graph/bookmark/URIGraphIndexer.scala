package com.keepit.search.graph.bookmark

import java.io.StringReader
import org.apache.lucene.document.Field
import com.keepit.common.db._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.net._
import com.keepit.common.strings._
import com.keepit.model._
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.Searcher
import com.keepit.search.index._
import com.keepit.search.index.Indexable.IteratorTokenStream
import com.keepit.search.line.LineField
import com.keepit.search.line.LineFieldBuilder
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Success, Try }
import scala.math._
import com.keepit.search.graph.URIList
import com.keepit.search.graph.Util
import com.keepit.search.IndexInfo
import com.keepit.search.sharding.Shard
import com.keepit.search.graph.SortedBookmarks
import com.keepit.common.logging.Logging

object URIGraphFields {
  val userField = "usr"
  val publicListField = "public_list"
  val privateListField = "private_list"
  val bookmarkIdField = "bookmark_ids"
  val uriField = "uri"
  val titleField = "title"
  val stemmedField = "title_stemmed"
  val siteField = "site"
  val homePageField = "home_page"
  val langProfField = "lang_prof"

  def decoders() = Map(
    userField -> DocUtil.URIListDecoder,
    publicListField -> DocUtil.URIListDecoder,
    privateListField -> DocUtil.URIListDecoder,
    titleField -> DocUtil.LineFieldDecoder,
    stemmedField -> DocUtil.LineFieldDecoder,
    siteField -> DocUtil.LineFieldDecoder
  )
}

class URIGraphIndexer(
  indexDirectory: IndexDirectory,
  val bookmarkStore: BookmarkStore,
  val airbrake: AirbrakeNotifier)
    extends Indexer[User, Keep, URIGraphIndexer](indexDirectory, URIGraphFields.decoders) {

  import URIGraphIndexer.URIGraphIndexable

  override val commitBatchSize = 500
  private val fetchSize = commitBatchSize

  override def initialSequenceNumberValue: Long = {
    val uriGraphSeq = commitData.getOrElse(Indexer.CommitData.sequenceNumber, "-1").toLong
    val bookmarkStoreSeq = bookmarkStore.initialSequenceNumberValue
    if (uriGraphSeq > bookmarkStoreSeq) {
      log.warn(s"bookmarkStore is behind. restarting from the bookmarkStore's sequence number: ${uriGraphSeq} -> ${bookmarkStoreSeq}")
      bookmarkStoreSeq
    } else {
      uriGraphSeq
    }
  }

  private[this] var searchers = (this.getSearcher, bookmarkStore.getSearcher)

  def getSearchers: (Searcher, Searcher) = searchers

  override def onFailure(indexable: Indexable[User, Keep], e: Throwable) {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = throw new UnsupportedOperationException()

  def update(name: String, bookmarks: Seq[Keep], shard: Shard[NormalizedURI]): Int = updateLock.synchronized {
    val cnt = doUpdate("URIGraphIndex" + name) {
      bookmarkStore.update(name, bookmarks, shard)

      val usersChanged = bookmarks.foldLeft(Map.empty[Id[User], SequenceNumber[Keep]]) { (m, b) => m + (b.userId -> b.seq) }.toSeq.sortBy(_._2)
      usersChanged.iterator.map(buildIndexable)
    }
    // update searchers together to get a consistent view of indexes
    searchers = (this.getSearcher, bookmarkStore.getSearcher)
    cnt
  }

  override def close() {
    super.close()
    bookmarkStore.close()
  }

  override def reindex() {
    super.reindex()
    bookmarkStore.reindex()
  }

  override def backup() {
    super.backup()
    bookmarkStore.backup()
  }

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber[Keep])): URIGraphIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val bookmarks = bookmarkStore.getBookmarks(userId)
    new URIGraphIndexable(id = userId,
      sequenceNumber = seq,
      isDeleted = bookmarks.isEmpty,
      bookmarks = bookmarks)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos("URIGraphIndex" + name) ++ bookmarkStore.indexInfos("BookmarkStore" + name)
  }
}

object URIGraphIndexer {

  class URIGraphIndexable(
      override val id: Id[User],
      override val sequenceNumber: SequenceNumber[Keep],
      override val isDeleted: Boolean,
      val bookmarks: Seq[Keep]) extends Indexable[User, Keep] with LineFieldBuilder with Logging {

    override def buildDocument = {
      val doc = super.buildDocument
      val (publicBookmarks, privateBookmarks) = URIList.sortBookmarks(bookmarks)

      val publicListBytes = URIList.toByteArray(publicBookmarks)
      val privateListBytes = URIList.toByteArray(privateBookmarks)

      val publicListFields = buildURIListField(URIGraphFields.publicListField, publicListBytes)
      val privateListFields = buildURIListField(URIGraphFields.privateListField, privateListBytes)
      val publicList = URIList(publicListBytes)
      val privateList = URIList(privateListBytes)

      publicListFields.foreach { doc.add }
      privateListFields.foreach { doc.add }

      val uri = buildURIIdField(publicList)
      doc.add(uri)

      val bookmarkIdsFields = buildBookmarkIdField(publicBookmarks.toSeq, privateBookmarks.toSeq)
      bookmarkIdsFields.foreach { doc.add }

      val titles = buildBookmarkTitleList(publicBookmarks.toSeq, privateBookmarks.toSeq, DefaultAnalyzer.defaultLang) // TODO: use user's primary language to bias the detection or do the detection upon bookmark creation?

      val title = buildLineField(URIGraphFields.titleField, titles) { (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.getAnalyzer(lang)
        analyzer.tokenStream(fieldName, new StringReader(text))
      }
      doc.add(title)

      val titleStemmed = buildLineField(URIGraphFields.stemmedField, titles) { (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
        analyzer.tokenStream(fieldName, new StringReader(text))
      }
      doc.add(titleStemmed)

      val bookmarkURLs = buildBookmarkURLList(publicBookmarks.toSeq, privateBookmarks.toSeq)

      val siteField = buildLineField(URIGraphFields.siteField, bookmarkURLs) { (fieldName, uri, lang) =>
        uri.toOption.flatMap(_.host) match {
          case Some(Host(domain @ _*)) =>
            new IteratorTokenStream((1 to domain.size).iterator, (n: Int) => domain.take(n).reverse.mkString("."))
          case _ => LineField.emptyTokenStream
        }
      }
      doc.add(siteField)

      val hostNameAnalyzer = DefaultAnalyzer.defaultAnalyzer
      val homePageField = buildLineField(URIGraphFields.homePageField, bookmarkURLs) { (fieldName, uri, lang) =>
        uri match {
          case Success(URI(_, _, Some(Host(domain @ _*)), _, path, None, None)) if (!path.isDefined || path == Some("/")) =>
            hostNameAnalyzer.tokenStream(fieldName, new StringReader(domain.reverse.mkString(" ")))
          case _ => LineField.emptyTokenStream
        }
      }
      doc.add(homePageField)

      doc.add(buildLangProfileField(titles.filter(_._2.length > 16).map(_._3))) // take langs of titles longer than 16 chars

      doc
    }

    private def buildURIListField(field: String, uriListBytes: Array[Byte]) = {
      buildExtraLongBinaryDocValuesField(field, uriListBytes)
    }

    private def buildURIIdField(uriList: URIList) = {
      buildIteratorField(URIGraphFields.uriField, uriList.ids.iterator) { uriId => uriId.toString }
    }

    private def buildBookmarkTitleList(publicBookmarks: Seq[Keep], privateBookmarks: Seq[Keep], preferedLang: Lang): ArrayBuffer[(Int, String, Lang)] = {
      var lineNo = 0
      var titles = new ArrayBuffer[(Int, String, Lang)]
      publicBookmarks.foreach { b =>
        val text = b.title.getOrElse("")
        val lang = LangDetector.detect(text, preferedLang)
        val urlText = urlToIndexableString(b.url).getOrElse("") // piggybacking uri text on title
        titles += ((lineNo, text + " " + urlText, lang))
        lineNo += 1
      }
      privateBookmarks.foreach { b =>
        val text = b.title.getOrElse("")
        val lang = LangDetector.detect(text, preferedLang)
        val urlText = urlToIndexableString(b.url).getOrElse("") // piggybacking uri text on title
        titles += ((lineNo, text + " " + urlText, lang))
        lineNo += 1
      }
      titles
    }

    private def buildBookmarkURLList(publicBookmarks: Seq[Keep], privateBookmarks: Seq[Keep]): ArrayBuffer[(Int, Try[URI], Lang)] = {
      val urlMap = bookmarks.foldLeft(Map.empty[Long, String]) { (m, b) => m + (b.uriId.id -> b.url) }

      var lineNo = 0
      var sites = new ArrayBuffer[(Int, Try[URI], Lang)]
      val en = LangDetector.en
      publicBookmarks.foreach { b =>
        sites += ((lineNo, URI.parse(b.url), en))
        lineNo += 1
      }
      privateBookmarks.foreach { b =>
        sites += ((lineNo, URI.parse(b.url), en))
        lineNo += 1
      }

      sites
    }

    private def buildBookmarkIdField(publicBookmarks: Seq[Keep], privateBookmarks: Seq[Keep]): Seq[Field] = {
      val arr = (publicBookmarks.map(_.id.get.id) ++ privateBookmarks.map(_.id.get.id)).toArray
      val packedBookmarkIds = Util.packLongArray(arr)
      buildExtraLongBinaryDocValuesField(URIGraphFields.bookmarkIdField, packedBookmarkIds)
    }

    private def buildLangProfileField(langs: Seq[Lang]): Field = {
      val langFreq = langs.foldLeft(Map[Lang, Float]()) { (m, lang) => m + (lang -> (m.getOrElse(lang, 0.0f) + 1.0f)) }
      val threshold = langs.size.toFloat * 0.05f // 5%
      val profile = langFreq.filter { case (_, freq) => freq > threshold }.toSeq.sortBy(p => -p._2).take(8).map(p => s"${p._1.lang}:${p._2.toInt}").mkString(",")
      buildBinaryDocValuesField(URIGraphFields.langProfField, profile.getBytes(UTF8))
    }
  }
}
