package com.keepit.model

import java.net.URLEncoder

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json.EitherFormat
import com.keepit.common.strings._
import play.api.libs.functional.syntax._
import play.api.libs.json._
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

sealed trait LibrarySpace

object LibrarySpace {
  case class UserSpace(id: Id[User]) extends LibrarySpace
  case class OrganizationSpace(id: Id[Organization]) extends LibrarySpace

  implicit def fromUserId(userId: Id[User]): LibrarySpace = UserSpace(userId)
  implicit def fromOrganizationId(organizationId: Id[Organization]): LibrarySpace = OrganizationSpace(organizationId)

  def fromOptions(userIdOpt: Option[Id[User]], orgIdOpt: Option[Id[Organization]]): Option[LibrarySpace] = {
    (userIdOpt, orgIdOpt) match { // org-biased, to match apply behavior
      case (_, Some(orgId)) => Some(OrganizationSpace(orgId))
      case (Some(userId), _) => Some(UserSpace(userId))
      case _ => None
    }
  }
  def fromEither(either: Either[Id[User], Id[Organization]]): LibrarySpace = either match {
    case Left(userId) => UserSpace(userId)
    case Right(orgId) => OrganizationSpace(orgId)
  }
  def toEither(space: LibrarySpace): Either[Id[User], Id[Organization]] = space match {
    case UserSpace(userId) => Left(userId)
    case OrganizationSpace(orgId) => Right(orgId)
  }

  def apply(ownerId: Id[User], organizationId: Option[Id[Organization]]): LibrarySpace = organizationId.map(OrganizationSpace) getOrElse UserSpace(ownerId)

  def prettyPrint(space: LibrarySpace): String = space match {
    case OrganizationSpace(orgId) => s"organization $orgId"
    case UserSpace(userId) => s"user $userId"
  }

  private val eitherFormat = EitherFormat.keyedFormat[Id[User], Id[Organization]]("user", "org")
  implicit val format: Format[LibrarySpace] = eitherFormat.inmap(fromEither, toEither)
}

sealed trait ExternalLibrarySpace
object ExternalLibrarySpace {
  case class ExternalUserSpace(userId: ExternalId[User]) extends ExternalLibrarySpace
  case class ExternalOrganizationSpace(orgId: PublicId[Organization]) extends ExternalLibrarySpace

  def fromEither(either: Either[ExternalId[User], PublicId[Organization]]): ExternalLibrarySpace = either match {
    case Left(userId) => ExternalUserSpace(userId)
    case Right(orgId) => ExternalOrganizationSpace(orgId)
  }
  def toEither(space: ExternalLibrarySpace): Either[ExternalId[User], PublicId[Organization]] = space match {
    case ExternalUserSpace(userId) => Left(userId)
    case ExternalOrganizationSpace(orgId) => Right(orgId)
  }

  implicit def fromUserId(userId: ExternalId[User]): ExternalUserSpace = ExternalUserSpace(userId)
  implicit def fromOrganizationId(organizationId: PublicId[Organization]): ExternalOrganizationSpace = ExternalOrganizationSpace(organizationId)

  private val eitherFormat = EitherFormat.keyedFormat[ExternalId[User], PublicId[Organization]]("user", "org")
  implicit val format: Format[ExternalLibrarySpace] = eitherFormat.inmap(fromEither, toEither)
}
