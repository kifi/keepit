package com.keepit.bender

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.{StringMapperDelegate}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import org.joda.time.DateTime
import com.keepit.common.time.{currentDateTime, zones, Clock}
import com.keepit.common.db.{ModelWithExternalId, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import play.api.libs.json.{Json, JsValue, JsObject}
import scala.slick.lifted.{BaseTypeMapper, TypeMapperDelegate}
import scala.slick.driver.{BasicProfile}
import scala.util.hashing.MurmurHash3
import MessagingTypeMappers._

case class MessageThreadParticipants(initialParticipants: Set[Id[User]]) {
  var participants : Map[Id[User], DateTime] = initialParticipants.map{userId => (userId, currentDateTime(zones.PT))}.toMap


  def contains(user: Id[User]) : Boolean = participants.isDefinedAt(user)
  def allExcept(user: Id[User]) : Set[Id[User]] = participants.keySet - user

  def hash : Int = MurmurHash3.setHash(participants.keySet)

  override def equals(other: Any) : Boolean = other match {
    case mtps: MessageThreadParticipants => super.equals(other) || mtps.participants.keySet == participants.keySet 
    case _ => false
  }

  override def hashCode = participants.keySet.hashCode

}


case class MessageThread(
    id: Option[Id[MessageThread]],
    createdAt: DateTime = currentDateTime(zones.PT), 
    updateAt: DateTime = currentDateTime(zones.PT), 
    externalId: ExternalId[MessageThread] = ExternalId(),
    uriId: Option[Id[NormalizedURI]],
    url: Option[String],
    participants: Option[MessageThreadParticipants],
    participantsHash: Option[Int],
    replyable: Boolean
  ) 
  extends ModelWithExternalId[MessageThread] {

  def withId(id: Id[MessageThread]): MessageThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime) 

  def containsUser(user: Id[User]) : Boolean = participants.map(_.contains(user)).getOrElse(false)
  def allUsersExcept(user: Id[User]) : Set[Id[User]] = participants.map(_.allExcept(user)).getOrElse(Set[Id[User]]())
}

@ImplementedBy(classOf[MessageThreadRepoImpl])
trait MessageThreadRepo extends Repo[MessageThread] with ExternalIdColumnFunction[MessageThread] {

  def getOrCreate(participants: Set[Id[User]], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]])(implicit session: RWSession) : (MessageThread, Boolean)

  override def get(id: ExternalId[MessageThread])(implicit session: RSession) : MessageThread

  override def get(id: Id[MessageThread])(implicit session: RSession) : MessageThread
}


@Singleton
class MessageThreadRepoImpl @Inject() (
    val clock: Clock, 
    val db: DataBaseComponent 
  ) 
  extends DbRepo[MessageThread] with MessageThreadRepo with ExternalIdColumnDbFunction[MessageThread] {

  override val table = new RepoTable[MessageThread](db, "message_thread") with ExternalIdColumn[MessageThread] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def url = column[String]("url", O.Nullable)
    def participants = column[MessageThreadParticipants]("participants", O.Nullable)
    def participantsHash = column[Int]("participants_hash", O.Nullable)
    def replyable = column[Boolean]("replyable", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ uriId.? ~ url.? ~ participants.? ~ participantsHash.? ~ replyable <> (MessageThread.apply _, MessageThread.unapply _)
  }

  import db.Driver.Implicit._

  def getOrCreate(participants: Set[Id[User]], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]])(implicit session: RWSession) : (MessageThread, Boolean) = {
    //Note (stephen): This has a race condition: When two threads that would normally be merged are created at the exact same time two different conversations will be the result
    val mtps = MessageThreadParticipants(participants)
    val candidates : Seq[MessageThread]= (for (row <- table if row.participantsHash===mtps.hash && row.uriId===uriIdOpt) yield row).list.filter { thread =>
      thread.uriId.isDefined &&
      thread.participants.isDefined &&
      thread.participants.get == mtps
    }
    if (candidates.length>0) (candidates.head, false)
    else {
      val thread = MessageThread(
        id = None,
        uriId = uriIdOpt,
        url = urlOpt,
        participants = Some(mtps),
        participantsHash = Some(mtps.hash),
        replyable = true
      )
      (super.save(thread), true)
    }

  }

  override def get(id: ExternalId[MessageThread])(implicit session: RSession) : MessageThread = {
    (for (row <- table if row.externalId===id) yield row).first
  }

  override def get(id: Id[MessageThread])(implicit session: RSession) : MessageThread = {
    (for (row <- table if row.id===id) yield row).first
  }

}