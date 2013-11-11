package com.keepit.commanders

import com.keepit.model._
import com.keepit.social._
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class KeeperInfo(
  normalized: String,
  kept: Option[String],
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
    (__ \ 'tags).writeNullable[Seq[SendableTag]].contramap[Seq[SendableTag]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'position).writeNullable[JsObject] and
    (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'sensitive).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'shown).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'keepers).writeNullable[Seq[BasicUser]].contramap[Seq[BasicUser]](Some(_).filter(_.nonEmpty)) and
    (__ \ 'keeps).writeNullable[Int].contramap[Int](Some(_).filter(_ > 0))
  )(unlift(KeeperInfo.unapply))
}
