package com.keepit.search.graph

import scala.collection.mutable.ArrayBuffer

import java.io.StringReader

import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder
import com.keepit.search.index.{DefaultAnalyzer, Indexable, Indexer, IndexError}
import com.keepit.search.line.LineFieldBuilder

import play.api.Play.current

object URIGraph {
  val userTerm = new Term("usr", "")
  val uriTerm = new Term("uri", "")
  val langTerm = new Term("title_lang", "")
  val titleTerm = new Term("title", "")
  val stemmedTerm = new Term("title_stemmed", "")
  val siteTerm = new Term("site", "")

  val decoders = Map(
    userTerm.field() -> DocUtil.URIListDecoder,
    langTerm.field() -> DocUtil.LineFieldDecoder,
    titleTerm.field() -> DocUtil.LineFieldDecoder,
    stemmedTerm.field() -> DocUtil.LineFieldDecoder,
    siteTerm.field() -> DocUtil.LineFieldDecoder
  )

  def apply(indexDirectory: Directory): URIGraph = {
    val config = new IndexWriterConfig(Version.LUCENE_36, DefaultAnalyzer.forIndexing)

    new URIGraphImpl(indexDirectory, config, decoders)
  }
}

trait URIGraph {
  def update(): Int
  def getURIGraphSearcher(): URIGraphSearcher
  def close(): Unit

  private[search] def sequenceNumber: SequenceNumber
  private[search] def sequenceNumber_=(n: SequenceNumber)
}

class URIGraphImpl(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, decoders: Map[String, FieldDecoder])
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

      val usersChanged = inject[Database].readOnly { implicit s =>
        inject[BookmarkRepo].getUsersChanged(sequenceNumber)
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

  def getURIGraphSearcher() = new URIGraphSearcher(searcher)

  def buildIndexable(userIdAndSequenceNumber: (Id[User], SequenceNumber)): URIListIndexable = {
    val (userId, seq) = userIdAndSequenceNumber
    val bookmarks = inject[Database].readOnly { implicit session =>
      inject[BookmarkRepo].getByUser(userId)
    }
    new URIListIndexable(userId, seq, bookmarks)
  }

  class URIListIndexable(
    override val id: Id[User],
    override val sequenceNumber: SequenceNumber,
    val bookmarks: Seq[Bookmark]
  ) extends Indexable[User] with LineFieldBuilder {

    override def buildDocument = {
      val doc = super.buildDocument
      val payload = URIList.toByteArray(bookmarks)
      val usr = buildURIListPayloadField(payload)
      val uri = buildURIIdField(bookmarks)

      val uriList = new URIList(payload)
      val langMap = buildLangMap(bookmarks, Lang("en")) // TODO: use user's primary language to bias the detection or do the detection upon bookmark creation?
      val langs = buildBookmarkTitleField(URIGraph.langTerm.field(), uriList, bookmarks){ (fieldName, text) =>
        val lang = langMap.getOrElse(text, Lang("en"))
        Some(new IteratorTokenStream(Some(lang.lang).iterator, (s: String) => s))
      }

      val title = buildBookmarkTitleField(URIGraph.titleTerm.field(), uriList, bookmarks){ (fieldName, text) =>
        val lang = langMap.getOrElse(text, Lang("en"))
        val analyzer = DefaultAnalyzer.forIndexing(lang)
        Some(analyzer.tokenStream(fieldName, new StringReader(text)))
      }
      doc.add(usr)
      doc.add(uri)
      doc.add(title)
      val titleStemmed = buildBookmarkTitleField(URIGraph.stemmedTerm.field(), uriList, bookmarks){ (fieldName, text) =>
        val lang = langMap.getOrElse(text, Lang("en"))
        DefaultAnalyzer.forIndexingWithStemmer(lang).map{ analyzer =>
          analyzer.tokenStream(fieldName, new StringReader(text))
        }
      }
      doc.add(titleStemmed)
      doc.add(buildBookmarkSiteField(URIGraph.siteTerm.field(), uriList, bookmarks))
      doc
    }

    def buildURIListPayloadField(payload: Array[Byte]) = {
      buildDataPayloadField(URIGraph.userTerm.createTerm(id.toString), payload)
    }

    def buildURIIdField(bookmarks: Seq[Bookmark]) = {
      val fld = buildIteratorField(URIGraph.uriTerm.field(), bookmarks.iterator.filter(bm => !bm.isPrivate)){ bm => bm.uriId.toString }
      fld.setOmitNorms(true)
      fld
    }

    def buildLangMap(bookmarks: Seq[Bookmark], preferedLang: Lang) = {
      bookmarks.foldLeft(Map.empty[String,Lang]){ (m, b) => m + (b.title -> LangDetector.detect(b.title, preferedLang)) }
    }

    def buildBookmarkTitleField(fieldName: String, uriList: URIList, bookmarks: Seq[Bookmark])(tokenStreamFunc: (String, String)=>Option[TokenStream]) = {
      val titleMap = bookmarks.foldLeft(Map.empty[Long,String]){ (m, b) => m + (b.uriId.id -> b.title) }

      val publicList = uriList.publicList
      val privateList = uriList.privateList

      var lineNo = 0
      var lines = new ArrayBuffer[(Int, String)]
      publicList.foreach{ uriId =>
        titleMap.get(uriId).foreach{ title => lines += ((lineNo, title)) }
        lineNo += 1
      }
      privateList.foreach{ uriId =>
        titleMap.get(uriId).foreach{ title => lines += ((lineNo, title)) }
        lineNo += 1
      }

      buildLineField(fieldName, lines, tokenStreamFunc)
    }

    def buildBookmarkSiteField(fieldName: String, uriList: URIList, bookmarks: Seq[Bookmark]) = {
      val domainMap = bookmarks.foldLeft(Map.empty[Long,String]){ (m, b) => m + (b.uriId.id -> b.url) }

      val publicList = uriList.publicList
      val privateList = uriList.privateList

      var lineNo = 0
      var domains = new ArrayBuffer[(Int, String)]
      publicList.foreach{ uriId =>
        domainMap.get(uriId).foreach{ domain => domains += ((lineNo, domain)) }
        lineNo += 1
      }
      privateList.foreach{ uriId =>
        domainMap.get(uriId).foreach{ domain => domains += ((lineNo, domain)) }
        lineNo += 1
      }

      buildLineField(fieldName, domains, (fieldName, url) =>
        URI.parse(url).toOption.flatMap(_.host) match {
          case Some(Host(domain @ _*)) =>
            Some(new IteratorTokenStream((1 to domain.size).iterator, (n: Int) => domain.take(n).reverse.mkString(".")))
          case _ => None
        }
      )
    }
  }
}

class URIGraphUnknownVersionException(msg: String) extends Exception(msg)

