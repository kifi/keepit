package com.keepit.graph.model

import com.keepit.common.db.State
import com.keepit.model.{KeepToCollection, Collection, UserConnection, Bookmark}

trait EdgeData

sealed trait RealEdgeData[E] extends EdgeData {
  val state: State[E]
}

case class KeptData(state: State[Bookmark]) extends RealEdgeData[Bookmark]
case class FollowsData(state: State[UserConnection]) extends RealEdgeData[UserConnection]
case class CollectsData(state: State[Collection]) extends RealEdgeData[Collection]
case class ContainsData(state: State[KeepToCollection]) extends RealEdgeData[KeepToCollection]
