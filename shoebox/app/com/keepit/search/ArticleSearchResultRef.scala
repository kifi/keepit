package com.keepit.search;

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import java.sql.Connection
import org.joda.time.DateTime
import ru.circumflex.orm._
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
  def save(implicit conn: Connection): ArticleSearchResultRef = {
    val entity = ArticleSearchResultRefEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

@ImplementedBy(classOf[ArticleSearchResultRefRepoImpl])
trait ArticleSearchResultRefRepo extends Repo[ArticleSearchResultRef] with ExternalIdColumnFunction[ArticleSearchResultRef] {
}

@Singleton
class ArticleSearchResultRefRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[ArticleSearchResultRef] with ArticleSearchResultRefRepo with ExternalIdColumnDbFunction[ArticleSearchResultRef] {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[ArticleSearchResultRef](db, "article_search_result") with ExternalIdColumn[ArticleSearchResultRef] {
    def last = column[ExternalId[ArticleSearchResultRef]]("last", O.Nullable)
    def myTotal = column[Int]("my_total", O.NotNull)
    def friendsTotal = column[Int]("friends_total", O.NotNull)
    def mayHaveMoreHits = column[Boolean]("may_have_more_hits", O.NotNull)
    def millisPassed = column[Int]("millis_passed", O.NotNull)
    def hitCount = column[Int]("hit_count", O.NotNull)
    def pageNumber = column[Int]("page_number", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ state ~ last.? ~ myTotal ~ friendsTotal ~ mayHaveMoreHits ~ millisPassed ~ hitCount ~ pageNumber <> (ArticleSearchResultRef, ArticleSearchResultRef.unapply _)
  }
}

object ArticleSearchResultRefStates {
  val ACTIVE = State[ArticleSearchResultRef]("active")
  val INACTIVE = State[ArticleSearchResultRef]("inactive")
}

object ArticleSearchResultFactory {
  def apply(res: ArticleSearchResult): ArticleSearchResultRef =
    ArticleSearchResultRef(externalId = res.uuid, createdAt = res.time, updatedAt = res.time,
        last = res.last, myTotal = res.myTotal, friendsTotal = res.friendsTotal, mayHaveMoreHits = res.mayHaveMoreHits, millisPassed = res.millisPassed,
        hitCount = res.hits.size, pageNumber = res.pageNumber)
}

private[search] class ArticleSearchResultRefEntity extends Entity[ArticleSearchResultRef, ArticleSearchResultRefEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[ArticleSearchResultRef].NOT_NULL(ExternalId())
  val state = "state".STATE.NOT_NULL(ArticleSearchResultRefStates.ACTIVE)
  val last = "last".EXTERNAL_ID[ArticleSearchResultRef]
  val myTotal = "my_total".INTEGER.NOT_NULL
  val friendsTotal = "friends_total".INTEGER.NOT_NULL
  val mayHaveMoreHits = "may_have_more_hits".BOOLEAN.NOT_NULL
  val millisPassed = "millis_passed".INTEGER.NOT_NULL
  val hitCount = "hit_count".INTEGER.NOT_NULL
  val pageNumber = "page_number".INTEGER.NOT_NULL

  def relation = ArticleSearchResultRefEntity

  def view = ArticleSearchResultRef(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    state = state(),
    last = last.value,
    myTotal = myTotal(),
    friendsTotal = friendsTotal(),
    mayHaveMoreHits = mayHaveMoreHits(),
    millisPassed = millisPassed(),
    hitCount = hitCount(),
    pageNumber = pageNumber()
  )
}

private[search] object ArticleSearchResultRefEntity extends ArticleSearchResultRefEntity with EntityTable[ArticleSearchResultRef, ArticleSearchResultRefEntity] {
  override def relationName = "article_search_result"

  def apply(view: ArticleSearchResultRef): ArticleSearchResultRefEntity = {
    val entity = new ArticleSearchResultRefEntity
    entity.id.set(view.id)
    entity.createdAt := view.createdAt
    entity.updatedAt := view.updatedAt
    entity.externalId := view.externalId
    entity.state := view.state
    entity.last.set(view.last)
    entity.myTotal := view.myTotal
    entity.friendsTotal := view.friendsTotal
    entity.mayHaveMoreHits := view.mayHaveMoreHits
    entity.millisPassed := view.millisPassed
    entity.hitCount := view.hitCount
    entity.pageNumber := view.pageNumber
    entity
  }
}
