package com.keepit.model

import java.net.URLEncoder

import com.keepit.common.db.Id
import com.keepit.common.strings._
import com.kifi.macros.json

@json
case class Handle(value: String) extends AnyVal {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
  override def toString() = value
}

object Handle {
  implicit def fromUsername(username: Username) = Handle(username.value)
  implicit def fromOrganizationHandle(organizationHandle: OrganizationHandle) = Handle(organizationHandle.value)
}

sealed trait LibrarySpace // todo(Léo): *Currently* equivalent to HandleOwner, unsure whether they should be merged.

object LibrarySpace {
  case class UserSpace(id: Id[User]) extends LibrarySpace
  case class OrganizationSpace(id: Id[Organization]) extends LibrarySpace

  implicit def fromUserId(userId: Id[User]) = UserSpace(userId)
  implicit def fromOrganizationId(organizationId: Id[Organization]) = OrganizationSpace(organizationId)

  def apply(ownerId: Id[User], organizationId: Option[Id[Organization]]): LibrarySpace = organizationId.map(OrganizationSpace(_)) getOrElse UserSpace(ownerId)

  def prettyPrint(space: LibrarySpace): String = space match {
    case OrganizationSpace(orgId) => s"organization $orgId"
    case UserSpace(userId) => s"user $userId"
  }
}
