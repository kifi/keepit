package com.keepit.search.graph

import java.io.StringReader
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.common.db._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.search.LangDetector
import com.keepit.search.index._
import com.keepit.search.index.Indexable.IteratorTokenStream
import com.keepit.search.line.LineField
import com.keepit.search.line.LineFieldBuilder
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.Some
import com.keepit.search.Lang

object URIGraphFields {
  val userField = "usr"
  val publicListField = "public_list"
  val privateListField = "private_list"
  val bookmarkIdField = "bookmark_ids"
  val uriField = "uri"
  val titleField = "title"
  val stemmedField = "title_stemmed"
  val siteField = "site"
  val siteKeywordField = "site_keywords"

  def decoders() = Map(
    userField -> DocUtil.URIListDecoder,
    publicListField -> DocUtil.URIListDecoder,
    privateListField -> DocUtil.URIListDecoder,
    titleField -> DocUtil.LineFieldDecoder,
    stemmedField -> DocUtil.LineFieldDecoder,
    siteField -> DocUtil.LineFieldDecoder,
    siteKeywordField -> DocUtil.LineFieldDecoder
  )
}

class URIGraphIndexer(
    indexDirectory: IndexDirectory,
    indexWriterConfig: IndexWriterConfig,
    val bookmarkStore: BookmarkStore,
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[User](indexDirectory, indexWriterConfig, URIGraphFields.decoders) {

  private[this] val commitBatchSize = 3000
  private[this] val fetchSize = commitBatchSize

  private[this] val updateLock = new AnyRef
  private[this] var searchers = (this.getSearcher, bookmarkStore.getSearcher)

  def getSearchers: (Searcher, Searcher) = searchers

  override def onFailure(indexable: Indexable[User], e: Throwable) {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    if (sequenceNumber.value > bookmarkStore.sequenceNumber.value) {
      log.warn(s"bookmarkStore is behind. restarting from the bookmarkStore's sequence number: ${sequenceNumber} -> ${bookmarkStore.sequenceNumber}")
      sequenceNumber = bookmarkStore.sequenceNumber
    }

    var total = 0
    var done = false
    while (!done) {
      total += update {
        val bookmarks = Await.result(shoeboxClient.getBookmarksChanged(sequenceNumber, fetchSize), 180 seconds)
        done = bookmarks.isEmpty
        bookmarks
      }
    }
    total
  }

  def update(userId: Id[User]): Int = updateLock.synchronized {
    update {
      Await.result(shoeboxClient.getBookmarks(userId), 180 seconds).filter(_.seq <= sequenceNumber)
    }
  }

  private def update(bookmarksChanged: => Seq[Bookmark]): Int = {
    log.info("updating URIGraph")
    try {
      val bookmarks = bookmarksChanged
      bookmarkStore.update(bookmarks)

      val usersChanged = bookmarks.foldLeft(Map.empty[Id[User], SequenceNumber]){ (m, b) => m + (b.userId -> b.seq) }.toSeq.sortBy(_._2)
      val cnt = successCount
      indexDocuments(usersChanged.iterator.map(buildIndexable), commitBatchSize)
      // update searchers together to get a consistent view of indexes
      searchers = (this.getSearcher, bookmarkStore.getSearcher)
      successCount - cnt
    } catch { case e: Throwable =>
      log.error("error in URIGraph update", e)
      throw e
    }
  }

  override def reindex() {
    super.reindex()
    bookmarkStore.reindex()
  }

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber)): URIGraphIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val bookmarks = bookmarkStore.getBookmarks(userId)
    new URIGraphIndexable(id = userId,
      sequenceNumber = seq,
      isDeleted = false,
      bookmarks = bookmarks)
  }

  class URIGraphIndexable(
    override val id: Id[User],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val bookmarks: Seq[Bookmark]
  ) extends Indexable[User] with LineFieldBuilder {

    override def buildDocument = {
      val doc = super.buildDocument
      val (publicBookmarks, privateBookmarks) = URIList.sortBookmarks(bookmarks)
      val publicListBytes = URIList.toByteArray(publicBookmarks)
      val privateListBytes = URIList.toByteArray(privateBookmarks)
      val publicListField = buildURIListField(URIGraphFields.publicListField, publicListBytes)
      val privateListField = buildURIListField(URIGraphFields.privateListField, privateListBytes)
      val publicList = URIList(publicListBytes)
      val privateList = URIList(privateListBytes)

      doc.add(publicListField)
      doc.add(privateListField)

      val uri = buildURIIdField(publicList)
      doc.add(uri)

      val bookmarkIds = buildBookmarkIdField(publicBookmarks.toSeq, privateBookmarks.toSeq)
      doc.add(bookmarkIds)

      val titles = buildBookmarkTitleList(publicBookmarks.toSeq, privateBookmarks.toSeq, Lang("en")) // TODO: use user's primary language to bias the detection or do the detection upon bookmark creation?

      val title = buildLineField(URIGraphFields.titleField, titles){ (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.forIndexing(lang)
        analyzer.tokenStream(fieldName, new StringReader(text))
      }
      doc.add(title)

      val titleStemmed = buildLineField(URIGraphFields.stemmedField, titles){ (fieldName, text, lang) =>
        val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
        analyzer.tokenStream(fieldName, new StringReader(text))
      }
      doc.add(titleStemmed)

      val bookmarkURLs = buildBookmarkURLList(publicBookmarks.toSeq, privateBookmarks.toSeq)

      val siteField = buildLineField(URIGraphFields.siteField, bookmarkURLs){ (fieldName, url, lang) =>
        URI.parse(url).toOption.flatMap(_.host) match {
          case Some(Host(domain @ _*)) =>
            new IteratorTokenStream((1 to domain.size).iterator, (n: Int) => domain.take(n).reverse.mkString("."))
          case _ => LineField.emptyTokenStream
        }
      }
      doc.add(siteField)

      val siteKeywordField = buildLineField(URIGraphFields.siteKeywordField, bookmarkURLs){ (fieldName, url, lang) =>
        URI.parse(url).toOption.flatMap(_.host) match {
          case Some(Host(domain @ _*)) =>
            new IteratorTokenStream((0 until domain.size).iterator, (n:Int) => domain(n))
         case _ => LineField.emptyTokenStream
        }
      }
      doc.add(siteKeywordField)

      doc
    }

    private def urlToIndexableString(url: String): Option[String] = {
      URI.parse(url).toOption.map{ u =>
        val host = u.host match {
          case Some(Host(domain @ _*)) => domain.mkString(" ")
          case _ => ""
        }
        val path = u.path.map{ p =>
          URIParserUtil.pathReservedChars.foldLeft(URIParserUtil.decodePercentEncode(p)){ (s, c) => s.replace(c.toString, " ") }
        }
        host + " " + path
      }
    }

    private def buildURIListField(field: String, uriListBytes: Array[Byte]) = {
      buildBinaryDocValuesField(field, uriListBytes)
    }

    private def buildURIIdField(uriList: URIList) = {
      buildIteratorField(URIGraphFields.uriField, uriList.ids.iterator){ uriId => uriId.toString }
    }

    private def buildBookmarkTitleList(publicBookmarks: Seq[Bookmark], privateBookmarks: Seq[Bookmark], preferedLang: Lang): ArrayBuffer[(Int, String, Lang)] = {
      var lineNo = 0
      var titles = new ArrayBuffer[(Int, String, Lang)]
      publicBookmarks.foreach{ b =>
        val text = b.title.getOrElse("")
        val lang = LangDetector.detect(text, preferedLang)
        val urlText = urlToIndexableString(b.url).getOrElse("") // piggybacking uri text on title
        titles += ((lineNo, text + " " + urlText, lang))
        lineNo += 1
      }
      privateBookmarks.foreach{ b =>
        val text = b.title.getOrElse("")
        val lang = LangDetector.detect(text, preferedLang)
        val urlText = urlToIndexableString(b.url).getOrElse("") // piggybacking uri text on title
        titles += ((lineNo, text + " " + urlText, lang))
        lineNo += 1
      }
      titles
    }

    private def buildBookmarkURLList(publicBookmarks: Seq[Bookmark], privateBookmarks: Seq[Bookmark]): ArrayBuffer[(Int, String, Lang)] = {
      val urlMap = bookmarks.foldLeft(Map.empty[Long, String]){ (m, b) => m + (b.uriId.id -> b.url) }

      var lineNo = 0
      var sites = new ArrayBuffer[(Int, String, Lang)]
      val en = LangDetector.en
      publicBookmarks.foreach{ b =>
        sites += ((lineNo, b.url, en))
        lineNo += 1
      }
      privateBookmarks.foreach{ b =>
        sites += ((lineNo, b.url, en))
        lineNo += 1
      }

      sites
    }

    private def buildBookmarkIdField(publicBookmarks: Seq[Bookmark], privateBookmarks: Seq[Bookmark]): Field = {
      val arr = (publicBookmarks.map(_.id.get.id) ++ privateBookmarks.map(_.id.get.id)).toArray
      val packedBookmarkIds = Util.packLongArray(arr)
      buildBinaryDocValuesField(URIGraphFields.bookmarkIdField, packedBookmarkIds)
    }
  }
}
