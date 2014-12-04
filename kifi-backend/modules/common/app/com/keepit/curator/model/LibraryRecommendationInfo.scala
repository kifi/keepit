package com.keepit.curator.model

import com.keepit.common.db.Id
import com.keepit.model.{ User, Library }
import com.kifi.macros.json

@json case class LibraryRecoInfo(
  userId: Id[User],
  libraryId: Id[Library],
  masterScore: Float,
  explain: String)
