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
    rekeeps: KeepCountData,
    libraryFollows: LibraryCountData = LibraryCountData(0, Map.empty),
    connections: Seq[Id[User]] = Seq.empty) {
  import GratificationData._
  def isEligible = libraryViews.totalCount >= MIN_LIB_VIEWS || keepViews.totalCount >= MIN_KEEP_VIEWS ||
    rekeeps.totalCount >= MIN_REKEEPS || libraryFollows.totalCount >= MIN_FOLLOWS || connections.length >= MIN_CONNECTIONS
}

object GratificationData {
  val MIN_KEEP_VIEWS = 5
  val MIN_LIB_VIEWS = 5
  val MIN_REKEEPS = 1
  val MIN_FOLLOWS = 1
  val MIN_CONNECTIONS = 1
}
