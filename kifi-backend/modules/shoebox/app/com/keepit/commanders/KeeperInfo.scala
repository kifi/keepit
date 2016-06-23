package com.keepit.commanders

import com.keepit.common.store.ImagePath
import com.keepit.social.{ BasicUserWithUrlIntersection, BasicUser }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model._
import com.kifi.macros.json
import play.api.libs.json.{ __, JsObject, JsValue, Writes }
import play.api.libs.functional.syntax._

case class KeeperPageInfo(
  normalized: String,
  position: Option[JsObject],
  neverOnSite: Boolean,
  shown: Boolean,
  keepers: Seq[BasicUserWithUrlIntersection],
  keepersTotal: Int,
  libraries: Seq[JsObject],
  sources: Seq[SourceAttribution],
  keeps: Seq[KeepData])

object KeeperPageInfo {
  implicit val writes: Writes[KeeperPageInfo] = {
    implicit val sourceWrites = SourceAttribution.externalWrites
    (
      (__ \ 'normalized).write[String] and
      (__ \ 'position).writeNullable[JsObject] and
      (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'shown).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'keepers).writeNullable[Seq[BasicUserWithUrlIntersection]].contramap[Seq[BasicUserWithUrlIntersection]](Some(_).filter(_.nonEmpty)) and
      (__ \ 'keepersTotal).writeNullable[Int].contramap[Int](Some(_).filter(_ > 0)) and
      (__ \ 'libraries).writeNullable[Seq[JsObject]].contramap[Seq[JsObject]](Some(_).filter(_.nonEmpty)) and
      (__ \ 'sources).writeNullable[Seq[SourceAttribution]].contramap[Seq[SourceAttribution]](Some(_).filter(_.nonEmpty)) and
      (__ \ 'keeps).write[Seq[KeepData]]
    )(unlift(KeeperPageInfo.unapply))
  }
}

case class KeepData(
  id: ExternalId[Keep],
  mine: Boolean,
  removable: Boolean,
  secret: Boolean, // i.e. library.visibility == SECRET; please use `visibility` below instead
  visibility: LibraryVisibility,
  libraryId: Option[PublicId[Library]])
object KeepData {
  implicit val writes: Writes[KeepData] = (
    (__ \ 'id).write[ExternalId[Keep]] and
    (__ \ 'mine).write[Boolean] and
    (__ \ 'removable).write[Boolean] and
    (__ \ 'secret).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'visibility).write[LibraryVisibility] and
    (__ \ 'libraryId).writeNullable[PublicId[Library]]
  )(unlift(KeepData.unapply))

  def apply(personalKeep: PersonalKeep): KeepData = KeepData(
    personalKeep.id,
    personalKeep.mine,
    personalKeep.removable,
    personalKeep.visibility == LibraryVisibility.SECRET,
    personalKeep.visibility,
    personalKeep.libraryId
  )
}

// The extension uses this object to augment `KeepData` only when needed. It's useless by itself.
case class MoarKeepData(
  title: Option[String],
  image: Option[String],
  note: Option[String],
  tags: Seq[String])
object MoarKeepData {
  private val simpleWrites: Writes[MoarKeepData] = (
    (__ \ 'title).writeNullable[String] and
    (__ \ 'image).writeNullable[String] and
    (__ \ 'note).writeNullable[String] and
    (__ \ 'tags).writeNullable[Seq[String]].contramap[Seq[String]](Some(_).filter(_.nonEmpty))
  )(unlift(MoarKeepData.unapply))

  // temporarily auto-populating note field with tags if necessary
  implicit val writes = new Writes[MoarKeepData] {
    def writes(o: MoarKeepData): JsValue = {
      val o2 = if (o.tags.nonEmpty && o.note.isEmpty) {
        o.copy(note = Some(Hashtags.addTagsToString("", o.tags)))
      } else {
        o
      }
      simpleWrites.writes(o2)
    }
  }
}

case class LibraryData(
  id: PublicId[Library],
  name: String,
  color: Option[LibraryColor],
  visibility: LibraryVisibility,
  path: String,
  hasCollaborators: Boolean,
  subscribedToUpdates: Boolean, // deprecated, use membership.subscribed instead
  collaborators: Seq[BasicUser],
  orgAvatar: Option[ImagePath], //not all libs have orgs
  membership: Option[LibraryMembershipInfo])

object LibraryData {
  implicit val writes: Writes[LibraryData] = (
    (__ \ 'id).write[PublicId[Library]] and
    (__ \ 'name).write[String] and
    (__ \ 'color).writeNullable[LibraryColor] and
    (__ \ 'visibility).write[LibraryVisibility] and
    (__ \ 'path).write[String] and
    (__ \ 'hasCollaborators).write[Boolean] and
    (__ \ 'subscribedToUpdates).write[Boolean] and
    (__ \ 'collaborators).write[Seq[BasicUser]] and
    (__ \ 'orgAvatar).writeNullable[ImagePath] and
    (__ \ 'membership).writeNullable[LibraryMembershipInfo]
  )(unlift(LibraryData.unapply))
}
