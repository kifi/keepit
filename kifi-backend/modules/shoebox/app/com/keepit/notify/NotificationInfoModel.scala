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
        Json.obj("image" -> Json.obj(
          "path" -> img.imagePath,
          "x" -> Json.toJson(img.positionX.getOrElse(30)),
          "y" -> Json.toJson(img.positionY.getOrElse(30))
        ))
      }

  def organization(org: Organization, image: Option[OrganizationAvatar])(implicit config: PublicIdConfiguration): JsObject =
    Json.obj(
      "id" -> Organization.publicId(org.id.get),
      "name" -> org.name,
      "handle" -> org.primaryHandle,
      "image" -> image.map(_.imagePath)
    )

}
