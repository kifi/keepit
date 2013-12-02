package com.keepit.eliza.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{StringMapperDelegate, Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{States, State, Model, Id}
import com.keepit.model.{EContact, User, NormalizedURI}
import play.api.libs.json._
import scala.slick.lifted.{BaseTypeMapper, Query}
import com.keepit.eliza._
import play.api.libs.functional.syntax._
import com.keepit.eliza.Notification
import play.api.libs.json.JsObject
import com.keepit.common.mail.{GenericEmailAddress, EmailAddressHolder}
import scala.slick.driver.BasicProfile
import MessagingTypeMappers._
import com.keepit.common.crypto.SimpleDESCrypt
import com.keepit.social.{NonUserKinds, NonUserKind, BasicUserLikeEntity}

sealed trait NonUserParticipant {
  val identifier: String
  val referenceId: Option[String]
  val kind: NonUserKind

  override def toString() = identifier.toString
}
object NonUserParticipant {
  implicit val format = new Format[NonUserParticipant] {
    // fields are shortened for overhead reasons
    def reads(json: JsValue) = {
      // k == "kind"
      // i == "identifier"
      // r == "referenceId"
      ((json \ "k").asOpt[String], (json \ "i").asOpt[String]) match {
        case (Some(NonUserKinds.email.name), Some(emailAddress)) =>
          val addr = GenericEmailAddress(emailAddress)
          val id = (json \ "r").asOpt[String].map(i => Id[EContact](i.toLong))
          JsSuccess(NonUserEmailParticipant(addr, id))
        case _ => JsError()
      }
    }
    def writes(p: NonUserParticipant): JsValue = {
      JsObject(Seq(Some("k" -> JsString(p.kind.name)), Some("i" -> JsString(p.identifier)), p.referenceId.map(r => "r" -> JsString(r))).flatten)
    }
  }
}

case class NonUserEmailParticipant(address: EmailAddressHolder, econtactId: Option[Id[EContact]]) extends NonUserParticipant {
  val identifier = address.address
  val referenceId = econtactId.map(_.id.toString)
  val kind = NonUserKinds.email
}

case class NonUserThread(
  id: Option[Id[NonUserThread]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  participant: NonUserParticipant,
  threadId: Id[MessageThread],
  uriId: Option[Id[NormalizedURI]],
  notifiedCount: Int,
  lastNotifiedAt: Option[DateTime],
  threadUpdatedAt: Option[DateTime],
  muted: Boolean,
  state: State[NonUserThread] = NonUserThreadStates.ACTIVE
) extends Model[NonUserThread] {
  def withId(id: Id[NonUserThread]): NonUserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withState(state: State[NonUserThread]) = copy(state = state)
}

object NonUserThreadStates extends States[NonUserThread]



@ImplementedBy(classOf[NonUserThreadRepoImpl])
trait NonUserThreadRepo extends Repo[NonUserThread] {

  def getThreadsByEmail(emailAddress: EmailAddressHolder)(implicit session: RSession): Seq[Id[MessageThread]]

  def getThreadsByEContactId(econtactId: Id[EContact])(implicit session: RSession): Seq[Id[MessageThread]]

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession): Seq[NonUserThread]

  def getByMessageThreadId(messageThreadId: Id[MessageThread])(implicit session: RSession): Seq[NonUserThread]

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Boolean

  def setMuteState(muteToken: String, muted: Boolean)(implicit session: RWSession): Boolean

}


@Singleton
class NonUserThreadRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent
  )
  extends DbRepo[NonUserThread] with NonUserThreadRepo with Logging {

  import db.Driver.Implicit._

  implicit object NonUserKindTypeMapper extends BaseTypeMapper[NonUserKind] {
    def apply(profile: BasicProfile) = new NonUserKindTypeMapperDelegate(profile)
  }
  class NonUserKindTypeMapperDelegate(profile: BasicProfile) extends StringMapperDelegate[NonUserKind](profile) {
    def zero = NonUserKind("")
    def sourceToDest(nonUserKind: NonUserKind) = NonUserKind.unapply(nonUserKind).get
    def safeDestToSource(str: String) = NonUserKind(str)
  }

  override val table = new RepoTable[NonUserThread](db, "non_user_thread") {

    def kind = column[NonUserKind]("kind", O.NotNull)
    def emailAddress = column[EmailAddressHolder]("email_address", O.Nullable)
    def econtactId = column[Id[EContact]]("econtact_id", O.Nullable)
    def threadId = column[Id[MessageThread]]("thread_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def notifiedCount = column[Int]("notified_count", O.NotNull)
    def lastNotifiedAt = column[DateTime]("last_notified_at", O.Nullable)
    def threadUpdatedAt = column[DateTime]("thread_updated_at", O.Nullable)
    def muted = column[Boolean]("muted", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ kind ~ emailAddress.? ~ econtactId.? ~ threadId ~ uriId.? ~ notifiedCount ~ lastNotifiedAt.? ~ threadUpdatedAt.? ~ muted ~ state <> (rowToObj _, objToRow _)

    private def rowToObj(id: Option[Id[NonUserThread]], createdAt: DateTime, updatedAt: DateTime, kind: NonUserKind, emailAddress: Option[EmailAddressHolder], econtactId: Option[Id[EContact]], threadId: Id[MessageThread], uriId: Option[Id[NormalizedURI]], notifiedCount: Int, lastNotifiedAt: Option[DateTime], threadUpdatedAt: Option[DateTime], muted: Boolean, state: State[NonUserThread]): NonUserThread = {
      val participant = kind match {
        case NonUserKinds.email =>
          NonUserEmailParticipant(emailAddress.get, econtactId)
      }
      NonUserThread(id, createdAt, updatedAt, participant, threadId, uriId, notifiedCount, lastNotifiedAt, threadUpdatedAt, muted, state)
    }

    private def objToRow(n: NonUserThread) = {
      val (kind, emailAddress, econtactId) = n.participant match {
        case ep: NonUserEmailParticipant =>
          (ep.kind, Option(ep.address), ep.econtactId)
      }
      Option((n.id, n.createdAt, n.updatedAt, kind, emailAddress, econtactId, n.threadId, n.uriId, n.notifiedCount, n.lastNotifiedAt, n.threadUpdatedAt, n.muted, n.state))
    }
  }

  def getThreadsByEmail(emailAddress: EmailAddressHolder)(implicit session: RSession): Seq[Id[MessageThread]] =
    (for (row <- table if row.emailAddress === emailAddress) yield row.threadId).list

  def getThreadsByEContactId(econtactId: Id[EContact])(implicit session: RSession): Seq[Id[MessageThread]] =
    (for (row <- table if row.econtactId === econtactId) yield row.threadId).list

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession): Seq[NonUserThread] =
    (for (row <- table if (row.lastNotifiedAt.isNull || row.lastNotifiedAt < row.threadUpdatedAt) && row.lastNotifiedAt < before) yield row).list

  def getByMessageThreadId(messageThreadId: Id[MessageThread])(implicit session: RSession): Seq[NonUserThread] =
    (for (row <- table if row.threadId === messageThreadId) yield row).list

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit =
    updates.foreach{ case (oldId, newId) =>
      (for (row <- table if row.uriId === oldId) yield row.uriId).update(newId)
    }

  def setMuteState(muteToken: String, muted: Boolean)(implicit session: RWSession): Boolean =
    muteTokenToNonUserThreadId(muteToken).map { id =>
      setMuteState(id, muted)
    }.getOrElse(false)

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Boolean =
    (for (row <- table if row.id === nonUserThreadId) yield row.muted).update(muted) > 0

  val crypt = new SimpleDESCrypt()
  val muteKey = crypt.stringToKey("non user thread id to muteKey key, word.")
  def nonUserThreadIdToMuteToken(id: Id[NonUserThread]) = {
    val encText = { // DES is a block cypher, so we'd prefer all blocks to be different for similar IDs
      val _id = id.id.toString
      _id.reverse.take(4) + " " + (" " * ((12 - _id.length) / 2) + _id).padTo(11, " ").mkString("")
    }
    crypt.crypt(muteKey, encText)
  }
  def muteTokenToNonUserThreadId(muteToken: String): Option[Id[NonUserThread]] = {
    val decText = crypt.decrypt(muteKey, muteToken)
    decText.map { i =>
      Id[NonUserThread](i.trim.split(" ").tail.dropWhile(_.length == 0).head.toLong)
    }.toOption
  }

}
