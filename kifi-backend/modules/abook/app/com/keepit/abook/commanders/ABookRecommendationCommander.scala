package com.keepit.abook.commanders

import com.keepit.common.db.Id
import com.keepit.model._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.abook.model._
import com.keepit.graph.GraphServiceClient
import scala.concurrent.Future
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.{ SocialNetworks, SocialNetworkType }
import SocialNetworks.{ FACEBOOK, LINKEDIN, EMAIL }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import scala.inline
import com.keepit.common.PriorityStreamAggregator
import com.keepit.common.logging.Logging

@Singleton
class ABookRecommendationCommander @Inject() (
    db: Database,
    abookCommander: ABookCommander,
    emailAccountRepo: EmailAccountRepo,
    contactRepo: EContactRepo,
    friendRecommendationRepo: FriendRecommendationRepo,
    facebookInviteRecommendationRepo: FacebookInviteRecommendationRepo,
    linkedInInviteRecommendationRepo: LinkedInInviteRecommendationRepo,
    emailInviteRecommendationRepo: EmailInviteRecommendationRepo,
    graph: GraphServiceClient,
    shoebox: ShoeboxServiceClient,
    clock: Clock) extends Logging {

  def hideFriendRecommendation(userId: Id[User], irrelevantUserId: Id[User]): Unit = {
    db.readWrite { implicit session =>
      friendRecommendationRepo.recordIrrelevantRecommendation(userId, irrelevantUserId)
    }
  }

  def getFriendRecommendations(userId: Id[User], page: Int, pageSize: Int): Future[Seq[Id[User]]] = {
    val start = clock.now()
    val futureRecommendations = generateFutureFriendRecommendations(userId).map(_.drop(page * pageSize).take(pageSize).map(_._1).toSeq)
    futureRecommendations.onSuccess {
      case recommendations =>
        log.info(s"Computed ${recommendations.length}/${pageSize} friend recommendations for user $userId in ${clock.now().getMillis - start.getMillis}ms.")
    }
    futureRecommendations
  }

  def hideInviteRecommendation(userId: Id[User], network: SocialNetworkType, irrelevantFriendId: Either[EmailAddress, Id[SocialUserInfo]]) = {
    db.readWrite { implicit session =>
      (irrelevantFriendId, network) match {
        case (Right(socialUserId), FACEBOOK) => facebookInviteRecommendationRepo.recordIrrelevantRecommendation(userId, socialUserId)
        case (Right(socialUserId), LINKEDIN) => linkedInInviteRecommendationRepo.recordIrrelevantRecommendation(userId, socialUserId)
        case (Left(emailAddress), EMAIL) =>
          val emailAccount = emailAccountRepo.internByAddress(emailAddress)
          emailInviteRecommendationRepo.recordIrrelevantRecommendation(userId, emailAccount.id.get)
        case unsupportedNetwork => throw new IllegalArgumentException(s"Cannot hide unsupported invite recommendation: $unsupportedNetwork")
      }
    }
  }

  def getInviteRecommendations(userId: Id[User], page: Int, pageSize: Int, relevantNetworks: Set[SocialNetworkType]): Future[Seq[InviteRecommendation]] = {
    val start = clock.now()
    val futureRecommendations = generateFutureInviteRecommendations(userId, relevantNetworks).map(_.drop(page * pageSize).take(pageSize).map(_._1).toSeq)
    futureRecommendations.onSuccess {
      case recommendations =>
        log.info(s"Computed ${recommendations.length}/${pageSize} invite recommendations for user $userId in ${clock.now().getMillis - start.getMillis}ms.")
    }
    futureRecommendations
  }

  private def generateFutureFriendRecommendations(userId: Id[User]): Future[Stream[(Id[User], Double)]] = {
    val futureRelatedUsers = graph.getSociallyRelatedUsers(userId, bePatient = false)
    val futureFriends = shoebox.getFriends(userId)
    val futureFriendRequests = shoebox.getFriendRequestsBySender(userId)
    val futureFakeUsers = shoebox.getAllFakeUsers()
    val rejectedRecommendations = db.readOnlyMaster { implicit session =>
      friendRecommendationRepo.getIrrelevantRecommendations(userId)
    }
    futureRelatedUsers.flatMap { relatedUsersOption =>
      val relatedUsers = relatedUsersOption.map(_.related) getOrElse Seq.empty
      if (relatedUsers.isEmpty) Future.successful(Stream.empty)
      else for {
        friends <- futureFriends
        friendRequests <- futureFriendRequests
        fakeUsers <- futureFakeUsers
      } yield {
        val irrelevantRecommendations = rejectedRecommendations ++ friends ++ friendRequests.map(_.recipientId) ++ fakeUsers + userId
        relatedUsers.toStream.filter { case (friendId, _) => !irrelevantRecommendations.contains(friendId) }
      }
    }
  }

  private def generateFutureInviteRecommendations(userId: Id[User], relevantNetworks: Set[SocialNetworkType]): Future[Stream[(InviteRecommendation, Double)]] = {
    val futureExistingInvites = if (relevantNetworks.isEmpty) Future.successful(Seq.empty) else shoebox.getInvitations(userId)

    val futureEmailInviteRecommendations = {
      if (!relevantNetworks.contains(EMAIL)) Future.successful(Stream.empty)
      else generateFutureEmailInvitesRecommendations(userId, futureExistingInvites)
    }

    val (futureRelevantSocialFriends, futureExistingSocialInvites) = {
      if ((relevantNetworks intersect Set(FACEBOOK, LINKEDIN)).isEmpty) (Future.successful(Map.empty[Id[SocialUserInfo], SocialUserBasicInfo]), Future.successful(Map.empty[Id[SocialUserInfo], Invitation]))
      else {
        val futureRelevantSocialFriends = shoebox.getSocialConnections(userId).map { socialFriends =>
          socialFriends.collect { case friend if friend.userId.isEmpty && relevantNetworks.contains(friend.networkType) => friend.id -> friend }.toMap
        }
        val futureExistingSocialInvites = futureExistingInvites.map { existingInvites =>
          existingInvites.collect { case socialInvite if socialInvite.recipientSocialUserId.isDefined => socialInvite.recipientSocialUserId.get -> socialInvite }.toMap
        }
        (futureRelevantSocialFriends, futureExistingSocialInvites)
      }
    }

    val futureFacebookInviteRecommendations = {
      if (!relevantNetworks.contains(FACEBOOK)) Future.successful(Stream.empty)
      else generateFutureFacebookInviteRecommendations(userId, futureExistingSocialInvites, futureRelevantSocialFriends)
    }

    val futureLinkedInInviteRecommendations = {
      if (!relevantNetworks.contains(LINKEDIN)) Future.successful(Stream.empty)
      else generateFutureLinkedInInviteRecommendations(userId, futureExistingSocialInvites, futureRelevantSocialFriends)
    }

    for {
      facebookInviteRecommendations <- futureFacebookInviteRecommendations
      linkedInInviteRecommendations <- futureLinkedInInviteRecommendations
      emailInviteRecommendations <- futureEmailInviteRecommendations
    } yield {
      // Relying on aggregateBy stability, breaks ties by picking Facebook over LinkedIn, and LinkedIn over Email
      PriorityStreamAggregator.aggregateBy(facebookInviteRecommendations, linkedInInviteRecommendations, emailInviteRecommendations) { case (_, score) => score }
    }
  }

  private def generateFutureFacebookInviteRecommendations(userId: Id[User], futureExistingSocialInvites: Future[Map[Id[SocialUserInfo], Invitation]], futureRelevantSocialFriends: Future[Map[Id[SocialUserInfo], SocialUserBasicInfo]]): Future[Stream[(InviteRecommendation, Double)]] = {
    graph.getSociallyRelatedFacebookAccounts(userId, bePatient = false).flatMap { relatedFacebookAccountsOption =>
      val relatedFacebookAccounts = relatedFacebookAccountsOption.map(_.related) getOrElse Seq.empty
      if (relatedFacebookAccounts.isEmpty) Future.successful(Stream.empty)
      else {
        val rejectedFacebookInviteRecommendations = db.readOnlyReplica { implicit session => facebookInviteRecommendationRepo.getIrrelevantRecommendations(userId) }
        for {
          existingSocialInvites <- futureExistingSocialInvites
          relevantSocialFriends <- futureRelevantSocialFriends
        } yield {
          generateSocialInviteRecommendations(relatedFacebookAccounts.toStream, rejectedFacebookInviteRecommendations, relevantSocialFriends, existingSocialInvites)
        }
      }
    }
  }

  private def generateFutureLinkedInInviteRecommendations(userId: Id[User], futureExistingSocialInvites: Future[Map[Id[SocialUserInfo], Invitation]], futureRelevantSocialFriends: Future[Map[Id[SocialUserInfo], SocialUserBasicInfo]]): Future[Stream[(InviteRecommendation, Double)]] = {
    graph.getSociallyRelatedLinkedInAccounts(userId, bePatient = false).flatMap { relatedLinkedInAccountsOption =>
      val relatedLinkedInAccounts = relatedLinkedInAccountsOption.map(_.related) getOrElse Seq.empty
      if (relatedLinkedInAccounts.isEmpty) Future.successful(Stream.empty)
      else {
        val rejectedLinkedInInviteRecommendations = db.readOnlyReplica { implicit session => linkedInInviteRecommendationRepo.getIrrelevantRecommendations(userId) }
        for {
          existingSocialInvites <- futureExistingSocialInvites
          relevantSocialFriends <- futureRelevantSocialFriends
        } yield {
          generateSocialInviteRecommendations(relatedLinkedInAccounts.toStream, rejectedLinkedInInviteRecommendations, relevantSocialFriends, existingSocialInvites)
        }
      }
    }
  }

  private def generateFutureEmailInvitesRecommendations(userId: Id[User], futureExistingInvites: Future[Seq[Invitation]]): Future[Stream[(InviteRecommendation, Double)]] = {
    graph.getSociallyRelatedEmailAccounts(userId, bePatient = false).flatMap { relatedEmailAccountsOption =>
      val relatedEmailAccounts = relatedEmailAccountsOption.map(_.related) getOrElse Seq.empty
      if (relatedEmailAccounts.isEmpty) Future.successful(Stream.empty)
      else {
        val rejectedEmailInviteRecommendations = db.readOnlyReplica { implicit session => emailInviteRecommendationRepo.getIrrelevantRecommendations(userId) }
        futureExistingInvites.map { existingInvites =>
          val emailInvitesByLowerCaseAddress = existingInvites.collect {
            case emailInvite if emailInvite.recipientEmailAddress.isDefined =>
              emailInvite.recipientEmailAddress.get.address.toLowerCase -> emailInvite
          }.toMap
          generateEmailInviteRecommendations(userId, relatedEmailAccounts.toStream, rejectedEmailInviteRecommendations, emailInvitesByLowerCaseAddress)
        }
      }
    }
  }

  private def generateSocialInviteRecommendations(
    relatedSocialUsers: Stream[(Id[SocialUserInfo], Double)],
    rejectedRecommendations: Set[Id[SocialUserInfo]],
    relevantSocialFriends: Map[Id[SocialUserInfo], SocialUserBasicInfo],
    existingSocialInvites: Map[Id[SocialUserInfo], Invitation]): Stream[(InviteRecommendation, Double)] = {

    @inline def isRelevant(socialUserId: Id[SocialUserInfo]): Boolean = {
      relevantSocialFriends.contains(socialUserId) &&
        !rejectedRecommendations.contains(socialUserId) &&
        (existingSocialInvites.get(socialUserId).map(canBeRecommendedAgain) getOrElse true)
    }

    val recommendations = relatedSocialUsers.collect {
      case (socialUserId, score) if isRelevant(socialUserId) =>
        val friend = relevantSocialFriends(socialUserId)
        val lastInvitedAt = existingSocialInvites.get(socialUserId).flatMap(_.lastSentAt)
        val recommendation = InviteRecommendation(friend.networkType, Right(friend.socialId), friend.fullName, friend.getPictureUrl(80, 80), lastInvitedAt)
        (recommendation, score)
    }
    recommendations.take(relevantSocialFriends.size)
  }

  private def generateEmailInviteRecommendations(
    userId: Id[User],
    relatedEmailAccounts: Stream[(Id[EmailAccountInfo], Double)],
    rejectedRecommendations: Set[Id[EmailAccount]],
    existingEmailInvitesByLowerCaseAddress: Map[String, Invitation]): Stream[(InviteRecommendation, Double)] = {

    val recommendations = relatedEmailAccounts.flatMap {
      case (emailAccountId, score) =>
        val emailAccount = db.readOnlyReplica { implicit session => emailAccountRepo.get(emailAccountId) }
        val emailAddress = emailAccount.address
        val existingInvite = existingEmailInvitesByLowerCaseAddress.get(emailAddress.address.toLowerCase)
        val canBeInvited = emailAccount.userId.isEmpty && (existingInvite.map(canBeRecommendedAgain) getOrElse true)
        val relevantContactName = if (canBeInvited && EmailAddress.isLikelyHuman(emailAddress)) {
          val contacts = abookCommander.getContactsByUserAndEmail(userId, emailAddress)
          contacts.collectFirst { case contact if contact.contactUserId.isEmpty && contact.name.isDefined => contact.name.get }
        } else None
        relevantContactName.map { name =>
          val inviteRecommendation = InviteRecommendation(SocialNetworks.EMAIL, Left(emailAddress), name, None, existingInvite.flatMap(_.lastSentAt))
          (inviteRecommendation, score)
        }.toStream
    }
    val distinctEmailContactsCount = db.readOnlyReplica { implicit session => contactRepo.countEmailContacts(userId, distinctEmailAccounts = true) }
    recommendations.take(distinctEmailContactsCount)
  }

  @inline
  private def canBeRecommendedAgain(invitation: Invitation): Boolean = {
    // arbitrary heuristic: allow an invitation to be suggested again if it has been sent only once more than a week ago
    invitation.state == InvitationStates.ACTIVE &&
      invitation.canBeSent &&
      invitation.timesSent > 1 &&
      !invitation.lastSentAt.exists(_.isAfter(clock.now().minusDays(7)))
  }
}
