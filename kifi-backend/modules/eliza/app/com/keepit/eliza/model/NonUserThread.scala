package com.keepit.eliza.model

import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.model.{EContact, NormalizedURI}
import play.api.libs.json._
import com.keepit.common.mail.EmailAddressHolder
import com.keepit.social.{BasicNonUser, NonUserKinds, NonUserKind}
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.common.crypto.ModelWithPublicId

sealed trait NonUserParticipant {
  val identifier: String
  val referenceId: Option[String]
  val kind: NonUserKind

  override def toString() = identifier.toString

  def shortName: String
  def fullName: String
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

  def toBasicNonUser(nonUser: NonUserParticipant) = {
    // todo: Add other data, like econtact data
    BasicNonUser(kind = nonUser.kind, id = nonUser.identifier, firstName = Some(nonUser.identifier), lastName = None)
  }
}

case class NonUserEmailParticipant(address: EmailAddressHolder, econtactId: Option[Id[EContact]]) extends NonUserParticipant {
  val identifier = address.address
  val referenceId = econtactId.map(_.id.toString)
  val kind = NonUserKinds.email

  def shortName = identifier
  def fullName = identifier
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
  muted: Boolean = false,
  state: State[NonUserThread] = NonUserThreadStates.ACTIVE
) extends ModelWithState[NonUserThread] with ModelWithPublicId[NonUserThread] {
  def withId(id: Id[NonUserThread]): NonUserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withState(state: State[NonUserThread]) = copy(state = state)
}

object NonUserThread {
  implicit object nonUserThread extends ModelWithPublicId[NonUserThread] {
    val id = None
    override val prefix = "nu"
  }
}

object NonUserThreadStates extends States[NonUserThread]
