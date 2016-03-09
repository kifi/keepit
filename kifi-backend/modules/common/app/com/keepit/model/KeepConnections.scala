package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.util.hashing.MurmurHash3

case class KeepConnections(
    libraries: Set[Id[Library]],
    // TODO(ryan): add non-users in here
    users: Set[Id[User]]) {
  def librariesHash = LibrariesHash(libraries)
  def participantsHash = ParticipantsHash(users)

  def withLibraries(libraries: Set[Id[Library]]) = this.copy(libraries = libraries)
  def withUsers(users: Set[Id[User]]) = this.copy(users = users)

  def plusUser(user: Id[User]) = this.withUsers(users + user)
  def plusLibrary(lib: Id[Library]) = this.withLibraries(libraries + lib)
}
object KeepConnections {
  val EMPTY: KeepConnections = KeepConnections(libraries = Set.empty, users = Set.empty)
  implicit val format: Format[KeepConnections] = Json.format[KeepConnections]
}

case class LibrariesHash(value: Int) extends AnyVal
object LibrariesHash {
  def apply(libraries: Set[Id[Library]]): LibrariesHash = LibrariesHash(MurmurHash3.setHash(libraries))
  implicit val format: Format[LibrariesHash] = Format(Reads(_.validate[Int].map(LibrariesHash(_))), Writes(o => JsNumber(o.value)))
}

case class ParticipantsHash(value: Int) extends AnyVal
object ParticipantsHash {
  def apply(users: Set[Id[User]]): ParticipantsHash = ParticipantsHash(MurmurHash3.setHash(users))
  implicit val format: Format[ParticipantsHash] = Format(Reads(_.validate[Int].map(ParticipantsHash(_))), Writes(o => JsNumber(o.value)))
}

sealed trait KeepMember {
  def addedAt: DateTime
  def addedBy: Option[BasicUser]
}

object KeepMember {
  case class Library(library: LibraryCardInfo, addedAt: DateTime, addedBy: Option[BasicUser]) extends KeepMember
  case class User(user: BasicUser, addedAt: DateTime, addedBy: Option[BasicUser]) extends KeepMember
  case class Email(email: EmailAddress, addedAt: DateTime, addedBy: Option[BasicUser]) extends KeepMember

  implicit val libraryWrites = Json.writes[Library]
  implicit val userWrites = Json.writes[User]
  implicit val emailWrites = Json.writes[Email]
}

case class KeepMembers(libraries: Seq[KeepMember.Library], users: Seq[KeepMember.User], emails: Seq[KeepMember.Email])
object KeepMembers {
  implicit val writes: OWrites[KeepMembers] = OWrites { members =>
    Json.obj(
      "libraries" -> members.libraries,
      "users" -> members.users,
      "emails" -> members.emails
    )
  }
  val empty = KeepMembers(Seq.empty, Seq.empty, Seq.empty)
}