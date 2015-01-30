package com.keepit.graph.manager

import com.keepit.graph.model._
import com.google.inject.Inject
import com.keepit.model._
import com.keepit.social.SocialNetworks
import com.keepit.cortex.models.lda.LDATopic

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
    case ldaUpdate: SparseLDAGraphUpdate => processLDAUpdate(ldaUpdate)
    case ldaCleanOldVersion: LDAOldVersionCleanupGraphUpdate => processLDACleanup(ldaCleanOldVersion)
    case uriUpdate: NormalizedUriGraphUpdate => processNormalizedUriGraphUpdate(uriUpdate)
    case emailAccountUpdate: EmailAccountGraphUpdate => processEmailAccountGraphUpdate(emailAccountUpdate)
    case emailContactUpdate: EmailContactGraphUpdate => processEmailContactGraphUpdate(emailContactUpdate)
    case libUpdate: LibraryGraphUpdate => processLibraryGraphUpdate(libUpdate)
    case libMemUpdate: LibraryMembershipGraphUpdate => processLibraryMembershipGraphUpdate(libMemUpdate)
  }

  private def processUserGraphUpdate(update: UserGraphUpdate)(implicit writer: GraphWriter) = update.state match {
    case UserStates.ACTIVE => writer.saveVertex(UserData(update.userId))
    case _ => writer.removeVertexIfExists(update.userId)
  }

  private def processUserConnectionGraphUpdate(update: UserConnectionGraphUpdate)(implicit writer: GraphWriter) = update.state match {
    case UserConnectionStates.UNFRIENDED | UserConnectionStates.INACTIVE =>
      writer.removeEdgeIfExists(update.firstUserId, update.secondUserId, EmptyEdgeReader)
      writer.removeEdgeIfExists(update.secondUserId, update.firstUserId, EmptyEdgeReader)
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
          writer.removeEdgeIfExists(firstFacebookUserVertexId, secondFacebookUserVertexId, EmptyEdgeReader)
          writer.removeEdgeIfExists(secondFacebookUserVertexId, firstFacebookUserVertexId, EmptyEdgeReader)
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
          writer.removeEdgeIfExists(firstLinkedInUserVertexId, secondLinkedInUserVertexId, EmptyEdgeReader)
          writer.removeEdgeIfExists(secondLinkedInUserVertexId, firstLinkedInUserVertexId, EmptyEdgeReader)
        case SocialConnectionStates.ACTIVE =>
          writer.saveVertex(LinkedInAccountData(firstLinkedInUserVertexId))
          writer.saveVertex(LinkedInAccountData(secondLinkedInUserVertexId))
          writer.saveEdge(firstLinkedInUserVertexId, secondLinkedInUserVertexId, EmptyEdgeData)
          writer.saveEdge(secondLinkedInUserVertexId, firstLinkedInUserVertexId, EmptyEdgeData)
      }

    case _ => // ignore
  }

  private def processKeepGraphUpdate(update: KeepGraphUpdate)(implicit writer: GraphWriter) = {
    val keepVertexId: VertexDataId[KeepReader] = update.id
    val uriVertexId: VertexDataId[UriReader] = update.uriId
    val userVertexId: VertexDataId[UserReader] = update.userId
    val libVertexId: VertexDataId[LibraryReader] = update.libId

    writer.removeVertexIfExists(keepVertexId) // build the vertex and its neighbors from empty state. (e.g. LibraryId can change, this removes old links)

    update.state match {

      case KeepStates.ACTIVE if update.source != KeepSource.default =>
        writer.saveVertex(KeepData(keepVertexId))
        writer.saveVertex(UriData(uriVertexId))
        writer.saveVertex(UserData(userVertexId))
        writer.saveVertex(LibraryData(libVertexId))

        writer.saveEdge(userVertexId, keepVertexId, TimestampEdgeData(update.createdAt.getMillis()))
        writer.saveEdge(uriVertexId, keepVertexId, TimestampEdgeData(update.createdAt.getMillis()))

        writer.saveEdge(keepVertexId, userVertexId, EmptyEdgeData)
        writer.saveEdge(keepVertexId, uriVertexId, EmptyEdgeData)

        writer.saveEdge(libVertexId, keepVertexId, EmptyEdgeData)
        writer.saveEdge(keepVertexId, libVertexId, EmptyEdgeData)

      case _ => // do nothing

    }
  }

  private def processLDAUpdate(update: SparseLDAGraphUpdate)(implicit writer: GraphWriter) = {

    def removeOldURITopicsIfExists(uriVertexId: VertexDataId[UriReader], numTopics: Int): Unit = {
      (0 until numTopics).foreach { i =>
        val topicId = LDATopicId(update.modelVersion, LDATopic(i))
        writer.removeEdgeIfExists(uriVertexId, topicId, WeightedEdgeReader)
        writer.removeEdgeIfExists(topicId, uriVertexId, WeightedEdgeReader)
      }
    }

    val uriVertexId: VertexDataId[UriReader] = update.uriFeatures.uriId
    removeOldURITopicsIfExists(uriVertexId, update.uriFeatures.features.dimension)

    val uriData = UriData(uriVertexId)

    update.uriFeatures.features.topics foreach {
      case (topic, score) =>
        val topicId = LDATopicId(update.modelVersion, topic)
        val topicVertexId: VertexDataId[LDATopicReader] = topicId
        writer.saveVertex(LDATopicData(topicVertexId))
        writer.saveVertex(uriData)
        writer.saveEdge(uriVertexId, topicVertexId, WeightedEdgeData(score))
        writer.saveEdge(topicVertexId, uriVertexId, WeightedEdgeData(score))
    }
  }

  private def processLDACleanup(ldaCleanOldVersion: LDAOldVersionCleanupGraphUpdate)(implicit writer: GraphWriter) = {
    val LDAOldVersionCleanupGraphUpdate(version, numTopics) = ldaCleanOldVersion
    (0 until numTopics).foreach { i =>
      val topicId = LDATopicId(version, LDATopic(i))
      writer.removeVertexIfExists(topicId)
    }
  }

  private def processNormalizedUriGraphUpdate(update: NormalizedUriGraphUpdate)(implicit writer: GraphWriter) = update.state match {
    case NormalizedURIStates.INACTIVE | NormalizedURIStates.REDIRECTED => writer.removeVertexIfExists(update.id)
    case _ => writer.saveVertex(UriData(update.id))
  }

  private def processEmailAccountGraphUpdate(update: EmailAccountGraphUpdate)(implicit writer: GraphWriter) = {
    writer.saveVertex(EmailAccountData(update.emailAccountId))
    update.userId.foreach { userId => // todo(Léo): once we have a more aggressive email verification policy, ignore unverified accounts
      writer.saveVertex(UserData(userId))
      writer.saveEdge(userId, update.emailAccountId, EmptyEdgeData)
      writer.saveEdge(update.emailAccountId, userId, EmptyEdgeData)
    }
  }

  private def processEmailContactGraphUpdate(update: EmailContactGraphUpdate)(implicit writer: GraphWriter) = {
    if (update.deleted || update.hidden) {
      writer.removeEdgeIfExists(update.abookId, update.emailAccountId, EmptyEdgeReader)
      writer.removeEdgeIfExists(update.emailAccountId, update.abookId, EmptyEdgeReader)
    } else {

      writer.saveVertex(AddressBookData(update.abookId))
      writer.saveVertex(UserData(update.userId))
      writer.saveVertex(EmailAccountData(update.emailAccountId))

      writer.saveEdge(update.userId, update.abookId, EmptyEdgeData)
      writer.saveEdge(update.abookId, update.userId, EmptyEdgeData)

      writer.saveEdge(update.emailAccountId, update.abookId, EmptyEdgeData)
      writer.saveEdge(update.abookId, update.emailAccountId, EmptyEdgeData)
    }
  }

  private def processLibraryGraphUpdate(update: LibraryGraphUpdate)(implicit writer: GraphWriter) = update.state match {
    case LibraryStates.ACTIVE => writer.saveVertex(LibraryData(update.libId))
    case _ => writer.removeVertexIfExists(update.libId)
  }

  private def processLibraryMembershipGraphUpdate(update: LibraryMembershipGraphUpdate)(implicit writer: GraphWriter) = update.state match {
    case LibraryMembershipStates.ACTIVE =>
      writer.saveVertex(UserData(update.userId))
      writer.saveVertex(LibraryData(update.libId))
      writer.saveEdge(update.userId, update.libId, EmptyEdgeData)
      writer.saveEdge(update.libId, update.userId, EmptyEdgeData)
    case _ =>
      writer.removeEdgeIfExists(update.userId, update.libId, EmptyEdgeReader)
      writer.removeEdgeIfExists(update.libId, update.userId, EmptyEdgeReader)
  }
}
