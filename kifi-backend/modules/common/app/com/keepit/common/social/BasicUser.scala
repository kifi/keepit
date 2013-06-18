package com.keepit.common.social


import com.keepit.common.db._
import com.keepit.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class BasicUser(
  externalId: ExternalId[User],
  firstName: String,
  lastName: String,
  networkIds: Map[SocialNetworkType, SocialId],
  pictureName: String)

object BasicUser {
  implicit val userExternalIdFormat = ExternalId.format[User]
  implicit val basicUserFormat = (
      (__ \ 'id).format[ExternalId[User]] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'networkIds).format[Map[String, String]].inmap[Map[SocialNetworkType,SocialId]](
        _.map { case (netStr, idStr) => SocialNetworkType(netStr) -> SocialId(idStr) }.toMap,
        _.map { case (network, id) => network.name -> id.id }.toMap
      ) and
      (__ \ 'pictureName).format[String]
  )(BasicUser.apply, unlift(BasicUser.unapply))
}
