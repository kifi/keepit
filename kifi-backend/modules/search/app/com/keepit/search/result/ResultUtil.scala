package com.keepit.search.result

import com.keepit.search.engine.Visibility
import com.keepit.search.engine.uri.{ UriShardHit, UriSearchResult }
import com.keepit.common.db.{ Id, ExternalId }
import org.joda.time.DateTime
import com.keepit.search.ArticleHit
import com.keepit.search.ArticleSearchResult
import com.keepit.model.{ NormalizedURI }

object ResultUtil {

  def toArticleSearchResult(
    res: UriSearchResult,
    last: Option[String], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
    millisPassed: Int,
    pageNumber: Int,
    previousHits: Int,
    time: DateTime,
    lang: String): ArticleSearchResult = {

    def toArticleHit(hit: UriShardHit): ArticleHit = {
      ArticleHit(
        Id[NormalizedURI](hit.id),
        hit.finalScore,
        hit.score,
        (hit.visibility & (Visibility.OWNER | Visibility.MEMBER)) != 0,
        (hit.visibility & Visibility.NETWORK) != 0)
    }

    val lastUUID = for { str <- last if str.nonEmpty } yield ExternalId[ArticleSearchResult](str)

    ArticleSearchResult(
      lastUUID,
      res.query,
      res.hits map toArticleHit,
      res.myTotal,
      res.friendsTotal,
      res.othersTotal,
      res.mayHaveMoreHits,
      res.idFilter,
      millisPassed,
      pageNumber,
      previousHits,
      res.uuid,
      time,
      res.show,
      lang
    )
  }
}
