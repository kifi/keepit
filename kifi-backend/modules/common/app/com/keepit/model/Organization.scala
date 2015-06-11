package com.keepit.model

import java.net.URLEncoder
import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ ModelWithPublicIdCompanion, ModelWithPublicId }
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

object Organization extends ModelWithPublicIdCompanion[Organization] {
  implicit val primaryHandleFormat = PrimaryOrganizationHandle.jsonAnnotationFormat

  protected val publicIdPrefix = "o"
  protected val publicIdIvSpec = new IvParameterSpec(Array(62, 91, 74, 34, 82, -77, 19, -35, -118, 3, 112, -59, -70, 94, 101, -115))

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

  def applyFromDbRow(
    id: Option[Id[Organization]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[Organization],
    seq: SequenceNumber[Organization],
    name: String,
    description: Option[String],
    ownerId: Id[User],
    organizationHandle: Option[OrganizationHandle],
    normalizedOrganizationHandle: Option[OrganizationHandle]) = {
    val primaryOrganizationHandle = for {
      original <- organizationHandle
      normalized <- normalizedOrganizationHandle
    } yield PrimaryOrganizationHandle(original, normalized)
    Organization(id, createdAt, updatedAt, state, seq, name, description, ownerId, primaryOrganizationHandle)
  }

  def unapplyToDbRow(org: Organization) = {
    Some((org.id,
      org.createdAt,
      org.updatedAt,
      org.state,
      org.seq,
      org.name,
      org.description,
      org.ownerId,
      org.handle.map(_.original),
      org.handle.map(_.normalized)))
  }
}

object OrganizationStates extends States[Organization]

@json
case class OrganizationHandle(value: String) extends AnyVal {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

@json
case class PrimaryOrganizationHandle(original: OrganizationHandle, normalized: OrganizationHandle)
