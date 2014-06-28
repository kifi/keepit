package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.Some
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress

case class Contact(
                    id: Option[Id[Contact]] = None,
                    createdAt: DateTime = currentDateTime,
                    updatedAt: DateTime = currentDateTime,
                    userId: Id[User],
                    abookId: Id[ABookInfo],
                    email: EmailAddress,
                    altEmails: Option[String] = None,
                    origin: ABookOriginType,
                    name: Option[String] = None,
                    firstName: Option[String] = None,
                    lastName: Option[String] = None,
                    pictureUrl: Option[String] = None
                    ) extends Model[Contact] {
  def withId(id: Id[Contact]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  override def toString = s"Contact($id,$userId,$abookId,$email,$altEmails,$origin,$name,$firstName,$lastName)"
}

object Contact extends Logging {
  def newInstance(userId:Id[User], abookId:Id[ABookInfo], email:EmailAddress, altEmails:Seq[EmailAddress], origin:ABookOriginType, name:Option[String], firstName:Option[String], lastName:Option[String], pictureUrl:Option[String]):Contact = {
    if (altEmails.isEmpty) {
      new Contact(userId = userId, abookId = abookId, email = email, origin = origin, name = name, firstName = firstName, lastName = lastName, pictureUrl = pictureUrl)
    } else {
      new Contact(userId = userId, abookId = abookId, email = email, altEmails = Some(Json.stringify(Json.toJson(altEmails))), origin = origin, name = name, firstName = firstName, lastName = lastName, pictureUrl = pictureUrl)
    }
  }
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[Contact]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'userId).format(Id.format[User]) and
      (__ \ 'abookId).format(Id.format[ABookInfo]) and
      (__ \ 'email).format[EmailAddress] and
      (__ \ 'altEmails).formatNullable[String] and
      (__ \ 'origin).format[ABookOriginType] and
      (__ \ 'name).formatNullable[String] and
      (__ \ 'firstName).formatNullable[String] and
      (__ \ 'lastName).formatNullable[String] and
      (__ \ 'pictureUrl).formatNullable[String]
    )(Contact.apply, unlift(Contact.unapply))
}
