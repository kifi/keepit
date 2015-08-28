package com.keepit.notify.info

import com.keepit.common.crypto.PublicId
import com.keepit.common.path.Path
import com.keepit.common.store.ImagePath
import com.keepit.model._
import com.keepit.social.BasicUser
import com.kifi.macros.json

@json
case class LibraryImageInfo(path: ImagePath, x: Int, y: Int)

@json
case class UserNotificationInfo(
  user: BasicUser,
  path: Path,
  imageUrl: String)

@json
case class KeepNotificationInfo(
  keeper: UserNotificationInfo,
  keep: Keep)

@json
case class LibraryNotificationInfo(
  id: PublicId[Library],
  name: String,
  slug: LibrarySlug,
  color: Option[LibraryColor],
  image: Option[LibraryImageInfo],
  path: Path,
  owner: UserNotificationInfo)

@json
case class OrganizationNotificationInfo(
  id: PublicId[Organization],
  name: String,
  abbreviatedName: String,
  handle: Option[PrimaryOrganizationHandle],
  image: Option[ImagePath],
  path: Path,
  owner: UserNotificationInfo)
