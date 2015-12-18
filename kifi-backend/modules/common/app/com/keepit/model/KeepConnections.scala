package com.keepit.model

import com.keepit.common.db.Id
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
