package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{SequenceNumber, State, ExternalId, Id}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import scala.Some
import org.joda.time.DateTime
import com.keepit.common.time._

@ImplementedBy(classOf[CollectionRepoImpl])
trait CollectionRepo extends Repo[Collection] with ExternalIdColumnFunction[Collection] {
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Collection]
  def getByUserAndExternalId(userId: Id[User], externalId: ExternalId[Collection])
    (implicit session: RSession): Option[Collection]
  def getByUserAndName(userId: Id[User], name: String,
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))
    (implicit session: RSession): Option[Collection]
  def getCollectionsChanged(num: SequenceNumber, limit: Int)
    (implicit session: RSession): Seq[(Id[Collection], Id[User], SequenceNumber)]
  def keepsChanged(modelId: Id[Collection], isActive: Boolean)(implicit session: RWSession)
}

@Singleton
class CollectionRepoImpl @Inject() (
                                     val userCollectionsCache: UserCollectionsCache,
                                     val db: DataBaseComponent,
                                     val clock: Clock)
  extends DbRepo[Collection] with CollectionRepo with ExternalIdColumnDbFunction[Collection] {

  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  private val sequence = db.getSequence("collection_sequence")

  override val table = new RepoTable[Collection](db, "collection") with ExternalIdColumn[Collection] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def lastKeptTo = column[Option[DateTime]]("last_kept_to", O.Nullable)
    def seq = column[SequenceNumber]("seq", O.NotNull)
    def * = id.? ~ externalId ~ userId ~ name ~ state ~ createdAt ~ updatedAt ~ lastKeptTo ~ seq <> (
      Collection.apply _, Collection.unapply _)
  }

  override def invalidateCache(collection: Collection)(implicit session: RSession): Collection = {
    userCollectionsCache.set(UserCollectionsKey(collection.userId),
      (for (c <- table if c.userId === collection.userId && c.state === CollectionStates.ACTIVE) yield c).list)
    collection
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Collection] =
    (userCollectionsCache.getOrElse(UserCollectionsKey(userId)) {
      (for (c <- table if c.userId === userId && c.state === CollectionStates.ACTIVE) yield c).list
    }).sortBy(_.lastKeptTo).reverse

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

  def keepsChanged(modelId: Id[Collection], isActive: Boolean)(implicit session: RWSession) {
    if (isActive) {
      save(get(modelId) withLastKeptTo clock.now())
    } else {
      (for (c <- table if c.id === modelId) yield c.seq).update(sequence.incrementAndGet())
    }
  }

  def getCollectionsChanged(num: SequenceNumber, limit: Int)
      (implicit session: RSession): Seq[(Id[Collection], Id[User], SequenceNumber)] =
    (for (c <- table if c.seq > num) yield (c.id, c.userId, c.seq)).sortBy(_._3).take(limit).list
}
