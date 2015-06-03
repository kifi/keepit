package com.keepit.model

import java.net.URLEncoder

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.strings._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Organization(
    id: Option[Id[Organization]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[Organization] = OrganizationStates.ACTIVE,
    seq: SequenceNumber[Organization] = SequenceNumber.ZERO,
    name: String,
    description: Option[String] = None,
    ownerId: Id[User],
    slug: OrganizationSlug) extends ModelWithPublicId[Organization] with ModelWithState[Organization] with ModelWithSeqNumber[Organization] {

  override def withId(id: Id[Organization]): Organization = this.copy(id = Some(id))

  override def withUpdateTime(now: DateTime): Organization = this.copy(updatedAt = now)
}

object Organization {
  implicit val format: Format[Organization] = (
    (__ \ 'id).formatNullable[Id[Organization]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[Organization]) and
    (__ \ 'seq).format(SequenceNumber.format[Organization]) and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'ownerId).format(Id.format[User]) and
    (__ \ 'slug).format[OrganizationSlug]
  )(Organization.apply, unlift(Organization.unapply))
}

case class OrganizationSlug(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object OrganizationSlug {
  implicit def format: Format[OrganizationSlug] =
    Format(__.read[String].map(OrganizationSlug(_)), new Writes[OrganizationSlug] {
      def writes(o: OrganizationSlug) = JsString(o.value)
    })
}

object OrganizationStates extends States[Organization]
