package com.keepit.search.graph

import com.keepit.common.logging.Logging
import com.keepit.common.db.CX
import com.keepit.common.db.Id
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model.{Bookmark, NormalizedURI, User}
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.{DefaultAnalyzer, Hit, Indexable, Indexer, IndexError, Searcher, QueryParser}
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil
import play.api.Play.current
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.Query
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.line.LineFieldBuilder
import java.io.StringReader
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder

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
  def load(): Int
  def update(userId: Id[User]): Int
  def getURIGraphSearcher(): URIGraphSearcher
  def getQueryParser(lang: Lang, proximityBoost: Float): QueryParser
  def close(): Unit
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

  def load(): Int = {
    log.info("loading URIGraph")
    try {
      val users = CX.withConnection { implicit c => User.all }
      var cnt = 0
      indexDocuments(users.iterator.map{ user => buildIndexable(user) }, commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
      cnt
    } catch {
      case ex: Throwable =>
        log.error("error in loading", ex)
        throw ex
    }
  }

  def update(userId: Id[User]): Int = {
    log.info("updating a URIGraph for user=%d".format(userId.id))
    try {
      val user = CX.withConnection { implicit c => User.get(userId) }
      var cnt = 0
      indexDocuments(Iterator(buildIndexable(user)), commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
      cnt
    } catch {
      case ex: Throwable =>
        log.error("error in URIGraph update", ex)
        throw ex
    }
  }

  def getQueryParser(lang: Lang): QueryParser = getQueryParser(lang, 0.0f)

  def getQueryParser(lang: Lang, proximityBoost: Float) = {
    val total = 1.0f + proximityBoost
    val parser = new URIGraphQueryParser(DefaultAnalyzer.forParsing(lang), 1.0f/total, proximityBoost/total)
    DefaultAnalyzer.forParsingWithStemmer(lang).foreach{ parser.setStemmingAnalyzer(_) }
    parser
  }

  def search(queryText: String, lang: Lang = Lang("en")): Seq[Hit] = {
    parseQuery(queryText, lang) match {
      case Some(query) => searcher.search(query)
      case None => Seq.empty[Hit]
    }
  }

  def getURIGraphSearcher() = new URIGraphSearcher(searcher)

  def buildIndexable(id: Id[User]) = {
    val user = CX.withConnection{ implicit c => User.get(id) }
    buildIndexable(user)
  }

  def buildIndexable(user: User) = {
    val bookmarks = CX.withConnection { implicit c =>
        Bookmark.ofUser(user).filter{ b => b.state == Bookmark.States.ACTIVE }
    }
    new URIListIndexable(user.id.get, bookmarks)
  }

  class URIListIndexable(override val id: Id[User], val bookmarks: Seq[Bookmark]) extends Indexable[User] with LineFieldBuilder {
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
        URI.parse(url).flatMap{ uri =>
          uri.host match {
            case Some(Host(domain @ _*)) =>
              Some(new IteratorTokenStream((1 to domain.size).iterator, (n: Int) => domain.take(n).reverse.mkString(".")))
            case _ => None
          }
        }
      )
    }
  }

  class URIGraphQueryParser(analyzer: Analyzer, baseBoost: Float, proximityBoost: Float) extends QueryParser(analyzer) {

    super.setAutoGeneratePhraseQueries(true)

    override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
      field.toLowerCase match {
        case "site" => getSiteQuery(queryText)
        case _ => getTextQuery(queryText, quoted)
      }
    }

    private def getTextQuery(queryText: String, quoted: Boolean) = {
      val booleanQuery = new BooleanQuery(true)
      var query = super.getFieldQuery(URIGraph.titleTerm.field(), queryText, quoted)
      if (query != null) booleanQuery.add(query, Occur.SHOULD)

      if (!quoted) {
        super.getStemmedFieldQueryOpt(URIGraph.stemmedTerm.field(), queryText).foreach{ query => booleanQuery.add(query, Occur.SHOULD) }
      }

      val clauses = booleanQuery.clauses
      if (clauses.size == 0) null
      else if (clauses.size == 1) clauses.get(0).getQuery()
      else booleanQuery
    }

    override def parseQuery(queryText: String) = {
      super.parseQuery(queryText).map{ query =>
        val terms = QueryUtil.getTermSeq(URIGraph.stemmedTerm.field(), query)
        val termSize = terms.size
        if (termSize > 1) {
          val booleanQuery = new BooleanQuery(true)
          query.setBoost(baseBoost)
          booleanQuery.add(query, Occur.MUST)
          val proxQ = ProximityQuery(terms)
          proxQ.setBoost(proximityBoost)
          booleanQuery.add(proxQ, Occur.SHOULD)
          booleanQuery
        } else {
          query
        }
      }
    }
  }
}

class URIGraphUnknownVersionException(msg: String) extends Exception(msg)

