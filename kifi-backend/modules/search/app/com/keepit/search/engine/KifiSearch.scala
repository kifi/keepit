package com.keepit.search.engine

import com.keepit.common.akka.SafeFuture
import com.keepit.common.logging.Logging
import com.keepit.search.{ SearchTimeLogs, Searcher }
import com.keepit.search.article.ArticleRecord
import com.keepit.search.engine.result.KifiResultCollector.HitQueue
import com.keepit.search.engine.result.{ KifiShardResult, KifiShardHit, KifiResultCollector }
import com.keepit.search.graph.keep.{ KeepFields, KeepRecord }
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.{ TermQuery, BooleanQuery }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._

abstract class KifiSearch(articleSearcher: Searcher, keepSearcher: Searcher, timeLogs: SearchTimeLogs) extends Logging {

  protected var debugFlag: Int = 0
  protected var debugDumpBufIds: Set[Long] = null

  // debug flags
  def debug(debugMode: String): Unit = {
    import DebugOption._
    debugMode.split(",").map(_.toLowerCase).foldLeft(0) { (flag, str) =>
      str match {
        case DumpBuf(ids) =>
          debugDumpBufIds = ids
          flag | DumpBuf.flag
        case _ =>
          flag
      }
    }
    if (debugFlag != 0) log.info(s"debug flag set: $debugFlag")
  }

  def execute(): KifiShardResult

  @inline def createQueue(sz: Int) = new HitQueue(sz)

  @inline def isDiscoverable(id: Long) = keepSearcher.has(new Term(KeepFields.uriDiscoverableField, id.toString))

  @inline def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble / halfDecay, 3.0d))).toFloat

  def getKeepRecord(keepId: Long)(implicit decode: (Array[Byte], Int, Int) => KeepRecord): Option[KeepRecord] = {
    keepSearcher.getDecodedDocValue[KeepRecord](KeepFields.recordField, keepId)
  }

  def getKeepRecord(libId: Long, uriId: Long)(implicit decode: (Array[Byte], Int, Int) => KeepRecord): Option[KeepRecord] = {
    val q = new BooleanQuery()
    q.add(new TermQuery(new Term(KeepFields.uriField, uriId.toString)), Occur.MUST)
    q.add(new TermQuery(new Term(KeepFields.libraryField, libId.toString)), Occur.MUST)

    keepSearcher.search(q) { (scorer, reader) =>
      if (scorer.nextDoc() < NO_MORE_DOCS) {
        return keepSearcher.getDecodedDocValue(KeepFields.recordField, reader, scorer.docID())
      }
    }
    None
  }

  def getArticleRecord(uriId: Long): Option[ArticleRecord] = {
    import com.keepit.search.article.ArticleRecordSerializer._
    articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uriId)
  }

  def toKifiShardHit(h: KifiResultCollector.Hit): KifiShardHit = {
    val visibility = h.visibility
    if ((visibility & Visibility.HAS_SECONDARY_ID) != 0) {
      // has a keep id
      val r = getKeepRecord(h.keepId).getOrElse(throw new Exception(s"missing keep record: keep id = ${h.keepId}"))
      KifiShardHit(h.id, h.score, h.visibility, r.libraryId, h.keepId, r.title.getOrElse(""), r.url, r.externalId)
    } else {
      // only a primary id (uri id)
      val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
      KifiShardHit(h.id, h.score, h.visibility, -1L, -1L, r.title, r.url, null)
    }
  }

  def timing(): Unit = {
    SafeFuture {
      timeLogs.send()
    }
  }
}
