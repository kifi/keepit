package com.keepit.search.graph

import scala.collection.mutable.ArrayBuffer
import java.io.StringReader
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.BinaryDocValuesField
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

object URIGraphFields {
  val userField = "usr"
  val publicListField = "public_list"
  val privateListField = "private_list"
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

trait URIGraph {
  def update(): Int
  def getURIGraphSearcher(userId: Option[Id[User]] = None): URIGraphSearcher
  def close(): Unit

  private[search] def sequenceNumber: SequenceNumber
  private[search] def sequenceNumber_=(n: SequenceNumber)
}

class URIGraphImpl(
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    decoders: Map[String, FieldDecoder],
    bookmarkRepo: BookmarkRepo,
    db: Database)
  extends Indexer[User](indexDirectory, indexWriterConfig, decoders) with URIGraph {

  val commitBatchSize = 100
  val fetchSize = commitBatchSize * 3

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
    log.info("updating URIGraph")
    try {
      var cnt = 0
      val usersChanged = db.readOnly { implicit s =>
        bookmarkRepo.getUsersChanged(sequenceNumber)
      }
      indexDocuments(usersChanged.iterator.map(buildIndexable), commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
      cnt
    } catch { case e: Throwable =>
      log.error("error in URIGraph update", e)
      throw e
    }
  }

  def getURIGraphSearcher(userId: Option[Id[User]]) = new URIGraphSearcher(searcher, userId)

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber)): URIListIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val bookmarks = db.readOnly { implicit session =>
      bookmarkRepo.getByUser(userId)
    }
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
      val (publicListBytes, privateListBytes) = URIList.toByteArrays(bookmarks)
      val publicListField = buildURIListField(URIGraphFields.publicListField, publicListBytes)
      val privateListField = buildURIListField(URIGraphFields.privateListField, privateListBytes)
      val uri = buildURIIdField(bookmarks)
      doc.add(publicListField)
      doc.add(privateListField)
      doc.add(uri)

      val publicList = URIList(publicListBytes)
      val privateList = URIList(privateListBytes)
      val titles = buildBookmarkTitleList(publicList.ids, privateList.ids, bookmarks, Lang("en")) // TODO: use user's primary language to bias the detection or do the detection upon bookmark creation?

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

      val bookmarkURLs = buildBookmarkURLList(publicList.ids, privateList.ids, bookmarks)

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
      new BinaryDocValuesField(field, new BytesRef(uriListBytes))
    }

    private def buildURIIdField(bookmarks: Seq[Bookmark]) = {
      buildIteratorField(URIGraphFields.uriField, bookmarks.iterator.filter(bm => !bm.isPrivate)){ bm => bm.uriId.toString }
    }

    private def buildBookmarkTitleList(publicIds: Array[Long], privateIds: Array[Long], bookmarks: Seq[Bookmark], preferedLang: Lang): ArrayBuffer[(Int, String, Lang)] = {
      val titleMap = bookmarks.foldLeft(Map.empty[Long, (String, Lang)]){ (m, b) =>
        val text = b.title.getOrElse("")
        m + (b.uriId.id -> (text, LangDetector.detect(text, preferedLang)))
      }

      var lineNo = 0
      var titles = new ArrayBuffer[(Int, String, Lang)]
      publicIds.foreach{ uriId =>
        titleMap.get(uriId).foreach{ case (title, lang) => titles += ((lineNo, title, lang)) }
        lineNo += 1
      }
      privateIds.foreach{ uriId =>
        titleMap.get(uriId).foreach{ case (title, lang) => titles += ((lineNo, title, lang)) }
        lineNo += 1
      }
      titles
    }

    private def buildBookmarkURLList(publicIds: Array[Long], privateIds: Array[Long], bookmarks: Seq[Bookmark]): ArrayBuffer[(Int, String, Lang)] = {
      val urlMap = bookmarks.foldLeft(Map.empty[Long,String]){ (m, b) => m + (b.uriId.id -> b.url) }

      var lineNo = 0
      var sites = new ArrayBuffer[(Int, String, Lang)]
      val en = LangDetector.en
      publicIds.foreach{ uriId =>
        urlMap.get(uriId).foreach{ site => sites += ((lineNo, site, en)) }
        lineNo += 1
      }
      privateIds.foreach{ uriId =>
        urlMap.get(uriId).foreach{ site => sites += ((lineNo, site, en)) }
        lineNo += 1
      }

      sites
    }
  }
}

class URIGraphUnknownVersionException(msg: String) extends Exception(msg)

