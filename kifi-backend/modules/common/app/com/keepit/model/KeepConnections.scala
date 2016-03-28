package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.util.DeltaSet
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.util.hashing.MurmurHash3

case class KeepConnections(
    libraries: Set[Id[Library]],
    emails: Set[EmailAddress],
    users: Set[Id[User]]) {
  def librariesHash = LibrariesHash(libraries)
  def participantsHash = ParticipantsHash(users)

  def withLibraries(libraries: Set[Id[Library]]) = this.copy(libraries = libraries)
  def withUsers(users: Set[Id[User]]) = this.copy(users = users)
  def withEmailAddresses(emailAddresses: Set[EmailAddress]) = this.copy(emails = emailAddresses)

  def plusUser(user: Id[User]) = this.withUsers(users + user)
  def plusEmailAddress(emailAddress: EmailAddress) = this.withEmailAddresses(emails + emailAddress)
  def plusLibrary(lib: Id[Library]) = this.withLibraries(libraries + lib)

  def union(that: KeepConnections): KeepConnections = KeepConnections(libraries = libraries ++ that.libraries, users = users ++ that.users, emails = emails ++ that.emails)
  def union(that: Option[KeepConnections]): KeepConnections = that.fold(this)(_ union this)

  def diffed(diff: KeepConnectionsDiff) = this.copy(
    users = users ++ diff.users.added -- diff.users.removed,
    emails = emails ++ diff.emails.added -- diff.emails.removed,
    libraries = libraries ++ diff.libraries.added -- diff.libraries.removed
  )
}
object KeepConnections {
  val EMPTY: KeepConnections = KeepConnections(libraries = Set.empty, users = Set.empty, emails = Set.empty)
  implicit val format: Format[KeepConnections] = (
    (__ \ 'libraries).formatNullable[Set[Id[Library]]].inmap[Set[Id[Library]]](_ getOrElse Set.empty, Some(_)) and
    (__ \ 'emails).formatNullable[Set[EmailAddress]].inmap[Set[EmailAddress]](_ getOrElse Set.empty, Some(_)) and
    (__ \ 'users).formatNullable[Set[Id[User]]].inmap[Set[Id[User]]](_ getOrElse Set.empty, Some(_))
  )(KeepConnections.apply, unlift(KeepConnections.unapply))
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
  implicit val writes = Json.writes[KeepMembers]
  val empty = KeepMembers(Seq.empty, Seq.empty, Seq.empty)
}

case class KeepConnectionsDiff(users: DeltaSet[Id[User]], libraries: DeltaSet[Id[Library]], emails: DeltaSet[EmailAddress])
object KeepConnectionsDiff {
  def addUser(user: Id[User]) = KeepConnectionsDiff(users = DeltaSet.empty.add(user), libraries = DeltaSet.empty, emails = DeltaSet.empty)
  def addUsers(users: Set[Id[User]]) = KeepConnectionsDiff(users = DeltaSet.empty.addAll(users), libraries = DeltaSet.empty, emails = DeltaSet.empty)
  def addLibrary(library: Id[Library]) = KeepConnectionsDiff(users = DeltaSet.empty, libraries = DeltaSet.empty.add(library), emails = DeltaSet.empty)
}
case class ExternalKeepConnectionsDiff(users: DeltaSet[ExternalId[User]], libraries: DeltaSet[PublicId[Library]], emails: DeltaSet[EmailAddress])
object ExternalKeepConnectionsDiff {
  implicit val reads: Reads[ExternalKeepConnectionsDiff] = (
    (__ \ 'users).readNullable[DeltaSet[ExternalId[User]]].map(_ getOrElse DeltaSet.empty) and
    (__ \ 'libraries).readNullable[DeltaSet[PublicId[Library]]].map(_ getOrElse DeltaSet.empty) and
    (__ \ 'emails).readNullable[DeltaSet[EmailAddress]].map(_ getOrElse DeltaSet.empty)
  )(ExternalKeepConnectionsDiff.apply _)
}
