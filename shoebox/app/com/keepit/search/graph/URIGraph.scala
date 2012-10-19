package com.keepit.search.graph;

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.{Bookmark, NormalizedURI, User}
import com.keepit.search.index.{Hit, Indexable, Indexer, IndexError}
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
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.store.DataInput
import org.apache.lucene.util.Version
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

object URIGraph {
  def apply(indexDirectory: Directory): URIGraph = {
    val analyzer = new KeywordAnalyzer()
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

    new URIGraph(indexDirectory, config)
  }
}

class URIGraph(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) extends Indexer[User](indexDirectory, indexWriterConfig) {

  val userTerm = new Term("usr", "")
  val uriTerm = new Term("uri", "")

  val commitBatchSize = 100
  val fetchSize = commitBatchSize * 3

  def commitCallback(commitBatch: Seq[(Indexable[User], Option[IndexError])]) = {
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

  def update(user: User) = {
    log.info("updating a URIGraph for user=%d".format(user.id.get))
    try {
      var cnt = 0
      indexDocuments(Iterator(buildIndexable(user)), commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
      cnt
    } catch {
      case ex: Throwable => 
        log.error("error in indexing run", ex) // log and eat the exception
        throw ex
    }
  }

  private val parser = new QueryParser(Version.LUCENE_36, "b", indexWriterConfig.getAnalyzer())

  def parse(queryText: String): Query = {
    parser.parse(queryText)
  }
  
  def search(queryString: String): Seq[Hit] = searcher.search(parse(queryString))
  
  def buildIndexable(user: User) = {
    val bookmarks = CX.withConnection { implicit c =>
        Bookmark.ofUser(user)
    }
    new URIListIndexable(user.id.get, bookmarks)
  }
  

  
  def getUserToUriPayload(user: Id[User]): Option[URIList] = {
    val term = userTerm.createTerm(user.toString)
    var urilist: Option[URIList] = None;
    val tp = searcher.indexReader.termPositions(term);
    try {
      if (tp.next()) {
        tp.nextPosition();
        val payloadLen = tp.getPayloadLength
        if (payloadLen > 0) {
          val payloadBuffer = new Array[Byte](payloadLen)
          tp.getPayload(payloadBuffer, 0)
          urilist = Some(buildURIList(payloadBuffer))
        }
      }
    } finally {
      tp.close();
    }
    urilist
  }
  
  def buildURIList(payload: Array[Byte]): URIList = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(payload))
    val version = in.readByte()
    version match {
      case 1 => new URIList(in)
      case _ => throw new URIGraphUnknownVersionException("network payload version=%d".format(version))
    }
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
      val (privateBookmarks, publicBookmarks) = bookmarks.partition(_.isPrivate)
      val baos = new ByteArrayOutputStream(publicBookmarks.size * 4)
      val out = new OutputStreamDataOutput(baos)
      // version
      out.writeByte(1)
      // public list size and private list size
      out.writeVInt(publicBookmarks.size)
      out.writeVInt(privateBookmarks.size)
      // encode public list
      var current = 0L
      publicBookmarks.map(_.uriId.id).sorted.foreach{ id =>
        out.writeVLong(id - current)
        current = id
      }
      // encode private list
      current = 0L
      privateBookmarks.map(_.uriId.id).sorted.foreach{ id  =>
        out.writeVLong(id - current)
        current = id
      }
      baos.flush()
      buildDataPayloadField(userTerm, baos.toByteArray())
    }
    
    def buildURIIdField(bookmarks: Seq[Bookmark]) = {
      val fld = buildIteratorField(uriTerm.field(), bookmarks.iterator.filter(bm => !bm.isPrivate)){ bm => bm.uriId.toString }
      fld.setOmitNorms(true)
      fld
    }
  }
  
  class URIList(in: DataInput) {
    val publicListSize = in.readVInt()
    val privateListSize = in.readVInt()
    val publicList = readList(publicListSize)
    lazy val privateList = readList(privateListSize)
    
    private def readList(length: Int) = {
      val arr = new Array[Long](length)
      var current = 0L;
      var i = 0
      while (i < length) {
        val id = current + in.readVLong
        arr(i) = id
        current = id
        i += 1
      }
      arr
    }
  }
  
  class URIGraphUnknownVersionException(msg: String) extends Exception(msg)
}
