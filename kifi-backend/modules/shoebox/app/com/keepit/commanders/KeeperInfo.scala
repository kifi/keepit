package com.keepit.commanders

import com.keepit.social.BasicUser
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model._
import com.kifi.macros.json
import play.api.libs.json.{ __, JsObject, JsValue, Writes }
import play.api.libs.functional.syntax._

case class KeeperInfo(
  normalized: String,
  kept: Option[String],
  keepId: Option[ExternalId[Keep]],
  tags: Seq[SendableTag],
  position: Option[JsObject],
  neverOnSite: Boolean,
  shown: Boolean,
  keepers: Seq[BasicUser],
  keeps: Int)

object KeeperInfo {
  implicit val writes: Writes[KeeperInfo] = (
    (__ \ 'normalized).write[String] and
    (__ \ 'kept).writeNullable[String] and
    (__ \ 'keepId).writeNullable[ExternalId[Keep]] and
    (__ \ 'tags).writeNullable[Seq[SendableTag]].contramap[Seq[SendableTag]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'position).writeNullable[JsObject] and
    (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'shown).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'keepers).writeNullable[Seq[BasicUser]].contramap[Seq[BasicUser]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'keeps).writeNullable[Int].contramap[Int](Some(_).filter(_ > 0))
  )(unlift(KeeperInfo.unapply))
}

case class KeeperPageInfo(
  normalized: String,
  position: Option[JsObject],
  neverOnSite: Boolean,
  shown: Boolean,
  keepers: Seq[BasicUser],
  keepersTotal: Int,
  libraries: Seq[JsObject],
  keeps: Seq[KeepData],
  related: Seq[RelatedPageInfo])
object KeeperPageInfo {
  implicit val writes: Writes[KeeperPageInfo] = (
    (__ \ 'normalized).write[String] and
    (__ \ 'position).writeNullable[JsObject] and
    (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'shown).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'keepers).writeNullable[Seq[BasicUser]].contramap[Seq[BasicUser]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'keepersTotal).writeNullable[Int].contramap[Int](Some(_).filter(_ > 0)) and
    (__ \ 'libraries).writeNullable[Seq[JsObject]].contramap[Seq[JsObject]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'keeps).write[Seq[KeepData]] and
    (__ \ 'related).writeNullable[Seq[RelatedPageInfo]].contramap[Seq[RelatedPageInfo]](Some(_).filter(_.nonEmpty))
  )(unlift(KeeperPageInfo.unapply))
}

case class KeepData(
  id: ExternalId[Keep],
  mine: Boolean,
  removable: Boolean,
  secret: Boolean, // i.e. library.visibility == SECRET
  libraryId: PublicId[Library])
object KeepData {
  implicit val writes: Writes[KeepData] = (
    (__ \ 'id).write[ExternalId[Keep]] and
    (__ \ 'mine).write[Boolean] and
    (__ \ 'removable).write[Boolean] and
    (__ \ 'secret).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'libraryId).write[PublicId[Library]]
  )(unlift(KeepData.unapply))

  def apply(basicKeep: BasicKeep): KeepData = KeepData(basicKeep.id, basicKeep.mine, basicKeep.removable, basicKeep.visibility == LibraryVisibility.SECRET, basicKeep.libraryId)
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
        o.copy(note = Some(o.tags.map(t => '#' + t.replace(' ', '\u00a0')).mkString(" ")))
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
  path: String)
object LibraryData {
  implicit val writes: Writes[LibraryData] = (
    (__ \ 'id).write[PublicId[Library]] and
    (__ \ 'name).write[String] and
    (__ \ 'color).writeNullable[LibraryColor] and
    (__ \ 'visibility).write[LibraryVisibility] and
    (__ \ 'path).write[String]
  )(unlift(LibraryData.unapply))
}
