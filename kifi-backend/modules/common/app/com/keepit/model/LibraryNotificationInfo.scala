package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.social.BasicUser
import com.kifi.macros.json

@json
case class LibraryNotificationInfo(
  id: PublicId[Library],
  name: String,
  slug: LibrarySlug,
  color: Option[LibraryColor],
  image: Option[LibraryImageInfo],
  owner: BasicUser)
