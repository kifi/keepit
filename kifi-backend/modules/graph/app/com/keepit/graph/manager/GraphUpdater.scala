package com.keepit.graph.manager

import com.keepit.graph.model.{EmptyEdgeData, UserData, GraphWriter}
import com.google.inject.Inject
import com.keepit.model.UserConnectionStates

trait GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit
}

class GraphUpdaterImpl @Inject() () extends GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit = update match {
    case userGraphUpdate: UserGraphUpdate => writer.saveVertex(UserData(userGraphUpdate.userId))
    case userConnectionGraphUpdate: UserConnectionGraphUpdate => processUserConnectionGraphUpdate(userConnectionGraphUpdate)
  }

  private def processUserConnectionGraphUpdate(update: UserConnectionGraphUpdate)(implicit writer: GraphWriter) = update.state match {
    case UserConnectionStates.UNFRIENDED | UserConnectionStates.INACTIVE =>
      writer.removeEdge(update.firstUserId, update.secondUserId)
      writer.removeEdge(update.secondUserId, update.firstUserId)
    case UserConnectionStates.ACTIVE =>
      writer.saveVertex(UserData(update.firstUserId))
      writer.saveVertex(UserData(update.secondUserId))
      writer.saveEdge(update.firstUserId, update.secondUserId, EmptyEdgeData)
      writer.saveEdge(update.secondUserId, update.firstUserId, EmptyEdgeData)
  }
}
