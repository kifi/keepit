package com.keepit.model

import com.keepit.common.db.Id
import com.kifi.macros.json

@json
case class LibraryCountData(totalCount: Int, countById: Map[Id[Library], Int]) {
  val sortedCountById = countById.toList.sortWith { _._2 > _._2 }
}

@json
case class KeepCountData(totalCount: Int, countById: Map[Id[Keep], Int]) {
  val sortedCountById = countById.toList.sortWith { _._2 > _._2 }
}

@json
case class GratificationData(
    userId: Id[User],
    libraryViews: LibraryCountData,
    keepViews: KeepCountData,
    rekeeps: KeepCountData) {
  import GratificationData._
  def isEligible = libraryViews.totalCount >= MIN_LIB_VIEWS || keepViews.totalCount >= MIN_KEEP_VIEWS || rekeeps.totalCount >= MIN_REKEEPS
}

object GratificationData {
  val MIN_KEEP_VIEWS = 5
  val MIN_LIB_VIEWS = 5
  val MIN_REKEEPS = 5
}