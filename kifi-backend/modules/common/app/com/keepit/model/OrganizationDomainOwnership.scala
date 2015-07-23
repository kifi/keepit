package com.keepit.model

import com.keepit.classify.Domain
import com.keepit.common.db._
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

object OrganizationDomainOwnershipStates extends States[OrganizationDomainOwnership]

case class OrganizationDomainOwnership(
    id: Option[Id[OrganizationDomainOwnership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationDomainOwnership] = OrganizationDomainOwnershipStates.ACTIVE,
    seq: SequenceNumber[OrganizationDomainOwnership] = SequenceNumber.ZERO,
    organizationId: Id[Organization],
    domainId: Id[Domain]) extends ModelWithSeqNumber[OrganizationDomainOwnership] with ModelWithState[OrganizationDomainOwnership] {

  override def withId(id: Id[OrganizationDomainOwnership]): OrganizationDomainOwnership = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): OrganizationDomainOwnership = copy(updatedAt = now)

  def toIngestableOrganizationDomainOwnership = IngestableOrganizationDomainOwnership(id.get, createdAt, state, seq, organizationId, domainId)

}

object OrganizationDomainOwnership {

  implicit val format = (
    (__ \ "id").formatNullable[Id[OrganizationDomainOwnership]] and
    (__ \ "createdAt").format[DateTime] and
    (__ \ "updatedAt").format[DateTime] and
    (__ \ "state").format[State[OrganizationDomainOwnership]] and
    (__ \ "seq").format[SequenceNumber[OrganizationDomainOwnership]] and
    (__ \ "organizationId").format[Id[Organization]] and
    (__ \ "domainId").format[Id[Domain]]
  )(OrganizationDomainOwnership.apply, unlift(OrganizationDomainOwnership.unapply))

}

@json
case class IngestableOrganizationDomainOwnership(
  id: Id[OrganizationDomainOwnership],
  createdAt: DateTime,
  state: State[OrganizationDomainOwnership],
  seq: SequenceNumber[OrganizationDomainOwnership],
  organizationId: Id[Organization],
  domainId: Id[Domain])
