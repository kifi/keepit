package com.keepit.model

import java.net.URLEncoder

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json.EitherFormat
import com.keepit.common.strings._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc.{ QueryStringBindable, PathBindable }

case class Handle(value: String) extends AnyVal {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
  override def toString = value
}

object Handle {
  implicit def fromUsername(username: Username): Handle = Handle(username.value)
  implicit def fromOrganizationHandle(organizationHandle: OrganizationHandle): Handle = Handle(organizationHandle.value)

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[Handle] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Handle]] = {
      stringBinder.bind(key, params) map {
        case Right(handle) => Right(Handle(handle))
        case _ => Left("Unable to bind an Handle")
      }
    }
    override def unbind(key: String, id: Handle): String = {
      stringBinder.unbind(key, id.value)
    }
  }

  implicit def pathBinder = new PathBindable[Handle] {
    override def bind(key: String, value: String): Either[String, Handle] = Right(Handle(value))
    override def unbind(key: String, handle: Handle): String = handle.value
  }

  implicit def format = Format(__.read[String].map(Handle(_)), new Writes[Handle] { def writes(o: Handle) = JsString(o.value) })
}

sealed trait LibrarySpace // todo(Léo): *Currently* equivalent to HandleOwner, unsure whether they should be merged.

object LibrarySpace {
  case class UserSpace(id: Id[User]) extends LibrarySpace
  case class OrganizationSpace(id: Id[Organization]) extends LibrarySpace

  implicit def fromUserId(userId: Id[User]): UserSpace = UserSpace(userId)
  implicit def fromOrganizationId(organizationId: Id[Organization]): OrganizationSpace = OrganizationSpace(organizationId)

  def apply(ownerId: Id[User], organizationId: Option[Id[Organization]]): LibrarySpace = organizationId.map(OrganizationSpace) getOrElse UserSpace(ownerId)

  def prettyPrint(space: LibrarySpace): String = space match {
    case OrganizationSpace(orgId) => s"organization $orgId"
    case UserSpace(userId) => s"user $userId"
  }
}

sealed trait ExternalLibrarySpace
object ExternalLibrarySpace {
  case class ExternalUserSpace(userId: ExternalId[User]) extends ExternalLibrarySpace
  case class ExternalOrganizationSpace(orgId: PublicId[Organization]) extends ExternalLibrarySpace

  implicit def fromUserId(userId: ExternalId[User]): ExternalUserSpace = ExternalUserSpace(userId)
  implicit def fromOrganizationId(organizationId: PublicId[Organization]): ExternalOrganizationSpace = ExternalOrganizationSpace(organizationId)

  implicit val reads: Reads[ExternalLibrarySpace] = Reads[ExternalLibrarySpace] { payload =>
    payload.validate[Either[ExternalId[User], PublicId[Organization]]](EitherFormat.keyedReads("user", "org")).map {
      case Left(userId) => ExternalUserSpace(userId)
      case Right(orgId) => ExternalOrganizationSpace(orgId)
    }
  }
}
