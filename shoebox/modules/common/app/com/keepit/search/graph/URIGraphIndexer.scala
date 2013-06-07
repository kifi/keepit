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
import com.keepit.common.db.slick._
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder
import com.keepit.search.index.{DefaultAnalyzer, Indexable, Indexer, IndexError}
import com.keepit.search.index.Indexable.IteratorTokenStream
import com.keepit.search.line.LineField
import com.keepit.search.line.LineFieldBuilder
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

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
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    bookmarkStore: BookmarkStore,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[User](indexDirectory, indexWriterConfig, URIGraphFields.decoders) {

  private[this] val commitBatchSize = 3000
  private[this] val fetchSize = commitBatchSize

  private def commitCallback(commitBatch: Seq[(Indexable[User], Option[IndexError])]) = {
    var cnt = 0
    commitBatch.foreach{ case (indexable, indexError) =>
      indexError match {
        case Some(error) =>
          log.error("indexing failed for user=%s error=%s".format(indexable.id, error.msg))
        case None =>
          cnt += 1
      }
    }
    cnt
  }

  def update(): Int = {
    resetSequenceNumberIfReindex()
    update {
      Await.result(shoeboxClient.getBookmarksChanged(sequenceNumber, fetchSize), 180 seconds)
    }
  }

  def update(userId: Id[User]): Int = {
    update {
      Await.result(shoeboxClient.getBookmarks(userId), 180 seconds)
    }
  }

  private def update(bookmarksChanged: => Seq[Bookmark]): Int = {
    log.info("updating URIGraph")
    try {
      val bookmarks = bookmarksChanged
      bookmarkStore.update(bookmarks)

      val usersChanged = bookmarks.foldLeft(Map.empty[Id[User], SequenceNumber]){ (m, b) => m + (b.userId -> b.seq) }.toSeq.sortBy(_._2)
      var cnt = 0
      indexDocuments(usersChanged.iterator.map(buildIndexable), commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
      cnt
    } catch { case e: Throwable =>
      log.error("error in URIGraph update", e)
      throw e
    }
  }

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber)): URIListIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val bookmarks = bookmarkStore.getBookmarks(userId)
    new URIListIndexable(id = userId,
                         sequenceNumber = seq,
                         isDeleted = false,
                         bookmarks = bookmarks)
  }

  class URIListIndexable(
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

    private def buildURIListField(field: String, uriListBytes: Array[Byte]) = {
      buildBinaryDocValuesField(field, uriListBytes)
    }

    private def buildURIIdField(uriList: URIList) = {
      buildIteratorField(URIGraphFields.uriField, uriList.ids.iterator){ uriId => uriId.toString }
    }

    private def buildBookmarkTitleList(publicBookmarks: Seq[Bookmark], privateBookmarks: Seq[Bookmark], preferedLang: Lang): ArrayBuffer[(Int, String, Lang)] = {
      val titleMap = bookmarks.foldLeft(Map.empty[Long, (String, Lang)]){ (m, b) =>
        val text = b.title.getOrElse("")
        m + (b.uriId.id -> (text, LangDetector.detect(text, preferedLang)))
      }

      var lineNo = 0
      var titles = new ArrayBuffer[(Int, String, Lang)]
      publicBookmarks.foreach{ b =>
        val text = b.title.getOrElse("")
        val lang = LangDetector.detect(text, preferedLang)
        titles += ((lineNo, text, lang))
        lineNo += 1
      }
      privateBookmarks.foreach{ b =>
        val text = b.title.getOrElse("")
        val lang = LangDetector.detect(text, preferedLang)
        titles += ((lineNo, text, lang))
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
      val packedBookmarkIds = URIList.packLongArray(arr)
      buildBinaryDocValuesField(URIGraphFields.bookmarkIdField, packedBookmarkIds)
    }
  }
}

class URIGraphUnsupportedVersionException(msg: String) extends Exception(msg)

