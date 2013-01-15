package com.keepit.search;

import com.keepit.common.db.{CX, Entity, EntityTable, ExternalId, Id, State, NotFoundException}
import com.keepit.common.time._
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
) {
  def save(implicit conn: Connection): ArticleSearchResultRef = {
    val entity = ArticleSearchResultRefEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

object ArticleSearchResultRefStates {
  val ACTIVE = State[ArticleSearchResultRef]("active")
  val INACTIVE = State[ArticleSearchResultRef]("inactive")
}

object ArticleSearchResultRef {

  def apply(res: ArticleSearchResult): ArticleSearchResultRef =
    ArticleSearchResultRef(externalId = res.uuid, createdAt = res.time, updatedAt = res.time,
        last = res.last, myTotal = res.myTotal, friendsTotal = res.friendsTotal, mayHaveMoreHits = res.mayHaveMoreHits, millisPassed = res.millisPassed,
        hitCount = res.hits.size, pageNumber = res.pageNumber)

  def get(id: Id[ArticleSearchResultRef])(implicit conn: Connection): ArticleSearchResultRef = ArticleSearchResultRefEntity.get(id).get.view

  def get(externalId: ExternalId[ArticleSearchResultRef])(implicit conn: Connection): ArticleSearchResultRef = getOpt(externalId).getOrElse(throw NotFoundException(externalId))

  def getOpt(externalId: ExternalId[ArticleSearchResultRef])(implicit conn: Connection): Option[ArticleSearchResultRef] =
    (ArticleSearchResultRefEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.externalId EQ externalId) unique }.map(_.view)

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
