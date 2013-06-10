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

