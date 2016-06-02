package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json.SchemaReads
import com.keepit.common.mail.EmailAddress
import com.keepit.common.util.DeltaSet
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.util.hashing.MurmurHash3

case class KeepRecipients(
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

  def union(that: KeepRecipients): KeepRecipients = KeepRecipients(libraries = libraries ++ that.libraries, users = users ++ that.users, emails = emails ++ that.emails)
  def union(that: Option[KeepRecipients]): KeepRecipients = that.fold(this)(_ union this)

  def --(that: KeepRecipients) = KeepRecipientsDiff(
    users = DeltaSet.addOnly(users).removeAll(that.users),
    libraries = DeltaSet.addOnly(libraries).removeAll(that.libraries),
    emails = DeltaSet.addOnly(emails).removeAll(that.emails)
  )
  def diffed(diff: KeepRecipientsDiff) = this.copy(
    users = users ++ diff.users.added -- diff.users.removed,
    emails = emails ++ diff.emails.added -- diff.emails.removed,
    libraries = libraries ++ diff.libraries.added -- diff.libraries.removed
  )

  def toDiff: KeepRecipientsDiff = KeepRecipientsDiff(DeltaSet.addOnly(users), DeltaSet.addOnly(libraries), DeltaSet.addOnly(emails))

  def numLibraries: Int = libraries.size
  def numParticipants: Int = users.size + emails.size
}
object KeepRecipients {
  val EMPTY: KeepRecipients = KeepRecipients(libraries = Set.empty, users = Set.empty, emails = Set.empty)
  implicit val format: Format[KeepRecipients] = (
    (__ \ 'libraries).formatNullable[Set[Id[Library]]].inmap[Set[Id[Library]]](_ getOrElse Set.empty, Some(_)) and
    (__ \ 'emails).formatNullable[Set[EmailAddress]].inmap[Set[EmailAddress]](_ getOrElse Set.empty, Some(_)) and
    (__ \ 'users).formatNullable[Set[Id[User]]].inmap[Set[Id[User]]](_ getOrElse Set.empty, Some(_))
  )(KeepRecipients.apply, unlift(KeepRecipients.unapply))
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

case class KeepRecipientsDiff(users: DeltaSet[Id[User]], libraries: DeltaSet[Id[Library]], emails: DeltaSet[EmailAddress]) {
  def isEmpty = this.users.isEmpty && this.libraries.isEmpty && this.emails.isEmpty
  def nonEmpty = !isEmpty
  def allEntities = (users.all, libraries.all, emails.all)
  def onlyAdditions = KeepRecipientsDiff(
    users = DeltaSet.addOnly(users.added),
    libraries = DeltaSet.addOnly(libraries.added),
    emails = DeltaSet.addOnly(emails.added)
  )
  def plusUser(u: Id[User]): KeepRecipientsDiff = this.copy(users = users.add(u))
  def plusLibrary(l: Id[Library]): KeepRecipientsDiff = this.copy(libraries = libraries.add(l))
  def plusEmail(e: EmailAddress): KeepRecipientsDiff = this.copy(emails = emails.add(e))
  def minusUser(u: Id[User]): KeepRecipientsDiff = this.copy(users = users.remove(u))
  def minusLibrary(l: Id[Library]): KeepRecipientsDiff = this.copy(libraries = libraries.remove(l))
  def minusEmail(e: EmailAddress): KeepRecipientsDiff = this.copy(emails = emails.remove(e))
}
object KeepRecipientsDiff {
  def addUser(user: Id[User]) = KeepRecipientsDiff(users = DeltaSet.empty.add(user), libraries = DeltaSet.empty, emails = DeltaSet.empty)
  def addUsers(users: Set[Id[User]]) = KeepRecipientsDiff(users = DeltaSet.empty.addAll(users), libraries = DeltaSet.empty, emails = DeltaSet.empty)
  def addLibrary(library: Id[Library]) = KeepRecipientsDiff(users = DeltaSet.empty, libraries = DeltaSet.empty.add(library), emails = DeltaSet.empty)
  def addLibraries(libraries: Set[Id[Library]]) = KeepRecipientsDiff(users = DeltaSet.empty, libraries = DeltaSet.addOnly(libraries), emails = DeltaSet.empty)
  def empty = KeepRecipientsDiff(DeltaSet.empty, DeltaSet.empty, DeltaSet.empty)

  val internalFormat: Format[KeepRecipientsDiff] = (
    (__ \ 'users).formatNullable[DeltaSet[Id[User]]].inmap[DeltaSet[Id[User]]](_.getOrElse(DeltaSet.empty), Some(_).filter(users => users.added.nonEmpty || users.removed.nonEmpty)) and
    (__ \ 'libraries).formatNullable[DeltaSet[Id[Library]]].inmap[DeltaSet[Id[Library]]](_.getOrElse(DeltaSet.empty), Some(_).filter(libs => libs.added.nonEmpty || libs.removed.nonEmpty)) and
    (__ \ 'emails).formatNullable[DeltaSet[EmailAddress]].inmap[DeltaSet[EmailAddress]](_.getOrElse(DeltaSet.empty), Some(_).filter(emails => emails.added.nonEmpty || emails.removed.nonEmpty))
  )(KeepRecipientsDiff.apply, unlift(KeepRecipientsDiff.unapply))
}
case class ExternalKeepRecipientsDiff(users: DeltaSet[ExternalId[User]], libraries: DeltaSet[PublicId[Library]], emails: DeltaSet[EmailAddress], source: Option[KeepEventSource])
object ExternalKeepRecipientsDiff {
  import SchemaReads._

  // NB: weird story time
  /*
  The classes herein are entirely composed of nullable fields. Because of that, some really weird stuff can get through
  _.readNullable is pretty forgiving. If you remove the Reads.JsObjectReads below, JsNumber(10).as[ExternalKeepRecipientsDiff] will succeed
  It seems that the readNullable does not care if the top-level object even has the correct type.
  It probably checks each path individually
   */
  private implicit def deltasetSchemaReads[T](implicit srt: SchemaReads[T]): SchemaReads[DeltaSet[T]] = (
    (__ \ 'add).readNullableWithSchema[Set[T]] and
    (__ \ 'remove).readNullableWithSchema[Set[T]]
  )(DeltaSet.fromSets[T] _) keepAnd Reads.JsObjectReads
  val schemaReads: SchemaReads[ExternalKeepRecipientsDiff] = (
    (__ \ 'users).readNullableWithSchema[DeltaSet[ExternalId[User]]].map(_ getOrElse DeltaSet.empty) and
    (__ \ 'libraries).readNullableWithSchema[DeltaSet[PublicId[Library]]].map(_ getOrElse DeltaSet.empty) and
    (__ \ 'emails).readNullableWithSchema[DeltaSet[EmailAddress]].map(_ getOrElse DeltaSet.empty) and
    (__ \ 'source).readNullableWithSchema[KeepEventSource]
  )(ExternalKeepRecipientsDiff.apply _) keepAnd Reads.JsObjectReads
  implicit val reads = schemaReads.reads
}
