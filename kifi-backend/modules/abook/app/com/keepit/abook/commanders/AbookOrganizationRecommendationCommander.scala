package com.keepit.abook.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.abook.model.{ EContact, EmailAccountInfo, EmailAccount, IrrelevantPeopleForOrg, OrganizationInviteRecommendation, OrganizationMemberRecommendationRepo, UserEmailInviteRecommendationRepo, TwitterInviteRecommendationRepo, LinkedInInviteRecommendationRepo, FacebookInviteRecommendationRepo, FriendRecommendationRepo, EContactRepo, EmailAccountRepo }
import com.keepit.common.CollectionHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.time._
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.model.SociallyRelatedEntitiesForOrg
import com.keepit.model.{ OrganizationInviteView, User, Organization }
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class AbookOrganizationRecommendationCommander @Inject() (
    db: Database,
    abookCommander: ABookCommander,
    emailAccountRepo: EmailAccountRepo,
    contactRepo: EContactRepo,
    friendRecommendationRepo: FriendRecommendationRepo,
    facebookInviteRecommendationRepo: FacebookInviteRecommendationRepo,
    linkedInInviteRecommendationRepo: LinkedInInviteRecommendationRepo,
    twitterInviteRecommendationRepo: TwitterInviteRecommendationRepo,
    userEmailInviteRecommendationRepo: UserEmailInviteRecommendationRepo,
    orgMembershipRecommendationRepo: OrganizationMemberRecommendationRepo,
    graph: GraphServiceClient,
    shoebox: ShoeboxServiceClient,
    abookRecommendationHelper: AbookRecommendationHelper,
    clock: Clock) extends Logging {

  def hideUserRecommendation(organizationId: Id[Organization], memberId: Id[User], irrelevantMemberId: Id[User]): Unit = {
    db.readWrite { implicit session =>
      orgMembershipRecommendationRepo.recordIrrelevantRecommendation(organizationId, memberId, Left(irrelevantMemberId))
    }
  }

  def hideEmailRecommendation(organizationId: Id[Organization], memberId: Id[User], emailAddress: EmailAddress): Unit = {
    db.readWrite { implicit session =>
      val emailAccount = emailAccountRepo.internByAddress(emailAddress)
      orgMembershipRecommendationRepo.recordIrrelevantRecommendation(organizationId, memberId, Right(emailAccount.id.get))
    }
  }

  def getRecommendations(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], offset: Int, limit: Int): Future[Seq[OrganizationInviteRecommendation]] = {
    val fRecommendations = generateFutureRecommendations(orgId, viewerIdOpt).map {
      recoStream => recoStream.slice(offset, offset + limit).toSeq
    }
    fRecommendations
  }

  private def generateFutureRecommendations(orgId: Id[Organization], viewerIdOpt: Option[Id[User]]): Future[Stream[OrganizationInviteRecommendation]] = {
    val fRelatedEntities = getSociallyRelatedEntities(orgId)
    val fEmailInviteRecommendations = generateFutureEmailRecommendations(orgId, viewerIdOpt, fRelatedEntities)
    val fUserInviteRecommendations = generateFutureUserRecommendations(orgId, fRelatedEntities).map(_.getOrElse(Stream.empty))
    for {
      emailRecommendations <- fEmailInviteRecommendations
      userRecommendations <- fUserInviteRecommendations
    } yield {
      CollectionHelpers.interleaveBy(emailRecommendations, userRecommendations)(_.score)
    }
  }

  def getIrrelevantPeople(organizationId: Id[Organization]): Future[IrrelevantPeopleForOrg] = {
    val fMembers = shoebox.getOrganizationMembers(organizationId)
    val fOrganizationInviteViews = shoebox.getOrganizationInviteViews(organizationId)
    val (irrelevantUsers, irrelevantEmailAccounts) = db.readOnlyMaster { implicit session =>
      val irrelevantRecos = orgMembershipRecommendationRepo.getIrrelevantRecommendations(organizationId)
      val irrelevantUsers = irrelevantRecos.collect { case Left(userId) => userId }
      val irrelevantEmailAccounts = irrelevantRecos.collect { case Right(emailAccountId) => emailAccountId }
      (irrelevantUsers, irrelevantEmailAccounts)
    }
    for {
      members <- fMembers
      invites <- fOrganizationInviteViews
    } yield {
      val invitedUserIds = invites.flatMap(_.userId)
      val invitedEmailAddresses = invites.flatMap(_.emailAddress).toSeq
      val invitedEmailAccounts = db.readOnlyMaster { implicit session => emailAccountRepo.getByAddresses(invitedEmailAddresses: _*).values.map(_.id.get) }
      IrrelevantPeopleForOrg(
        organizationId,
        irrelevantUsers -- members -- invitedUserIds,
        (irrelevantEmailAccounts -- invitedEmailAccounts).map(EmailAccount.toEmailAccountInfoId)
      )
    }
  }

  private def generateFutureUserRecommendations(orgId: Id[Organization], fRelatedEntities: Future[Option[SociallyRelatedEntitiesForOrg]]): Future[Option[Stream[OrganizationInviteRecommendation]]] = {
    val fRelatedUsers = fRelatedEntities.map(_.map(_.users))
    val fFakeUsers = shoebox.getAllFakeUsers()
    val fOrgMembers = shoebox.getOrganizationMembers(orgId)
    val rejectedRecommendations = db.readOnlyMaster { implicit session =>
      orgMembershipRecommendationRepo.getIrrelevantRecommendations(orgId)
    }
    fRelatedUsers.flatMap {
      case None => Future.successful(None)
      case Some(relatedUsers) => for {
        members <- fOrgMembers
        fakeUsers <- fFakeUsers
      } yield {
        val recommendations = relatedUsers.related.map { case (userId, score) => OrganizationInviteRecommendation(Left(userId), None, score) }
        val irrelevantRecommendations = members ++ fakeUsers ++ rejectedRecommendations
        Some(recommendations.toStream.filter(reco => !irrelevantRecommendations.contains(reco.identifier.left.get)))
      }
    }
  }

  private def generateFutureEmailRecommendations(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], fRelatedEntities: Future[Option[SociallyRelatedEntitiesForOrg]]): Future[Stream[OrganizationInviteRecommendation]] = {
    val fNormalizedUsernames = abookRecommendationHelper.getNormalizedUsernames(Right(orgId))
    fRelatedEntities.flatMap { relatedEntities =>
      val relatedEmailAccounts = relatedEntities.map(_.emailAccounts.related) getOrElse Seq.empty
      if (relatedEmailAccounts.isEmpty) Future.successful(Stream.empty)
      else {
        val rejectedEmailInviteRecommendations = db.readOnlyReplica { implicit session => orgMembershipRecommendationRepo.getIrrelevantRecommendations(orgId) }.collect {
          case Right(emailAccountId) => emailAccountId
        }
        val allContacts = db.readOnlyMaster { implicit session =>
          viewerIdOpt match {
            case Some(viewerId) => contactRepo.getByUserId(viewerId)
            case None =>
              val relevantEmailAccountIds = relatedEmailAccounts.map { case (id, score) => EmailAccount.fromEmailAccountInfoId(id) }.toSet
              contactRepo.getByEmailAccountIds(relevantEmailAccountIds)
          }
        }
        for {
          normalizedUserNames <- fNormalizedUsernames
        } yield {
          generateEmailInviteRecommendations(relatedEmailAccounts.toStream, rejectedEmailInviteRecommendations, allContacts, normalizedUserNames)
        }
      }
    }
  }

  private def generateEmailInviteRecommendations(
    relatedEmailAccounts: Stream[(Id[EmailAccountInfo], Double)],
    rejectedRecommendations: Set[Id[EmailAccount]],
    allContacts: Set[EContact],
    normalizedUserNames: Set[String]): Stream[OrganizationInviteRecommendation] = {

    val relevantEmailAccounts = allContacts.groupBy(_.emailAccountId).filter {
      case (emailAccountId, contacts) =>
        val mayAlreadyBeOnKifi = contacts.head.contactUserId.isDefined || contacts.exists { contact =>
          contact.name.exists { name => normalizedUserNames.contains(abookRecommendationHelper.normalize(name)) }
        }
        !mayAlreadyBeOnKifi && EmailAddress.isLikelyHuman(contacts.head.email)
    }

    @inline def isRelevant(emailAccountId: Id[EmailAccountInfo]): Boolean = {
      relevantEmailAccounts.contains(emailAccountId) &&
        !rejectedRecommendations.contains(emailAccountId)
    }

    @inline def isValidName(name: String, address: EmailAddress) = name.nonEmpty && !name.equalsIgnoreCase(address.address)

    val recommendations = relatedEmailAccounts.collect {
      case (emailAccountId, score) if isRelevant(emailAccountId) =>
        val preferredContact = relevantEmailAccounts(emailAccountId).maxBy { emailAccount =>
          emailAccount.name.collect { case name if isValidName(name, emailAccount.email) => name.length } getOrElse 0 // pick by longest name different from the email address
        }
        val validName = preferredContact.name.filter(isValidName(_, preferredContact.email))
        OrganizationInviteRecommendation(Right(preferredContact.email), validName, score)
    }
    recommendations.take(relevantEmailAccounts.size)
  }

  private val consolidateRelatedEntities = new RequestConsolidator[Id[Organization], Option[SociallyRelatedEntitiesForOrg]](1 second)
  private def getSociallyRelatedEntities(orgId: Id[Organization]): Future[Option[SociallyRelatedEntitiesForOrg]] = {
    consolidateRelatedEntities(orgId)(graph.getSociallyRelatedEntitiesForOrg)
  }
}
