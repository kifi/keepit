package com.keepit.common.store

import com.keepit.common.db.ExternalId
import com.keepit.model.User

object S3ImageConfig {
  val ImageSizes = Seq(100, 200)
}

case class S3ImageConfig(bucketName: String, cloudfrontHost: String) {
  def avatarUrlByExternalId(w: Int, userId: ExternalId[User]): String = {
    val size = S3ImageConfig.ImageSizes.find(_ >= w).getOrElse(S3ImageConfig.ImageSizes.last)
    s"//${cloudfrontHost}/${keyByExternalId(size, userId)}"
  }

  def keyByExternalId(width: Int, userId: ExternalId[User]): String =
    s"${userId}_${width}x${width}.jpg"

}
