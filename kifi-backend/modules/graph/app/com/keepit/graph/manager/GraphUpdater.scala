package com.keepit.graph.manager

import com.keepit.graph.model._
import com.google.inject.Inject
import com.keepit.model.UserConnectionStates
import com.keepit.social.SocialNetworks
import com.keepit.graph.model.UserData
import com.keepit.graph.model.FacebookAccountData

trait GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit
}

class GraphUpdaterImpl @Inject() () extends GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit = update match {
    case userGraphUpdate: UserGraphUpdate => writer.saveVertex(UserData(userGraphUpdate.userId))
    case userConnectionGraphUpdate: UserConnectionGraphUpdate => processUserConnectionGraphUpdate(userConnectionGraphUpdate)
    case socialUserInfoGraphUpdate: SocialUserInfoGraphUpdate => processSocialUserInfoGraphUpdate(socialUserInfoGraphUpdate)
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

  private def processSocialUserInfoGraphUpdate(update: SocialUserInfoGraphUpdate)(implicit writer: GraphWriter) = update.network match {
    case SocialNetworks.FACEBOOK => writer.saveVertex(FacebookAccountData(update.socialUserId))
    case SocialNetworks.LINKEDIN => writer.saveVertex(LinkedInAccountData(update.socialUserId))
    case _ => // ignore
  }
}
