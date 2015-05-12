package com.keepit.search.engine.uri

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURI
import com.keepit.search.engine.result.{ Hit, HitQueue }
import com.keepit.search.engine.{ DebugOption, SearchTimeLogs, Visibility }
import com.keepit.search.index.Searcher
import com.keepit.search.index.article.ArticleRecord
import com.keepit.search.index.graph.keep.{ KeepFields, KeepRecord }
import org.apache.lucene.index.Term
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.math._

object UriSearch {
  @inline def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble / halfDecay, 3.0d))).toFloat
  @inline def createQueue(sz: Int) = new HitQueue(sz)
}

abstract class UriSearch(articleSearcher: Searcher, keepSearcher: Searcher, timeLogs: SearchTimeLogs) extends DebugOption { self: Logging =>

  def execute(): UriShardResult
  def explain(uriId: Id[NormalizedURI]): UriSearchExplanation

  @inline def isDiscoverable(id: Long) = keepSearcher.has(new Term(KeepFields.uriDiscoverableField, id.toString))

  def getKeepRecord(keepId: Long)(implicit decode: (Array[Byte], Int, Int) => KeepRecord): Option[KeepRecord] = {
    if (keepId >= 0) keepSearcher.getDecodedDocValue[KeepRecord](KeepFields.recordField, keepId) else None
  }

  def getArticleRecord(uriId: Long): Option[ArticleRecord] = {
    articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uriId)
  }

  def toKifiShardHit(h: Hit): UriShardHit = {
    if ((h.visibility & Visibility.HAS_SECONDARY_ID) != 0) {
      // has a keep id
      val r = getKeepRecord(h.secondaryId).getOrElse(throw new Exception(s"missing keep record: keep id = ${h.secondaryId}"))
      UriShardHit(h.id, h.score, h.visibility, r.libraryId, h.secondaryId, r.title, r.url, r.externalId)
    } else {
      // has a primary id (uri id) only
      val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
      UriShardHit(h.id, h.score, h.visibility, -1L, -1L, r.title, r.url, null)
    }
  }

  def timing(): Unit = {
    SafeFuture { timeLogs.send() }
    debugLog(timeLogs.toString)
  }
}
