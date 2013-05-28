package com.keepit.model

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.time._

case class Collection(
  id: Option[Id[Collection]] = None,
  externalId: ExternalId[Collection] = ExternalId(),
  userId: Id[User],
  name: String,
  state: State[Collection] = CollectionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  seq: SequenceNumber = SequenceNumber.ZERO
  ) extends ModelWithExternalId[Collection] {
  def withId(id: Id[Collection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == CollectionStates.ACTIVE
}

@ImplementedBy(classOf[CollectionRepoImpl])
trait CollectionRepo extends Repo[Collection] with ExternalIdColumnFunction[Collection] {
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Collection]
  def getByUserAndExternalId(userId: Id[User], externalId: ExternalId[Collection])
      (implicit session: RSession): Option[Collection]
  def getByUserAndName(userId: Id[User], name: String,
      excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))
      (implicit session: RSession): Option[Collection]
  def getUsersChanged(num: SequenceNumber)(implicit session: RSession): Seq[(Id[User], SequenceNumber)]
  def getCollectionsChanged(num: SequenceNumber)
      (implicit session: RSession): Seq[(Id[Collection], Id[User], SequenceNumber)]
  def updateSequenceNumber(modelId: Id[Collection])(implicit session: RWSession)
}

@Singleton
class CollectionRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[Collection] with CollectionRepo with ExternalIdColumnDbFunction[Collection] {

  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  private val sequence = db.getSequence("collection_sequence")

  override val table = new RepoTable[Collection](db, "collection") with ExternalIdColumn[Collection] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def seq = column[SequenceNumber]("seq", O.NotNull)
    def * = id.? ~ externalId ~ userId ~ name ~ state ~ createdAt ~ updatedAt ~ seq <> (Collection.apply _,
        Collection.unapply _)
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Collection] =
    (for (c <- table if c.userId === userId && c.state === CollectionStates.ACTIVE) yield c).list

  def getByUserAndExternalId(userId: Id[User], externalId: ExternalId[Collection])
      (implicit session: RSession): Option[Collection] =
    (for {
      c <- table if c.userId === userId && c.externalId === externalId && c.state === CollectionStates.ACTIVE
    } yield c).firstOption

  def getByUserAndName(userId: Id[User], name: String,
      excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))
      (implicit session: RSession): Option[Collection] =
    (for (c <- table if c.userId === userId && c.name === name
        && c.state =!= excludeState.getOrElse(null)) yield c).firstOption

  override def save(model: Collection)(implicit session: RWSession): Collection = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    super.save(newModel)
  }

  def updateSequenceNumber(modelId: Id[Collection])(implicit session: RWSession) {
    (for (c <- table if c.id === modelId) yield c.seq).update(sequence.incrementAndGet())
  }

  def getUsersChanged(num: SequenceNumber)(implicit session: RSession): Seq[(Id[User], SequenceNumber)] =
    (for (c <- table if c.seq > num) yield c)
        .groupBy(_.userId).map{ case (u, c) => (u -> c.map(_.seq).max.get) }.sortBy(_._2).list

  def getCollectionsChanged(num: SequenceNumber)
      (implicit session: RSession): Seq[(Id[Collection], Id[User], SequenceNumber)] =
    (for (c <- table if c.seq > num) yield (c.id, c.userId, c.seq)).sortBy(_._3).list
}

object CollectionStates extends States[Collection]
