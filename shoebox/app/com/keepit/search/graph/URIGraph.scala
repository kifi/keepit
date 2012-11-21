package com.keepit.search.graph

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.{Bookmark, NormalizedURI, User}
import com.keepit.search.index.{DefaultAnalyzer, Hit, Indexable, Indexer, IndexError, Searcher, QueryParser}
import com.keepit.common.db.CX
import play.api.Play.current
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.line.LineFieldBuilder

object URIGraph {
  val userTerm = new Term("usr", "")
  val uriTerm = new Term("uri", "")
  val titleTerm = new Term("title", "")

  val indexingAnalyzer = DefaultAnalyzer.forIndexing
  val parsingAnalyzer = DefaultAnalyzer.forParsing

  def apply(indexDirectory: Directory): URIGraph = {
    val config = new IndexWriterConfig(Version.LUCENE_36, indexingAnalyzer)

    new URIGraphImpl(indexDirectory, config, parsingAnalyzer)
  }
}

trait URIGraph {
  def load(): Int
  def update(userId: Id[User]): Int
  def getURIGraphSearcher(): URIGraphSearcher
  def getQueryParser: QueryParser
  def close(): Unit
}

class URIGraphImpl(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, parsingAnalyzer: Analyzer)
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

  def getQueryParser = new URIGraphQueryParser

  def search(queryText: String): Seq[Hit] = {
    parseQuery(queryText) match {
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
      val doc = super.buildDocument
      val payload = URIList.toByteArray(bookmarks)
      val usr = buildURIListPayloadField(payload)
      val uri = buildURIIdField(bookmarks)
      val title = buildBookmarkTitleField(doc, payload, bookmarks, indexWriterConfig.getAnalyzer)
      doc.add(usr)
      doc.add(uri)
      doc.add(title)
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

    def buildBookmarkTitleField(doc: Document, payload: Array[Byte], bookmarks: Seq[Bookmark], analyzer: Analyzer) = {
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

      buildLineField(URIGraph.titleTerm.field(), lines, analyzer)
    }
  }

  class URIGraphQueryParser extends QueryParser(parsingAnalyzer) {

    super.setAutoGeneratePhraseQueries(true)

    override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
      super.getFieldQuery("title", queryText, quoted)
    }
  }
}

class URIGraphUnknownVersionException(msg: String) extends Exception(msg)

