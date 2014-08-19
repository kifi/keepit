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
import java.text.Normalizer

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

  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int): Future[Seq[Id[User]]] = {
    val start = clock.now()
    val futureRecommendations = generateFutureFriendRecommendations(userId).map(_.drop(offset).take(limit).map(_._1).toSeq)
    futureRecommendations.onSuccess {
      case recommendations =>
        log.info(s"Computed ${recommendations.length}/${limit} friend recommendations for user $userId in ${clock.now().getMillis - start.getMillis}ms.")
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

  def getInviteRecommendations(userId: Id[User], offset: Int, limit: Int, relevantNetworks: Set[SocialNetworkType]): Future[Seq[InviteRecommendation]] = {
    val start = clock.now()
    val futureRecommendations = generateFutureInviteRecommendations(userId, relevantNetworks).map(_.drop(offset).take(limit).toSeq)
    futureRecommendations.onSuccess {
      case recommendations =>
        log.info(s"Computed ${recommendations.length}/${limit} invite recommendations for user $userId in ${clock.now().getMillis - start.getMillis}ms.")
    }
    futureRecommendations
  }

  def getIrrelevantRecommendations(userId: Id[User]): IrrelevantPeopleRecommendations = {
    db.readOnlyMaster { implicit session =>
      val irrelevantUsers = friendRecommendationRepo.getIrrelevantRecommendations(userId)
      val irrelevantFacebookAccounts = facebookInviteRecommendationRepo.getIrrelevantRecommendations(userId)
      val irrelevantLinkedInAccounts = linkedInInviteRecommendationRepo.getIrrelevantRecommendations(userId)
      val irrelevantEmailAccounts = emailInviteRecommendationRepo.getIrrelevantRecommendations(userId).map(EmailAccount.toEmailAccountInfoId)
      IrrelevantPeopleRecommendations(userId, irrelevantUsers, irrelevantFacebookAccounts, irrelevantLinkedInAccounts, irrelevantEmailAccounts)
    }
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

  private def generateFutureInviteRecommendations(userId: Id[User], relevantNetworks: Set[SocialNetworkType]): Future[Stream[InviteRecommendation]] = {
    val (futureExistingInvites, futureNormalizedUserNames) = {
      if (relevantNetworks.isEmpty) (Future.successful(Seq.empty[Invitation]), Future.successful(Set.empty[String]))
      else (shoebox.getInvitations(userId), getNormalizedUserNames(userId))
    }

    val futureEmailInviteRecommendations = {
      if (!relevantNetworks.contains(EMAIL)) Future.successful(Stream.empty)
      else generateFutureEmailInvitesRecommendations(userId, futureExistingInvites, futureNormalizedUserNames)
    }

    val (futureRelevantSocialFriends, futureExistingSocialInvites) = {
      if ((relevantNetworks intersect Set(FACEBOOK, LINKEDIN)).isEmpty) (Future.successful(Map.empty[Id[SocialUserInfo], SocialUserBasicInfo]), Future.successful(Map.empty[Id[SocialUserInfo], Invitation]))
      else {
        val futureRelevantSocialFriends = {
          val futureSocialFriends = shoebox.getSocialConnections(userId)
          for {
            socialFriends <- futureSocialFriends
            normalizedUserNames <- futureNormalizedUserNames
          } yield {
            @inline def mayAlreadyBeOnKifi(socialFriend: SocialUserBasicInfo) = socialFriend.userId.isDefined || normalizedUserNames.contains(normalize(socialFriend.fullName))
            socialFriends.collect { case socialFriend if relevantNetworks.contains(socialFriend.networkType) && !mayAlreadyBeOnKifi(socialFriend) => socialFriend.id -> socialFriend }.toMap
          }
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
      PriorityStreamAggregator.aggregateBy(facebookInviteRecommendations, linkedInInviteRecommendations, emailInviteRecommendations)(_.score)
    }
  }

  private def generateFutureFacebookInviteRecommendations(userId: Id[User], futureExistingSocialInvites: Future[Map[Id[SocialUserInfo], Invitation]], futureRelevantSocialFriends: Future[Map[Id[SocialUserInfo], SocialUserBasicInfo]]): Future[Stream[InviteRecommendation]] = {
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

  private def generateFutureLinkedInInviteRecommendations(userId: Id[User], futureExistingSocialInvites: Future[Map[Id[SocialUserInfo], Invitation]], futureRelevantSocialFriends: Future[Map[Id[SocialUserInfo], SocialUserBasicInfo]]): Future[Stream[InviteRecommendation]] = {
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

  private def generateFutureEmailInvitesRecommendations(userId: Id[User], futureExistingInvites: Future[Seq[Invitation]], futureNormalizedUserNames: Future[Set[String]]): Future[Stream[InviteRecommendation]] = {
    graph.getSociallyRelatedEmailAccounts(userId, bePatient = false).flatMap { relatedEmailAccountsOption =>
      val relatedEmailAccounts = relatedEmailAccountsOption.map(_.related) getOrElse Seq.empty
      if (relatedEmailAccounts.isEmpty) Future.successful(Stream.empty)
      else {
        val rejectedEmailInviteRecommendations = db.readOnlyReplica { implicit session => emailInviteRecommendationRepo.getIrrelevantRecommendations(userId) }
        val allContacts = db.readOnlyMaster { implicit session => contactRepo.getByUserId(userId) }
        for {
          existingInvites <- futureExistingInvites
          normalizedUserNames <- futureNormalizedUserNames
        } yield {
          generateEmailInviteRecommendations(userId, relatedEmailAccounts.toStream, rejectedEmailInviteRecommendations, allContacts, normalizedUserNames, existingInvites)
        }
      }
    }
  }

  private def generateSocialInviteRecommendations(
    relatedSocialUsers: Stream[(Id[SocialUserInfo], Double)],
    rejectedRecommendations: Set[Id[SocialUserInfo]],
    relevantSocialFriends: Map[Id[SocialUserInfo], SocialUserBasicInfo],
    existingSocialInvites: Map[Id[SocialUserInfo], Invitation]): Stream[InviteRecommendation] = {

    @inline def isRelevant(socialUserId: Id[SocialUserInfo]): Boolean = {
      relevantSocialFriends.contains(socialUserId) &&
        !rejectedRecommendations.contains(socialUserId) &&
        (existingSocialInvites.get(socialUserId).map(canBeRecommendedAgain) getOrElse true)
    }

    val recommendations = relatedSocialUsers.collect {
      case (socialUserId, score) if isRelevant(socialUserId) =>
        val friend = relevantSocialFriends(socialUserId)
        val lastInvitedAt = existingSocialInvites.get(socialUserId).flatMap(_.lastSentAt)
        InviteRecommendation(friend.networkType, Right(friend.socialId), friend.fullName, friend.getPictureUrl(80, 80), lastInvitedAt, score)
    }
    recommendations.take(relevantSocialFriends.size)
  }

  private def generateEmailInviteRecommendations(
    userId: Id[User],
    relatedEmailAccounts: Stream[(Id[EmailAccountInfo], Double)],
    rejectedRecommendations: Set[Id[EmailAccount]],
    allContacts: Seq[EContact],
    normalizedUserNames: Set[String],
    existingInvites: Seq[Invitation]): Stream[InviteRecommendation] = {

    val relevantEmailAccounts = allContacts.groupBy(_.emailAccountId).filter {
      case (emailAccountId, contacts) =>
        val mayAlreadyBeOnKifi = contacts.head.contactUserId.isDefined || contacts.exists { contact =>
          contact.name.exists { name => normalizedUserNames.contains(normalize(name)) }
        }
        !mayAlreadyBeOnKifi && EmailAddress.isLikelyHuman(contacts.head.email)
    }

    val existingEmailInvitesByLowerCaseAddress = existingInvites.collect {
      case emailInvite if emailInvite.recipientEmailAddress.isDefined =>
        emailInvite.recipientEmailAddress.get.address.toLowerCase -> emailInvite
    }.toMap

    val relevantEmailInvites = relevantEmailAccounts.mapValues { contacts =>
      existingEmailInvitesByLowerCaseAddress.get(contacts.head.email.address.toLowerCase)
    }.collect { case (emailAccountId, Some(existingInvite)) => emailAccountId -> existingInvite }

    @inline def isRelevant(emailAccountId: Id[EmailAccountInfo]): Boolean = {
      relevantEmailAccounts.contains(emailAccountId) &&
        !rejectedRecommendations.contains(emailAccountId) &&
        (relevantEmailInvites.get(emailAccountId).map(canBeRecommendedAgain) getOrElse true)
    }

    val recommendations = relatedEmailAccounts.collect {
      case (emailAccountId, score) if isRelevant(emailAccountId) =>
        val lastInvitedAt = relevantEmailInvites.get(emailAccountId).flatMap(_.lastSentAt)
        val contacts = relevantEmailAccounts(emailAccountId)
        val (emailAddress, name) = contacts.find(_.name.isDefined) match {
          case Some(preferredContact) => (preferredContact.email, preferredContact.name.get)
          case _ => {
            val emailAddress = contacts.head.email
            (emailAddress, emailAddress.address)
          }
        }
        InviteRecommendation(SocialNetworks.EMAIL, Left(emailAddress), name, None, lastInvitedAt, score)
    }
    recommendations.take(relevantEmailAccounts.size)
  }

  @inline
  private def canBeRecommendedAgain(invitation: Invitation): Boolean = {
    // arbitrary heuristic: allow an invitation to be suggested again if it has been sent only once more than a week ago
    invitation.state == InvitationStates.ACTIVE &&
      invitation.canBeSent &&
      invitation.timesSent > 1 &&
      !invitation.lastSentAt.exists(_.isAfter(clock.now().minusDays(7)))
  }

  private def getNormalizedUserNames(userId: Id[User]): Future[Set[String]] = {
    for {
      friendIds <- shoebox.getFriends(userId)
      basicUsers <- shoebox.getBasicUsers(Seq(userId) ++ friendIds)
    } yield {
      val fullNames = basicUsers.values.flatMap(user => Set(user.firstName + " " + user.lastName, user.lastName + " " + user.firstName))
      fullNames.toSet.map(normalize)
    }
  }

  private val diacriticalMarksRegex = "\\p{InCombiningDiacriticalMarks}+".r
  @inline private def normalize(fullName: String): String = diacriticalMarksRegex.replaceAllIn(Normalizer.normalize(fullName.trim, Normalizer.Form.NFD), "").toLowerCase
}
