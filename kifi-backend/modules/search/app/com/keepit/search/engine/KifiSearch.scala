package com.keepit.search.engine

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURI
import com.keepit.search.Searcher
import com.keepit.search.index.article.ArticleRecord
import com.keepit.search.engine.explain.Explanation
import com.keepit.search.engine.result.{ Hit, HitQueue, KifiShardResult, KifiShardHit }
import com.keepit.search.index.graph.keep.{ KeepFields, KeepRecord }
import org.apache.lucene.index.Term
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._

object KifiSearch {
  @inline def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble / halfDecay, 3.0d))).toFloat
  @inline def createQueue(sz: Int) = new HitQueue(sz)
}

abstract class KifiSearch(articleSearcher: Searcher, keepSearcher: Searcher, timeLogs: SearchTimeLogs) extends DebugOption { self: Logging =>

  def execute(): KifiShardResult
  def explain(uriId: Id[NormalizedURI]): Explanation

  @inline def isDiscoverable(id: Long) = keepSearcher.has(new Term(KeepFields.uriDiscoverableField, id.toString))

  def getKeepRecord(keepId: Long)(implicit decode: (Array[Byte], Int, Int) => KeepRecord): Option[KeepRecord] = {
    if (keepId >= 0) keepSearcher.getDecodedDocValue[KeepRecord](KeepFields.recordField, keepId) else None
  }

  def getArticleRecord(uriId: Long): Option[ArticleRecord] = {
    import com.keepit.search.index.article.ArticleRecordSerializer._
    articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uriId)
  }

  def toKifiShardHit(h: Hit): KifiShardHit = {
    if ((h.visibility & Visibility.HAS_SECONDARY_ID) != 0) {
      // has a keep id
      val r = getKeepRecord(h.secondaryId).getOrElse(throw new Exception(s"missing keep record: keep id = ${h.secondaryId}"))
      KifiShardHit(h.id, h.score, h.visibility, r.libraryId, h.secondaryId, r.title.getOrElse(""), r.url, r.externalId)
    } else {
      // has a primary id (uri id) only
      val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
      KifiShardHit(h.id, h.score, h.visibility, -1L, -1L, r.title, r.url, null)
    }
  }

  def timing(): Unit = {
    SafeFuture { timeLogs.send() }
    debugLog(timeLogs.toString)
  }
}
