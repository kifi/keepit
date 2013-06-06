package com.keepit.common.store

import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.common.net.URI

object S3ImageConfig {
  val ImageSizes = Seq(100, 200)
}

case class S3ImageConfig(bucketName: String, cdnBase: String, isLocal: Boolean = false) {
  def avatarUrlByExternalId(w: Int, userId: ExternalId[User], protocolDefault: Option[String] = None): String = {
    val size = S3ImageConfig.ImageSizes.find(_ >= w).getOrElse(S3ImageConfig.ImageSizes.last)
    val uri = URI.parse(s"${cdnBase}/${keyByExternalId(size, userId)}").get
    URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString
  }

  def keyByExternalId(size: Int, userId: ExternalId[User]): String =
    s"users/$userId/pics/$size/0.jpg"
}
