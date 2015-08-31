package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.store.ImagePath
import play.api.libs.functional.syntax._
import play.api.libs.json._

// OrganizationCard should ONLY contain public information. No internal ids.
case class OrganizationCard(
  orgId: PublicId[Organization],
  ownerId: ExternalId[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  avatarPath: Option[ImagePath],
  numMembers: Int,
  numLibraries: Int)

object OrganizationCard {
  implicit val defaultFormat = (
    (__ \ 'id).format[PublicId[Organization]] and
    (__ \ 'ownerId).format[ExternalId[User]] and
    (__ \ 'handle).format[OrganizationHandle] and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'avatarPath).formatNullable[ImagePath] and
    (__ \ 'numMembers).format[Int] and
    (__ \ 'numLibraries).format[Int]
  )(OrganizationCard.apply, unlift(OrganizationCard.unapply))
}
