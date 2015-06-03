package com.keepit.model

import java.net.URLEncoder

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.strings._
import com.keepit.common.time._
import com.kifi.macros.json
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
    handle: Option[PrimaryOrganizationHandle]) extends ModelWithPublicId[Organization] with ModelWithState[Organization] with ModelWithSeqNumber[Organization] {

  override def withId(id: Id[Organization]): Organization = this.copy(id = Some(id))

  override def withUpdateTime(now: DateTime): Organization = this.copy(updatedAt = now)
}

object Organization {
  implicit val primaryHandleFormat = PrimaryOrganizationHandle.jsonAnnotationFormat

  implicit val format: Format[Organization] = (
    (__ \ 'id).formatNullable[Id[Organization]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[Organization]) and
    (__ \ 'seq).format(SequenceNumber.format[Organization]) and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'ownerId).format(Id.format[User]) and
    (__ \ 'handle).formatNullable[PrimaryOrganizationHandle]
  )(Organization.apply, unlift(Organization.unapply))
}

object OrganizationStates extends States[Organization]

@json
case class OrganizationHandle(value: String) extends AnyVal {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

@json
case class PrimaryOrganizationHandle(original: OrganizationHandle, normalized: OrganizationHandle)
