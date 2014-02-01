package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{SequenceNumber, State, ExternalId, Id}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import scala.Some
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import scala.util.Try
import play.api.libs.json.Json
import com.keepit.common.logging.Logging
import com.keepit.heimdal.HeimdalServiceClient

@ImplementedBy(classOf[CollectionRepoImpl])
trait CollectionRepo extends Repo[Collection] with ExternalIdColumnFunction[Collection] with SeqNumberFunction[Collection]{
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Collection]
  def getByUserAndExternalId(userId: Id[User], externalId: ExternalId[Collection])
    (implicit session: RSession): Option[Collection]
  def getByUserAndName(userId: Id[User], name: String,
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))
    (implicit session: RSession): Option[Collection]
  def getCollectionsChanged(num: SequenceNumber, limit: Int)(implicit session: RSession): Seq[Collection]
  def collectionChanged(modelId: Id[Collection], isActive: Boolean = false)(implicit session: RWSession)
}

@Singleton
class CollectionRepoImpl @Inject() (
  val userCollectionsCache: UserCollectionsCache,
  val elizaServiceClient: ElizaServiceClient,
  val heimdal: HeimdalServiceClient,
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[Collection] with CollectionRepo with ExternalIdColumnDbFunction[Collection] with SeqNumberDbFunction[Collection] with Logging {

  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  private val sequence = db.getSequence("collection_sequence")

  override val table = new RepoTable[Collection](db, "collection") with ExternalIdColumn[Collection] with SeqNumberColumn[Collection]{
    def userId = column[Id[User]]("user_id", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def lastKeptTo = column[Option[DateTime]]("last_kept_to", O.Nullable)
    def * = id.? ~ externalId ~ userId ~ name ~ state ~ createdAt ~ updatedAt ~ lastKeptTo ~ seq <> (
      Collection.apply _, Collection.unapply _)
  }

  override def invalidateCache(collection: Collection)(implicit session: RSession): Unit = {
    userCollectionsCache.set(UserCollectionsKey(collection.userId),
      (for (c <- table if c.userId === collection.userId && c.state === CollectionStates.ACTIVE) yield c).list)
  }

  override def deleteCache(model: Collection)(implicit session: RSession): Unit = {
    userCollectionsCache.remove(UserCollectionsKey(model.userId))
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
    Try {
      if (model.state == CollectionStates.INACTIVE) {
        elizaServiceClient.sendToUser(model.userId, Json.arr("remove_tag", SendableTag.from(model)))
      } else if (model.id == None) {
        elizaServiceClient.sendToUser(model.userId, Json.arr("create_tag", SendableTag.from(model)))
      } else {
        elizaServiceClient.sendToUser(model.userId, Json.arr("rename_tag", SendableTag.from(model)))
      }
    }
    super.save(newModel)
  }

  def collectionChanged(collectionId: Id[Collection], isNewKeep: Boolean = false)(implicit session: RWSession) {
    if (isNewKeep) {
      save(get(collectionId) withLastKeptTo clock.now())
    } else {
      (for (c <- table if c.id === collectionId) yield c.seq).update(sequence.incrementAndGet())
    }
  }

  def getCollectionsChanged(num: SequenceNumber, limit: Int)(implicit session: RSession): Seq[Collection] = super.getBySequenceNumber(num, limit)


}
