package com.keepit.abook.commanders

import java.text.Normalizer

import com.google.inject.{ Inject, Singleton }
import com.keepit.abook.model.{ EContact, EmailAccountInfo, EmailAccount, IrrelevantPeopleForOrg, OrganizationInviteRecommendation, OrganizationMemberRecommendationRepo, OrganizationEmailInviteRecommendationRepo, UserEmailInviteRecommendationRepo, TwitterInviteRecommendationRepo, LinkedInInviteRecommendationRepo, FacebookInviteRecommendationRepo, FriendRecommendationRepo, EContactRepo, EmailAccountRepo }
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
    organizationEmailInviteRecommendationRepo: OrganizationEmailInviteRecommendationRepo,
    orgMembershipRecommendationRepo: OrganizationMemberRecommendationRepo,
    graph: GraphServiceClient,
    shoebox: ShoeboxServiceClient,
    oldWTICommander: WTICommander,
    abookRecommendationHelper: AbookRecommendationHelper,
    clock: Clock) extends Logging {

  def hideUserRecommendation(organizationId: Id[Organization], memberId: Id[User], irrelevantMemberId: Id[User]): Unit = {
    db.readWrite { implicit session =>
      orgMembershipRecommendationRepo.recordIrrelevantRecommendation(organizationId, memberId, irrelevantMemberId)
    }
  }

  def hideEmailRecommendation(organizationId: Id[Organization], memberId: Id[User], emailAddress: EmailAddress): Unit = {
    db.readWrite { implicit session =>
      val emailAccount = emailAccountRepo.internByAddress(emailAddress)
      organizationEmailInviteRecommendationRepo.recordIrrelevantRecommendation(organizationId, memberId, emailAccount.id.get)
    }
  }

  def getRecommendations(orgId: Id[Organization], memberId: Id[User], offset: Int, limit: Int): Future[Seq[OrganizationInviteRecommendation]] = {
    val start = clock.now()
    val fRecommendations = generateFutureRecommendations(orgId, memberId).map {
      recoStream => recoStream.slice(offset, offset + limit).toSeq
    }
    fRecommendations.onSuccess {
      case recommendations if recommendations.nonEmpty => log.info(s"Computed ${recommendations.length}/${limit} (skipped $offset) friend recommendations for user $memberId in ${clock.now().getMillis - start.getMillis}ms.")
      case _ => log.info(s"Org recommendations are not available. Returning in ${clock.now().getMillis - start.getMillis}ms.")
    }
    fRecommendations
  }

  def generateFutureRecommendations(orgId: Id[Organization], memberId: Id[User]): Future[Stream[OrganizationInviteRecommendation]] = {
    val fExistingInvites = shoebox.getOrganizationInviteViews(orgId)
    val fEmailInviteRecommendations = generateFutureEmailRecommendations(orgId, memberId, fExistingInvites)
    val fUserInviteRecommendations = generateFutureUserRecommendations(orgId, fExistingInvites).map(_.getOrElse(Stream.empty))

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
      val irrelevantUsers = orgMembershipRecommendationRepo.getIrrelevantRecommendations(organizationId)
      val irrelevantEmailAccounts = organizationEmailInviteRecommendationRepo.getIrrelevantRecommendations(organizationId)
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

  private def generateFutureUserRecommendations(orgId: Id[Organization], fExistingInvites: Future[Set[OrganizationInviteView]]): Future[Option[Stream[OrganizationInviteRecommendation]]] = {
    val fRelatedUsers = getSociallyRelatedEntities(orgId).map(_.map(_.users))
    val fOrgMembers = shoebox.getOrganizationMembers(orgId)
    val fFakeUsers = shoebox.getAllFakeUsers()
    val rejectedRecommendations = db.readOnlyMaster { implicit session =>
      orgMembershipRecommendationRepo.getIrrelevantRecommendations(orgId)
    }
    fRelatedUsers.flatMap {
      case None => Future.successful(None)
      case Some(relatedUsers) => for {
        members <- fOrgMembers
        invitees <- fExistingInvites
        fakeUsers <- fFakeUsers
      } yield {
        val recommendations = relatedUsers.related.map { case (userId, score) => OrganizationInviteRecommendation(Left(userId), score) }
        val irrelevantRecommendations = members ++ invitees.flatMap(_.userId) ++ fakeUsers ++ rejectedRecommendations
        Some(recommendations.toStream.filter(reco => !irrelevantRecommendations.contains(reco.target.left.get)))
      }
    }
  }

  private def generateFutureEmailRecommendations(orgId: Id[Organization], memberId: Id[User], fExistingInvites: Future[Set[OrganizationInviteView]]): Future[Stream[OrganizationInviteRecommendation]] = {
    val fExistingInvites = shoebox.getOrganizationInviteViews(orgId)
    val fNormalizedUsernames = abookRecommendationHelper.getNormalizedUsernames(Right(orgId))
    getSociallyRelatedEntities(orgId).flatMap { relatedEntities =>
      val relatedEmailAccounts = relatedEntities.map(_.emailAccounts.related) getOrElse Seq.empty
      if (relatedEmailAccounts.isEmpty) Future.successful(Stream.empty)
      else {
        val rejectedEmailInviteRecommendations = db.readOnlyReplica { implicit session => organizationEmailInviteRecommendationRepo.getIrrelevantRecommendations(orgId) }
        val allContacts = db.readOnlyMaster { implicit session => contactRepo.getByUserId(memberId) }
        for {
          existingInvites <- fExistingInvites
          normalizedUserNames <- fNormalizedUsernames
        } yield {
          generateEmailInviteRecommendations(relatedEmailAccounts.toStream, rejectedEmailInviteRecommendations, allContacts, normalizedUserNames, existingInvites)
        }
      }
    }
  }

  private def generateEmailInviteRecommendations(
    relatedEmailAccounts: Stream[(Id[EmailAccountInfo], Double)],
    rejectedRecommendations: Set[Id[EmailAccount]],
    viewersContacts: Seq[EContact],
    normalizedUsernames: Set[String],
    existingInvites: Set[OrganizationInviteView]): Stream[OrganizationInviteRecommendation] = {

    val relevantEmailAccounts = viewersContacts.groupBy(_.emailAccountId).filter {
      case (emailAccountId, contacts) =>
        val mayAlreadyBeOnKifi = contacts.head.contactUserId.isDefined || contacts.exists { contact =>
          contact.name.exists { name => normalizedUsernames.contains(abookRecommendationHelper.normalize(name)) }
        }
        !mayAlreadyBeOnKifi && EmailAddress.isLikelyHuman(contacts.head.email)
    }

    val existingEmailInvitesByLowerCaseAddress = existingInvites.collect {
      case emailInvite if emailInvite.emailAddress.isDefined =>
        emailInvite.emailAddress.get.address.toLowerCase -> emailInvite
    }.toMap

    val relevantEmailInvites = relevantEmailAccounts.mapValues { contacts =>
      existingEmailInvitesByLowerCaseAddress.get(contacts.head.email.address.toLowerCase)
    }.collect { case (emailAccountId, Some(existingInvite)) => emailAccountId -> existingInvite }

    @inline def isRelevant(emailAccountId: Id[EmailAccountInfo]): Boolean = {
      relevantEmailAccounts.contains(emailAccountId) &&
        !rejectedRecommendations.contains(emailAccountId)
    }

    @inline def isValidName(name: String, address: EmailAddress) = name.nonEmpty && !name.equalsIgnoreCase(address.address)

    val recommendations = relatedEmailAccounts.collect {
      case (emailAccountId, score) if isRelevant(emailAccountId) =>
        val firstInvitedAt = relevantEmailInvites.get(emailAccountId).map(_.createdAt)
        val preferredContact = relevantEmailAccounts(emailAccountId).maxBy { emailAccount =>
          emailAccount.name.collect { case name if isValidName(name, emailAccount.email) => name.length } getOrElse 0 // pick by longest name different from the email address
        }
        val validName = preferredContact.name.filter(isValidName(_, preferredContact.email))
        OrganizationInviteRecommendation(Right(preferredContact.email), score)
    }
    recommendations.take(relevantEmailAccounts.size)
  }

  private val consolidateRelatedEntities = new RequestConsolidator[Id[Organization], Option[SociallyRelatedEntitiesForOrg]](1 second)
  private def getSociallyRelatedEntities(orgId: Id[Organization]): Future[Option[SociallyRelatedEntitiesForOrg]] = {
    consolidateRelatedEntities(orgId)(graph.getSociallyRelatedEntitiesForOrg)
  }
}
