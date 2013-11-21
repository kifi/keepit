package com.keepit.eliza.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{Model, Id}
import com.keepit.model.{EContact, User, NormalizedURI}
import play.api.libs.json._
import scala.slick.lifted.Query
import com.keepit.eliza._
import play.api.libs.functional.syntax._
import scala.Some
import com.keepit.eliza.Notification
import play.api.libs.json.JsObject
import com.keepit.common.mail.EmailAddressHolder

case class NonUserKind(name: String)

case class NonUserThread(
  id: Option[Id[NonUserThread]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  kind: NonUserKind,
  emailAddress: Option[EmailAddressHolder],
  econtactId: Option[Id[EContact]],
  threadId: Id[MessageThread],
  uriId: Option[Id[NormalizedURI]],
  notifiedCount: Int,
  lastNotifiedAt: Option[DateTime],
  threadUpdatedAt: Option[DateTime],
  muted: Boolean
) extends Model[NonUserThread] {
  def withId(id: Id[NonUserThread]): NonUserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime)
}

case class BasicNonUser(`type`: NonUserKind, id: String, firstName: Option[String], lastName: Option[String])

object BasicNonUser {
  implicit val nonUserTypeFormat = Json.format[NonUserKind]
  implicit val basicNonUserFormat = (
    (__ \ 'type).format[NonUserKind] and
      (__ \ 'id).format[String] and
      (__ \ 'firstName).formatNullable[String] and
      (__ \ 'lastName).formatNullable[String]
    )(BasicNonUser.apply, unlift(BasicNonUser.unapply))

}


@ImplementedBy(classOf[NonUserThreadRepoImpl])
trait NonUserThreadRepo extends Repo[NonUserThread] {

  def getThreadsByEmail(emailAddress: EmailAddressHolder)(implicit session: RSession): Seq[Id[MessageThread]]

  def getThreadsByEContactId(econtactId: Id[EContact])(implicit session: RSession): Seq[Id[MessageThread]]

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession): Seq[NonUserThread]

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Int

}


@Singleton
class NonUserThreadRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent
  )
  extends DbRepo[NonUserThread] with NonUserThreadRepo with Logging {

  import db.Driver.Implicit._

  override val table = new RepoTable[NonUserThread](db, "user_thread") {

    def kind = column[NonUserKind]("kind", O.NotNull)
    def emailAddress = column[EmailAddressHolder]("email_address", O.Nullable)
    def econtactId = column[Id[EContact]]("econtact_id", O.Nullable)
    def threadId = column[Id[MessageThread]]("thread_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def notifiedCount = column[Int]("notified_count", O.NotNull)
    def lastNotified = column[DateTime]("last_notified", O.Nullable)
    def muted = column[Boolean]("muted", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ kind ~ emailAddress.? ~ econtactId.? ~ threadId ~ uriId.? ~ notifiedCount ~ lastNotified.? ~ muted <> (NonUserThread.apply _, NonUserThread.unapply _)

  }

  def getThreadsByEmail(emailAddress: EmailAddressHolder)(implicit session: RSession): Seq[Id[MessageThread]] =
    (for (row <- table if row.emailAddress === emailAddress) yield row.threadId).list

  def getThreadsByEContactId(econtactId: Id[EContact])(implicit session: RSession): Seq[Id[MessageThread]] =
    (for (row <- table if row.econtactId === econtactId) yield row.threadId).list

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession): Seq[NonUserThread] =
    (for (row <- table if row.notificationPending===true && row.notificationEmailed===false && (row.notificationLastSeen.isNull || row.notificationLastSeen < row.notificationUpdatedAt) && row.notificationUpdatedAt < before) yield row).list

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit =
    updates.foreach{ case (oldId, newId) =>
      (for (row <- table if row.uriId===oldId) yield row.uriId).update(newId)
    }

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Boolean =
    (for (row <- table if row.id === nonUserThreadId) yield row.muted).update(muted) > 0

}
