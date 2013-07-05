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

@ImplementedBy(classOf[ArticleSearchResultRefRepoImpl])
trait ArticleSearchResultRefRepo extends Repo[ArticleSearchResultRef] with ExternalIdColumnFunction[ArticleSearchResultRef]

@Singleton
class ArticleSearchResultRefRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ArticleSearchResultRef] with ArticleSearchResultRefRepo with ExternalIdColumnDbFunction[ArticleSearchResultRef] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[ArticleSearchResultRef](db, "article_search_result") with ExternalIdColumn[ArticleSearchResultRef] {
    def last = column[ExternalId[ArticleSearchResultRef]]("last", O.Nullable)
    def myTotal = column[Int]("my_total", O.NotNull)
    def friendsTotal = column[Int]("friends_total", O.NotNull)
    def mayHaveMoreHits = column[Boolean]("may_have_more_hits", O.NotNull)
    def millisPassed = column[Int]("millis_passed", O.NotNull)
    def hitCount = column[Int]("hit_count", O.NotNull)
    def pageNumber = column[Int]("page_number", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ state ~ last.? ~ myTotal ~ friendsTotal ~ mayHaveMoreHits ~ millisPassed ~ hitCount ~ pageNumber <> (ArticleSearchResultRef.apply _, ArticleSearchResultRef.unapply _)
  }
}

object ArticleSearchResultRefStates extends States[ArticleSearchResultRef]

object ArticleSearchResultFactory {
  def apply(res: ArticleSearchResult): ArticleSearchResultRef =
    ArticleSearchResultRef(externalId = res.uuid, createdAt = res.time, updatedAt = res.time,
        last = res.last, myTotal = res.myTotal, friendsTotal = res.friendsTotal, mayHaveMoreHits = res.mayHaveMoreHits, millisPassed = res.millisPassed,
        hitCount = res.hits.size, pageNumber = res.pageNumber)
}
