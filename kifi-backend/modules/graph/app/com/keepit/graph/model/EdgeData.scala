package com.keepit.graph.model

import com.keepit.common.db.State
import com.keepit.model.{KeepToCollection, Collection, UserConnection, Bookmark}

sealed trait EdgeData {
  type DbType
  val state: State[DbType]
}

case class KeptData(state: State[Bookmark]) extends EdgeData { type DbType = Bookmark }
case class FollowsData(state: State[UserConnection]) extends { type DbType = UserConnection }
case class CollectsData(state: State[Collection]) extends { type DbType = Collection }
case class ContainsData(state: State[KeepToCollection]) extends { type DbType = KeepToCollection }
