package com.keepit.commanders

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model._
import com.keepit.social.BasicUser

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class KeeperInfo(
  normalized: String,
  kept: Option[String],
  keepId: Option[ExternalId[Keep]],
  tags: Seq[SendableTag],
  position: Option[JsObject],
  neverOnSite: Boolean,
  sensitive: Boolean,
  shown: Boolean,
  keepers: Seq[BasicUser],
  keeps: Int)

object KeeperInfo {
  implicit val writesKeeperInfo = (
    (__ \ 'normalized).write[String] and
    (__ \ 'kept).writeNullable[String] and
    (__ \ 'keepId).writeNullable[ExternalId[Keep]] and
    (__ \ 'tags).writeNullable[Seq[SendableTag]].contramap[Seq[SendableTag]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'position).writeNullable[JsObject] and
    (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'sensitive).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'shown).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'keepers).writeNullable[Seq[BasicUser]].contramap[Seq[BasicUser]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'keeps).writeNullable[Int].contramap[Int](Some(_).filter(_ > 0))
  )(unlift(KeeperInfo.unapply))
}

case class PageInfo(
  normalized: String,
  position: Option[JsObject],
  neverOnSite: Boolean,
  sensitive: Boolean,
  shown: Boolean,
  keepers: Seq[BasicUser],
  keeps: Seq[KeepData])

object PageInfo {
  implicit val writesPageInfo = (
    (__ \ 'normalized).write[String] and
    (__ \ 'position).writeNullable[JsObject] and
    (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'sensitive).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'shown).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'keepers).writeNullable[Seq[BasicUser]].contramap[Seq[BasicUser]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'keeps).write[Seq[KeepData]]
  )(unlift(PageInfo.unapply))
}

case class KeepData(
  id: ExternalId[Keep],
  mine: Boolean,
  removable: Boolean,
  library: LibraryData)
object KeepData {
  val keepReads: Reads[KeepData] = (
    (JsPath \ "id").read[ExternalId[Keep]] and
    (JsPath \ "mine").read[Boolean] and
    (JsPath \ "removable").read[Boolean] and
    (JsPath \ "library").read[LibraryData]
  )(KeepData.apply _)

  val keepWrites: Writes[KeepData] = (
    (JsPath \ "id").write[ExternalId[Keep]] and
    (JsPath \ "mine").write[Boolean] and
    (JsPath \ "removable").write[Boolean] and
    (JsPath \ "library").write[LibraryData]
  )(unlift(KeepData.unapply))

  implicit val keepDataFormat: Format[KeepData] =
    Format(keepReads, keepWrites)
}

case class LibraryData(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  url: String)

object LibraryData {

  val libReads: Reads[LibraryData] = (
    (JsPath \ "id").read[PublicId[Library]] and
    (JsPath \ "name").read[String] and
    (JsPath \ "visibility").read[LibraryVisibility] and
    (JsPath \ "url").read[String]
  )(LibraryData.apply _)

  val libWrites: Writes[LibraryData] = (
    (JsPath \ "id").write[PublicId[Library]] and
    (JsPath \ "name").write[String] and
    (JsPath \ "visibility").write[LibraryVisibility] and
    (JsPath \ "url").write[String]
  )(unlift(LibraryData.unapply))

  implicit val libDataFormat: Format[LibraryData] =
    Format(libReads, libWrites)
}
