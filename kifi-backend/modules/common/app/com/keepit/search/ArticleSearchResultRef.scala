package com.keepit.search

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import java.sql.Connection
import org.joda.time.DateTime
import com.keepit.model._

case class ArticleSearchResultRef (
  id: Option[Id[ArticleSearchResultRef]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[ArticleSearchResultRef],
  state: State[ArticleSearchResultRef] = ArticleSearchResultRefStates.ACTIVE,
  last: Option[ExternalId[ArticleSearchResultRef]],
  myTotal: Int,
  friendsTotal: Int,
  mayHaveMoreHits: Boolean,
  millisPassed: Int,
  hitCount: Int,
  pageNumber: Int
) extends ModelWithExternalId[ArticleSearchResultRef] {
  def withId(id: Id[ArticleSearchResultRef]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object ArticleSearchResultRef {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit def articleSearchResultRefFormat = (
    (__ \ 'id).formatNullable(Id.format[ArticleSearchResultRef]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[ArticleSearchResultRef]) and
    (__ \ 'state).format(State.format[ArticleSearchResultRef]) and
    (__ \ 'last).formatNullable(ExternalId.format[ArticleSearchResultRef]) and
    (__ \ 'myTotal).format[Int] and
    (__ \ 'friendsTotal).format[Int] and
    (__ \ 'mayHaveMoreHits).format[Boolean] and
    (__ \ 'millisPassed).format[Int] and
    (__ \ 'hitCount).format[Int] and
    (__ \ 'pageNumber).format[Int]
  )(ArticleSearchResultRef.apply, unlift(ArticleSearchResultRef.unapply))
}

object ArticleSearchResultRefStates extends States[ArticleSearchResultRef]

object ArticleSearchResultFactory {
  def apply(res: ArticleSearchResult): ArticleSearchResultRef =
    ArticleSearchResultRef(externalId = res.uuid, createdAt = res.time, updatedAt = res.time,
        last = res.last, myTotal = res.myTotal, friendsTotal = res.friendsTotal, mayHaveMoreHits = res.mayHaveMoreHits, millisPassed = res.millisPassed,
        hitCount = res.hits.size, pageNumber = res.pageNumber)
}
