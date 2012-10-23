package com.keepit.search.graph

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.{Bookmark, NormalizedURI, User}
import com.keepit.search.index.{Hit, Indexable, Indexer, IndexError, Searcher}
import com.keepit.common.db.CX
import play.api.Play.current
import org.apache.lucene.analysis.KeywordAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryParser.QueryParser
import org.apache.lucene.search.Query
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version

object URIGraph {
  val userTerm = new Term("usr", "")
  val uriTerm = new Term("uri", "")

  def apply(indexDirectory: Directory): URIGraph = {
    val analyzer = new KeywordAnalyzer()
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

    new URIGraphImpl(indexDirectory, config)
  }
}

trait URIGraph {
  def load(): Int
  def update(userId: Id[User]): Int
  def getURIGraphSearcher(): URIGraphSearcher
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
    log.info("updating a URIGraph for user=%d".format(userId))
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

  private val parser = new QueryParser(Version.LUCENE_36, "b", indexWriterConfig.getAnalyzer())

  def parse(queryText: String): Query = {
    parser.parse(queryText)
  }
  
  def search(queryString: String): Seq[Hit] = searcher.search(parse(queryString))

  def getURIGraphSearcher() = new URIGraphSearcher(searcher)
  
  def buildIndexable(user: User) = {
    val bookmarks = CX.withConnection { implicit c =>
        Bookmark.ofUser(user)
    }
    new URIListIndexable(user.id.get, bookmarks)
  }
  
  class URIListIndexable(override val id: Id[User], val bookmarks: Seq[Bookmark]) extends Indexable[User] {
    override def buildDocument = {
      val doc = super.buildDocument
      val usr = buildURIListPayloadField(bookmarks)
      val uri = buildURIIdField(bookmarks)
      doc.add(usr)
      doc.add(uri)
      doc
    }
    
    def buildURIListPayloadField(bookmarks: Seq[Bookmark]) = {
      buildDataPayloadField(URIGraph.userTerm.createTerm(id.toString), URIList.toByteArray(bookmarks))
    }
    
    def buildURIIdField(bookmarks: Seq[Bookmark]) = {
      val fld = buildIteratorField(URIGraph.uriTerm.field(), bookmarks.iterator.filter(bm => !bm.isPrivate)){ bm => bm.uriId.toString }
      fld.setOmitNorms(true)
      fld
    }
  }
}

class URIGraphUnknownVersionException(msg: String) extends Exception(msg)

