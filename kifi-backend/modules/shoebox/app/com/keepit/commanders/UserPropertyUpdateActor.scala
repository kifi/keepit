package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.{ ContextData, HeimdalContextBuilder, HeimdalServiceClient }
import com.keepit.model.{ CollectionRepo, KeepRepo, User, UserConnectionRepo }

abstract sealed class UserPropertyUpdateInstruction(val v: Int) {
  def has(other: UserPropertyUpdateInstruction): Boolean = (this.v & other.v) == other.v
  override def toString = s"UserPropertyUpdateInstruction($v)"
}

object UserPropertyUpdateInstruction {
  object ConnectionCount extends UserPropertyUpdateInstruction(1)
  object KeepCounts extends UserPropertyUpdateInstruction(2)
  object TagCount extends UserPropertyUpdateInstruction(4)
  object All extends UserPropertyUpdateInstruction(ConnectionCount.v | KeepCounts.v | TagCount.v)
}

class UserPropertyUpdateActor @Inject() (
    airbrake: AirbrakeNotifier,
    heimdalServiceClient: HeimdalServiceClient,
    keepRepo: KeepRepo,
    userConnectionRepo: UserConnectionRepo,
    collectionRepo: CollectionRepo,
    db: Database) extends FortyTwoActor(airbrake) {
  import UserPropertyUpdateInstruction._

  def receive: Receive = {
    case id: Id[_] => self ! (id, All)
    case (id: Id[_], instruction: UserPropertyUpdateInstruction) =>
      setUserProperties(Id[User](id.id), instruction)
  }

  def setUserProperties(userId: Id[User], instruction: UserPropertyUpdateInstruction): Map[String, ContextData] = db.readOnlyReplica(attempts = 2) { implicit session =>
    log.info(s"UserPropertyUpdateActor($userId, $instruction) called")

    val properties: Map[String, ContextData] = {
      val builder = new HeimdalContextBuilder()

      if (instruction.has(ConnectionCount)) {
        val connectionCount = userConnectionRepo.getConnectionCount(userId)
        builder += ("kifiConnections", connectionCount)
      }

      if (instruction.has(KeepCounts)) {
        val (privateKeeps, publicKeeps) = keepRepo.getPrivatePublicCountByUser(userId)
        builder += ("keeps", privateKeeps + publicKeeps)
        builder += ("publicKeeps", publicKeeps)
        builder += ("privateKeeps", privateKeeps)
      }

      if (instruction.has(TagCount)) {
        val tagsCount = collectionRepo.count(userId)
        builder += ("tags", tagsCount)
      }

      builder.build.data
    }

    if (properties.nonEmpty) {
      heimdalServiceClient.setUserProperties(userId, properties.toSeq: _*)
    } else {
      airbrake.notify(s"UserPropertyUpdateActor (userId=$userId instruction=$instruction) did not set any user properties")
    }

    properties
  }
}
