package com.keepit.search;

import com.keepit.common.db.{CX, Entity, EntityTable, ExternalId, Id, State, NotFoundException}
import com.keepit.common.time._
import java.sql.Connection
import org.joda.time.DateTime
import ru.circumflex.orm._
import com.keepit.model.User

case class ArticleSearchResultRef (
  id: Option[Id[ArticleSearchResultRef]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[ArticleSearchResultRef],
  userId: Id[User],
  state: State[ArticleSearchResultRef] = ArticleSearchResultRef.States.ACTIVE
) {
  def save(implicit conn: Connection): ArticleSearchResultRef = {
    val entity = ArticleSearchResultRefEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

object ArticleSearchResultRef {
  object States {
    val ACTIVE = State[ArticleSearchResultRef]("active")
    val INACTIVE = State[ArticleSearchResultRef]("inactive")
  }
  
  def apply(res: ArticleSearchResult): ArticleSearchResultRef = 
    ArticleSearchResultRef(externalId = res.uuid, userId = res.userId)
  
  def get(id: Id[ArticleSearchResultRef])(implicit conn: Connection): ArticleSearchResultRef = ArticleSearchResultRefEntity.get(id).get.view
  
  def get(externalId: ExternalId[ArticleSearchResultRef])(implicit conn: Connection): ArticleSearchResultRef = getOpt(externalId).getOrElse(throw NotFoundException(externalId))

  def getOpt(externalId: ExternalId[ArticleSearchResultRef])(implicit conn: Connection): Option[ArticleSearchResultRef] =
    (ArticleSearchResultRefEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.externalId EQ externalId) unique }.map(_.view)

}

private[search] class ArticleSearchResultRefEntity extends Entity[ArticleSearchResultRef, ArticleSearchResultRefEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[ArticleSearchResultRef].NOT_NULL(ExternalId())
  val userId = "user_id".ID[User]
  val state = "state".STATE.NOT_NULL(ArticleSearchResultRef.States.ACTIVE)
  
  def relation = ArticleSearchResultRefEntity
  
  def view = ArticleSearchResultRef(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    userId = userId(),
    state = state()
  )
}

private[search] object ArticleSearchResultRefEntity extends ArticleSearchResultRefEntity with EntityTable[ArticleSearchResultRef, ArticleSearchResultRefEntity] {
  override def relationName = "email_address"
  
  def apply(view: ArticleSearchResultRef): ArticleSearchResultRefEntity = {
    val entity = new ArticleSearchResultRefEntity
    entity.id.set(view.id)
    entity.createdAt := view.createdAt
    entity.updatedAt := view.updatedAt
    entity.externalId := view.externalId
    entity.userId := view.userId
    entity.state := view.state
    entity
  }
}
