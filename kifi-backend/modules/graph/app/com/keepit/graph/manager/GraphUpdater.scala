package com.keepit.graph.manager

import com.keepit.graph.model._
import com.google.inject.Inject
import com.keepit.model.{KeepStates, SocialConnectionStates, UserConnectionStates}
import com.keepit.social.SocialNetworks
import com.keepit.graph.model.UserData
import com.keepit.graph.model.FacebookAccountData
import com.keepit.cortex.models.lda.VersionedLDATopicId

trait GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit
}

class GraphUpdaterImpl @Inject() () extends GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit = update match {
    case userGraphUpdate: UserGraphUpdate => processUserGraphUpdate(userGraphUpdate)
    case userConnectionGraphUpdate: UserConnectionGraphUpdate => processUserConnectionGraphUpdate(userConnectionGraphUpdate)
    case socialUserInfoGraphUpdate: SocialUserInfoGraphUpdate => processSocialUserInfoGraphUpdate(socialUserInfoGraphUpdate)
    case socialConnectionGraphUpdate: SocialConnectionGraphUpdate => processSocialConnectionGraphUpdate(socialConnectionGraphUpdate)
    case keepGraphUpdate: KeepGraphUpdate => processKeepGraphUpdate(keepGraphUpdate)
    case ldaUpdate: LDAURITopicGraphUpdate => {/*processLDAUpdate(ldaUpdate)*/}
  }

  private def processUserGraphUpdate(update: UserGraphUpdate)(implicit writer: GraphWriter) = {
    writer.saveVertex(UserData(update.userId))
  }

  private def processUserConnectionGraphUpdate(update: UserConnectionGraphUpdate)(implicit writer: GraphWriter) = update.state match {
    case UserConnectionStates.UNFRIENDED | UserConnectionStates.INACTIVE =>
      writer.removeEdgeIfExists(update.firstUserId, update.secondUserId, EmptyEdgeDataReader)
      writer.removeEdgeIfExists(update.secondUserId, update.firstUserId, EmptyEdgeDataReader)
    case UserConnectionStates.ACTIVE =>
      writer.saveVertex(UserData(update.firstUserId))
      writer.saveVertex(UserData(update.secondUserId))
      writer.saveEdge(update.firstUserId, update.secondUserId, EmptyEdgeData)
      writer.saveEdge(update.secondUserId, update.firstUserId, EmptyEdgeData)
  }

  private def processSocialUserInfoGraphUpdate(update: SocialUserInfoGraphUpdate)(implicit writer: GraphWriter) = update.network match {
    case SocialNetworks.FACEBOOK =>
      val facebookAccountVertex = FacebookAccountData(update.socialUserId)
      writer.saveVertex(facebookAccountVertex)
      update.userId.foreach { userId =>
        val userVertex = UserData(userId)
        writer.saveVertex(userVertex)
        writer.saveEdge(facebookAccountVertex.id, userVertex.id, EmptyEdgeData)
        writer.saveEdge(userVertex.id, facebookAccountVertex.id, EmptyEdgeData)
      }

    case SocialNetworks.LINKEDIN =>
      val linkedInAccountVertex = LinkedInAccountData(update.socialUserId)
      writer.saveVertex(linkedInAccountVertex)
      update.userId.foreach { userId =>
        val userVertex = UserData(userId)
        writer.saveVertex(userVertex)
        writer.saveEdge(linkedInAccountVertex.id, userVertex.id, EmptyEdgeData)
        writer.saveEdge(userVertex.id, linkedInAccountVertex.id, EmptyEdgeData)
      }
    case _ => // ignore
  }

  private def processSocialConnectionGraphUpdate(update: SocialConnectionGraphUpdate)(implicit writer: GraphWriter) = update.network match {
    case SocialNetworks.FACEBOOK =>
      val firstFacebookUserVertexId: VertexDataId[FacebookAccountReader] = update.firstSocialUserId
      val secondFacebookUserVertexId: VertexDataId[FacebookAccountReader] = update.secondSocialUserId
      update.state match {
        case SocialConnectionStates.INACTIVE =>
          writer.removeEdgeIfExists(firstFacebookUserVertexId, secondFacebookUserVertexId, EmptyEdgeDataReader)
          writer.removeEdgeIfExists(secondFacebookUserVertexId, firstFacebookUserVertexId, EmptyEdgeDataReader)
        case SocialConnectionStates.ACTIVE =>
          writer.saveVertex(FacebookAccountData(firstFacebookUserVertexId))
          writer.saveVertex(FacebookAccountData(secondFacebookUserVertexId))
          writer.saveEdge(firstFacebookUserVertexId, secondFacebookUserVertexId, EmptyEdgeData)
          writer.saveEdge(secondFacebookUserVertexId, firstFacebookUserVertexId, EmptyEdgeData)
      }

    case SocialNetworks.LINKEDIN =>
      val firstLinkedInUserVertexId: VertexDataId[LinkedInAccountReader] = update.firstSocialUserId
      val secondLinkedInUserVertexId: VertexDataId[LinkedInAccountReader] = update.secondSocialUserId
      update.state match {
        case SocialConnectionStates.INACTIVE =>
          writer.removeEdgeIfExists(firstLinkedInUserVertexId, secondLinkedInUserVertexId, EmptyEdgeDataReader)
          writer.removeEdgeIfExists(secondLinkedInUserVertexId, firstLinkedInUserVertexId, EmptyEdgeDataReader)
        case SocialConnectionStates.ACTIVE =>
          writer.saveVertex(LinkedInAccountData(firstLinkedInUserVertexId))
          writer.saveVertex(LinkedInAccountData(secondLinkedInUserVertexId))
          writer.saveEdge(firstLinkedInUserVertexId, secondLinkedInUserVertexId, EmptyEdgeData)
          writer.saveEdge(secondLinkedInUserVertexId, firstLinkedInUserVertexId, EmptyEdgeData)
      }

    case _ => // ignore
  }

  private def processKeepGraphUpdate(update: KeepGraphUpdate)(implicit writer: GraphWriter) = {
    val keepVertexId: VertexDataId[KeepReader] =  update.id
    val uriVertexId: VertexDataId[UriReader] = update.uriId
    val userVertexId: VertexDataId[UserReader] = update.userId

    update.state match {
      case KeepStates.INACTIVE | KeepStates.DUPLICATE =>
        writer.removeVertexIfExists(keepVertexId)

      case KeepStates.ACTIVE =>
        writer.saveVertex(KeepData(keepVertexId))
        writer.saveVertex(UriData(uriVertexId))
        writer.saveVertex(UserData(userVertexId))

        writer.saveEdge(userVertexId, keepVertexId, EmptyEdgeData)
        writer.saveEdge(keepVertexId, uriVertexId, EmptyEdgeData)

        writer.saveEdge(keepVertexId, userVertexId, EmptyEdgeData)
        writer.saveEdge(uriVertexId, keepVertexId, EmptyEdgeData)
    }
  }

  private def processLDAUpdate(update: LDAURITopicGraphUpdate)(implicit writer: GraphWriter) = {

    def removeOldURITopicsIfExists(uriVertexId: VertexDataId[UriReader], numTopics: Int): Unit = {
      (0 until numTopics).foreach{ i =>
        val topicId = VersionedLDATopicId(update.uriSeq.version, i)
        writer.removeEdgeIfExists(uriVertexId, topicId, WeightedEdgeDataReader)
        writer.removeEdgeIfExists(topicId, uriVertexId, WeightedEdgeDataReader)
      }
    }

    val uriVertexId: VertexDataId[UriReader] = update.uriId
    removeOldURITopicsIfExists(uriVertexId, update.dimension)

    val uriData = UriData(uriVertexId)

    (update.topicScores zip update.topicIds) foreach { case (score, index) =>
      val topicId = VersionedLDATopicId(update.uriSeq.version, index)
      val topicVertexId: VertexDataId[LDATopicReader] = topicId
      writer.saveVertex(LDATopicData(topicVertexId))
      writer.saveVertex(uriData)
      writer.saveEdge(uriVertexId, topicVertexId, WeightedEdgeData(score))
      writer.saveEdge(topicVertexId, uriVertexId, WeightedEdgeData(score))
    }
  }
}
