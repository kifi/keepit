package com.keepit.common.store

import com.keepit.common.db.ExternalId
import com.keepit.model.User

object S3ImageConfig {
  val ImageSizes = Seq(100, 200)
}

case class S3ImageConfig(bucketName: String, cdnBase: String, isLocal: Boolean = false) {
  def avatarUrlByExternalId(w: Int, userId: ExternalId[User]): String = {
    val size = S3ImageConfig.ImageSizes.find(_ >= w).getOrElse(S3ImageConfig.ImageSizes.last)
    s"${cdnBase}/${keyByExternalId(size, userId)}"
  }

  def keyByExternalId(size: Int, userId: ExternalId[User]): String =
    s"users/$userId/pics/$size/0.jpg"
}
