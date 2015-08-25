package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.store.ImagePath
import com.keepit.social.BasicUser
import com.kifi.macros.json

// todo could be more generic
@json
case class LibraryImageInfo(path: ImagePath, x: Int, y: Int)

@json
case class LibraryNotificationInfo(
  id: PublicId[Library],
  name: String,
  slug: LibrarySlug,
  color: Option[LibraryColor],
  image: Option[LibraryImageInfo],
  owner: BasicUser)

@json
case class OrganizationNotificationInfo(
  id: PublicId[Organization],
  name: String,
  handle: Option[PrimaryOrganizationHandle],
  image: Option[ImagePath])
