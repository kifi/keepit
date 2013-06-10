package com.keepit.search

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.social.BasicUser
import com.keepit.controllers.ext.PersonalSearchHit
import com.keepit.controllers.ext.PersonalSearchResult
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.{Future, promise}
import scala.concurrent.duration._
import com.keepit.common.db.ExternalId

trait ResultDecorator {
  def decorate(resultSet: ArticleSearchResult): Future[Seq[PersonalSearchResult]]
}

class ResultDecoratorImpl(val userId: Id[User], shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait) extends ResultDecorator {
  override def decorate(resultSet: ArticleSearchResult): Future[Seq[PersonalSearchResult]] = {

    shoeboxClient.getPersonalSearchInfo(userId, resultSet).map { case (allUsers, personalSearchHits) =>
      (resultSet.hits, resultSet.scorings, personalSearchHits).zipped.toSeq.map { case (hit, score, personalHit) =>
        val users = hit.users.map(allUsers)
        val isNew = (!hit.isMyBookmark && score.recencyScore > 0.5f)
        PersonalSearchResult(personalHit,
          hit.bookmarkCount,
          hit.isMyBookmark,
          personalHit.isPrivate,
          users,
          hit.score,
          isNew)
      }
    }
  }
}

class ResultDecoratorImpl2(searcher: MainSearcher, shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait) extends ResultDecorator {

  override def decorate(resultSet: ArticleSearchResult): Future[Seq[PersonalSearchResult]] = {
    val hits = resultSet.hits
    val users = hits.map(_.users).flatten.distinct
    val usersFuture = shoeboxClient.getBasicUsers(users)

    val personalSearchHits = hits.map{ h =>
      if (h.isMyBookmark) {
        val r = searcher.getBookmarkRecord(h.uriId).get
        PersonalSearchHit(Id[NormalizedURI](r.uriId), ExternalId[NormalizedURI](), Some(r.title), r.url, r.isPrivate)
      } else {
        val r = searcher.getArticleRecord(h.uriId).get
        PersonalSearchHit(Id[NormalizedURI](r.id), ExternalId[NormalizedURI](), Some(r.title), r.url, false)
      }
    }

    usersFuture.map{ basicUserMap =>
      (hits, resultSet.scorings, personalSearchHits).zipped.toSeq.map { case (hit, score, personalHit) =>
        val users = hit.users.map(basicUserMap)
        val isNew = (!hit.isMyBookmark && score.recencyScore > 0.5f)
        PersonalSearchResult(
          personalHit,
          hit.bookmarkCount,
          hit.isMyBookmark,
          personalHit.isPrivate,
          users,
          hit.score,
          isNew)
        }
    }
  }
}

