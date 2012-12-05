package com.keepit.search.graph

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.{Bookmark, NormalizedURI, User}
import com.keepit.search.Lang
import com.keepit.search.index.{DefaultAnalyzer, Hit, Indexable, Indexer, IndexError, Searcher, QueryParser}
import com.keepit.common.db.CX
import play.api.Play.current
import org.apache.lucene.analysis.Analyzer
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

object URIGraph {
  val userTerm = new Term("usr", "")
  val uriTerm = new Term("uri", "")
  val titleTerm = new Term("title", "")
  val stemmedTerm = new Term("title_stemmed", "")

  def apply(indexDirectory: Directory): URIGraph = {
    val config = new IndexWriterConfig(Version.LUCENE_36, DefaultAnalyzer.forIndexing)

    new URIGraphImpl(indexDirectory, config)
  }
}

trait URIGraph {
  def load(): Int
  def update(userId: Id[User]): Int
  def getURIGraphSearcher(): URIGraphSearcher
  def getQueryParser(lang: Lang): QueryParser
  def close(): Unit
}

class URIGraphImpl(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig)
  extends Indexer[User](indexDirectory, indexWriterConfig) with URIGraph {

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

  def getQueryParser(lang: Lang) = new URIGraphQueryParser(DefaultAnalyzer.forParsing(lang))

  def search(queryText: String, lang: Lang = Lang("en")): Seq[Hit] = {
    parseQuery(queryText, lang) match {
      case Some(query) => searcher.search(query)
      case None => Seq.empty[Hit]
    }
  }

  def getURIGraphSearcher() = new URIGraphSearcher(searcher)

  def buildIndexable(user: User) = {
    val bookmarks = CX.withConnection { implicit c =>
        Bookmark.ofUser(user).filter{ b => b.state == Bookmark.States.ACTIVE }
    }
    new URIListIndexable(user.id.get, bookmarks)
  }

  class URIListIndexable(override val id: Id[User], val bookmarks: Seq[Bookmark]) extends Indexable[User] with LineFieldBuilder {
    override def buildDocument = {
      val lang = Lang("en") //TODO: detect
      val doc = super.buildDocument
      val payload = URIList.toByteArray(bookmarks)
      val usr = buildURIListPayloadField(payload)
      val uri = buildURIIdField(bookmarks)
      val title = buildBookmarkTitleField(URIGraph.titleTerm.field(), payload, bookmarks, DefaultAnalyzer.forIndexing(lang))
      doc.add(usr)
      doc.add(uri)
      doc.add(title)
      DefaultAnalyzer.forIndexingWithStemmer(lang).foreach{ analyzer =>
        val titleStemmed = buildBookmarkTitleField(URIGraph.stemmedTerm.field(), payload, bookmarks, analyzer)
        doc.add(titleStemmed)
      }
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

    def buildBookmarkTitleField(fieldName: String, payload: Array[Byte], bookmarks: Seq[Bookmark], analyzer: Analyzer) = {
      val titleMap = bookmarks.foldLeft(Map.empty[Long,String]){ (m, b) => m + (b.uriId.id -> b.title) }

      val list = new URIList(payload)
      val publicList = list.publicList
      val privateList =  list.privateList

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

      buildLineField(fieldName, lines, analyzer)
    }
  }

  class URIGraphQueryParser(analyzer: Analyzer) extends QueryParser(analyzer) {

    super.setAutoGeneratePhraseQueries(true)

    override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
      val booleanQuery = new BooleanQuery
      var query = super.getFieldQuery(URIGraph.titleTerm.field(), queryText, quoted)
      if (query != null) booleanQuery.add(query, Occur.SHOULD)

      if (!quoted) {
        query = super.getFieldQuery(URIGraph.stemmedTerm.field(), queryText, quoted)
        if (query != null) booleanQuery.add(query, Occur.SHOULD)
      }

      val clauses = booleanQuery.clauses
      if (clauses.size == 0) null
      else if (clauses.size == 1) clauses.get(0).getQuery()
      else booleanQuery
    }
  }
}

class URIGraphUnknownVersionException(msg: String) extends Exception(msg)

