package com.keepit.notify

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json.{ Json, JsObject }

/**
 * Needed to work with clients as well as support the legacy notification system.
 */
object NotificationInfoModel {

  def library(lib: Library, image: Option[LibraryImage], owner: BasicUser)(implicit config: PublicIdConfiguration): JsObject =
    Json.obj(
      "id" -> Library.publicId(lib.id.get),
      "name" -> lib.name,
      "slug" -> lib.slug,
      "color" -> lib.color,
      "owner" -> owner
    ) ++ image.fold(Json.obj()) { img =>
        Json.obj("image" -> Json.toJson(LibraryImageInfo.fromImage(img)))
      }

  def organization(org: Organization, image: OrganizationAvatar)(implicit config: PublicIdConfiguration): JsObject =
    Json.obj(
      "id" -> Organization.publicId(org.id.get),
      "name" -> org.name,
      "handle" -> org.primaryHandle,
      "image" -> image.imagePath
    )

}
