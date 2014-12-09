package com.keepit.curator.model

import com.keepit.common.db.Id
import com.keepit.model.{ User, Library }
import com.kifi.macros.json

@json case class LibraryRecoInfo(
  userId: Id[User],
  libraryId: Id[Library],
  masterScore: Float,
  explain: String)

@json case class LibraryRecoSelectionParams(
  recencyScoreWeight: Float,
  interestScoreWeight: Float,
  sizeScoreWeight: Float,
  popularityScoreWeight: Float,
  socialScoreWeight: Float,
  minMembers: Int,
  minKeeps: Int,
  reset: Boolean = false)

object LibraryRecoSelectionParams {
  val default = LibraryRecoSelectionParams(
    recencyScoreWeight = 0.8f,
    interestScoreWeight = 1f,
    sizeScoreWeight = 0.7f,
    popularityScoreWeight = 0.8f,
    socialScoreWeight = 0.9f,
    minKeeps = 3,
    minMembers = 1,
    reset = false)
}
