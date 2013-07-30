package com.keepit.bender

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import org.joda.time.DateTime
import com.keepit.common.time.{currentDateTime, zones, Clock}
import com.keepit.common.db.{ModelWithExternalId, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import MessagingTypeMappers._

case class Message(
    id: Option[Id[Message]],
    createdAt: DateTime = currentDateTime(zones.PT), 
    updatedAt: DateTime = currentDateTime(zones.PT), 
    externalId: ExternalId[Message] = ExternalId(),
    from: Option[Id[User]],
    thread: Id[MessageThread],
    messageText: String,
    sentOnUrl: Option[String],
    sentOnUriId: Option[Id[NormalizedURI]]
  ) 
  extends ModelWithExternalId[Message] {

  def withId(id: Id[Message]): Message = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt=updateTime) 
}






@ImplementedBy(classOf[MessageRepoImpl])
trait MessageRepo extends Repo[Message] with ExternalIdColumnFunction[Message] {

  def create(from: Id[User], thread: MessageThread, messageText: String, urlOpt: Option[String])(implicit session: RWSession) : Message

  def updateUriId(message: Message, uriId: Id[NormalizedURI])(implicit session: RWSession) : Unit

  def get(thread: Id[MessageThread], from: Int, to: Option[Int])(implicit session: RSession)  : Seq[Message]

}


@Singleton
class MessageRepoImpl @Inject() (
    val clock: Clock, 
    val db: DataBaseComponent 
  ) 
  extends DbRepo[Message] with MessageRepo with ExternalIdColumnDbFunction[Message] {

  import db.Driver.Implicit._

  override val table = new RepoTable[Message](db, "message") with ExternalIdColumn[Message] {
    def from = column[Id[User]]("sender", O.Nullable)
    def thread = column[Id[MessageThread]]("thread", O.NotNull)
    def messageText = column[String]("message_text", O.NotNull)
    def sentOnUrl = column[String]("sent_on_url", O.Nullable)
    def sentOnUriId = column[Id[NormalizedURI]]("sent_on_uri_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ from.? ~ thread ~ messageText ~ sentOnUrl.? ~ sentOnUriId.? <> (Message.apply _, Message.unapply _)
  }


  def create(from: Id[User], thread: MessageThread, messageText: String, urlOpt: Option[String])(implicit session: RWSession) : Message = {
    val msg = Message(
      id = None,
      from = Some(from),
      thread = thread.id.get,
      messageText = messageText,
      sentOnUrl = urlOpt,
      sentOnUriId = None
    )
    save(msg)
  }

  def updateUriId(message: Message, uriId: Id[NormalizedURI])(implicit session: RWSession) : Unit = { //TODO Stephen: Make this an update
    message.copy(sentOnUriId=Some(uriId))
    save(message)
  }


  def get(thread: Id[MessageThread], from: Int, to: Option[Int])(implicit session: RSession) : Seq[Message] = {
    val query = (for (row <- table if row.thread === thread) yield row).drop(from)
    to match {
      case Some(upper) => query.take(upper-from).list
      case None => query.list
    }
  }


}




