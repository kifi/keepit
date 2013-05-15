package com.keepit.model

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.time._

case class Collection(
  id: Option[Id[Collection]] = None,
  externalId: ExternalId[Collection] = ExternalId(),
  userId: Id[User],
  name: String,
  state: State[Collection] = CollectionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
  ) extends ModelWithExternalId[Collection] {
  def withId(id: Id[Collection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[CollectionRepoImpl])
trait CollectionRepo extends Repo[Collection] with ExternalIdColumnFunction[Collection] {
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Collection]
  def getByUserAndName(userId: Id[User], name: String)(implicit session: RSession): Option[Collection]
}

@Singleton
class CollectionRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[Collection] with CollectionRepo with ExternalIdColumnDbFunction[Collection] {

  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[Collection](db, "collection") with ExternalIdColumn[Collection] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def * = id.? ~ externalId ~ userId ~ name ~ state ~ createdAt ~ updatedAt <> (Collection.apply _,
        Collection.unapply _)
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Collection] =
    (for (c <- table if c.userId === userId && c.state === CollectionStates.ACTIVE) yield c).list

  def getByUserAndName(userId: Id[User], name: String)(implicit session: RSession): Option[Collection] =
    (for (c <- table if c.userId === userId && c.name === name
        && c.state === CollectionStates.ACTIVE) yield c).firstOption
}

object CollectionStates extends States[Collection]
