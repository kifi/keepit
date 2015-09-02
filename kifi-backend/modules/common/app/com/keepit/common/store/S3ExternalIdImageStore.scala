package com.keepit.common.store

import com.keepit.common.db.ExternalId
import com.keepit.common.net.URI
import com.keepit.model.User
import com.keepit.social.BasicUser

trait S3ExternalIdImageStore {

  def config: S3ImageConfig

  def avatarUrlByExternalId(w: Option[Int], userId: ExternalId[User], picName: String, protocolDefault: Option[String] = None): String = {
    val size = S3UserPictureConfig.ImageSizes.find(size => w.exists(size >= _)).map(_.toString).getOrElse(S3UserPictureConfig.OriginalImageSize)
    val uri = URI.parse(s"${config.cdnBase}/${keyByExternalId(size, userId, picName)}").get
    URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString
  }

  def avatarUrlByUser(user: User): String = avatarUrlByUser(BasicUser.fromUser(user))

  def avatarUrlByUser(user: BasicUser): String =
    avatarUrlByExternalId(Some(200), user.externalId, user.pictureName, Some("https"))

  def keyByExternalId(size: String, userId: ExternalId[User], picName: String): String = {
    val pic = if (picName.endsWith(".jpg")) picName else s"$picName.jpg"
    s"users/$userId/pics/$size/$pic"
  }

  def tempPath(token: String): String = {
    val pic = if (token.endsWith(".jpg")) token else s"$token.jpg"
    s"temp/user/pics/$pic"
  }

}
