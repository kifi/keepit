package com.keepit.commanders

import java.util.concurrent.TimeUnit

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.abook.model.RichContact
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientModule }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.OrganizationFactory
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

class OrganizationInviteCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  def orgInviteCommander(implicit injector: Injector) = inject[OrganizationInviteCommander]
  def organizationMembershipRepo(implicit injector: Injector) = inject[OrganizationMembershipRepo]

  def setup(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val owner = UserFactory.user().withName("Kiwi", "Kiwi").saved
      userEmailAddressCommander.intern(userId = owner.id.get, address = EmailAddress("kiwi-test@kifi.com")).get
      val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).withHandle(OrganizationHandle("kifiorg")).saved
      val membership = organizationMembershipRepo.save(org.newMembership(userId = owner.id.get, role = OrganizationRole.ADMIN))
      (org, owner, membership)
    }
  }

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule()
  )

  "organization invite commander" should {
    "invite members" in {
      withDb(modules: _*) { implicit injector =>
        val invitees: Set[Either[Id[User], EmailAddress]] = Set(Right(EmailAddress("kiwi-test@kifi.com")))
        val (org, owner, _) = setup
        val inviteeEmails = invitees.collect { case Right(email) => email }
        val inviteeUserIds = invitees.collect { case Left(userId) => userId }
        val orgInvite = OrganizationInviteSendRequest(org.id.get, owner.id.get, inviteeEmails, inviteeUserIds, Some("would you like to join Kifi?"))
        val result = Await.result(orgInviteCommander.inviteToOrganization(orgInvite), new FiniteDuration(3, TimeUnit.SECONDS))

        result.isRight === true
        val inviteesWithAccess = result.right.get
        inviteesWithAccess.size === 1
        val invited = inviteesWithAccess.head
        invited must equalTo(Right(RichContact(EmailAddress("kiwi-test@kifi.com"))))
      }
    }

    "when inviting members already in the org" in {
      "return an error" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, _) = setup
          val invitees: Set[Either[Id[User], EmailAddress]] = Set(Left(owner.id.get))
          val inviter = db.readWrite { implicit session =>
            val bond = UserFactory.user.withName("James", "Bond").saved
            val membership: OrganizationMembership = org.newMembership(userId = bond.id.get, role = OrganizationRole.MEMBER)
            organizationMembershipRepo.save(membership.copy(permissions = (membership.permissions + OrganizationPermission.INVITE_MEMBERS)))
            bond
          }
          val inviteeEmails = invitees.collect { case Right(email) => email }
          val inviteeUserIds = invitees.collect { case Left(userId) => userId }
          val orgInvite = OrganizationInviteSendRequest(org.id.get, inviter.id.get, inviteeEmails, inviteeUserIds, Some("inviting the owner, what a shmuck"))
          val result = Await.result(orgInviteCommander.inviteToOrganization(orgInvite), new FiniteDuration(3, TimeUnit.SECONDS))

          result.isLeft === true
          result.left.get === OrganizationFail.ALREADY_A_MEMBER
        }
      }
    }

    "not invite members when" in {
      "the inviter cannot invite" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, _) = setup
          val invitees = Set.empty[Either[Id[User], EmailAddress]]
          val aMemberThatCannotInvite = db.readWrite { implicit session =>
            val bond = UserFactory.user.withName("James", "Bond").saved
            organizationMembershipRepo.save(org.newMembership(userId = bond.id.get, role = OrganizationRole.MEMBER))
            bond
          }
          val inviteeEmails = invitees.collect { case Right(email) => email }
          val inviteeUserIds = invitees.collect { case Left(userId) => userId }
          val orgInvite = OrganizationInviteSendRequest(org.id.get, aMemberThatCannotInvite.id.get, inviteeEmails, inviteeUserIds, None)
          val result = Await.result(orgInviteCommander.inviteToOrganization(orgInvite), new FiniteDuration(3, TimeUnit.SECONDS))

          result.isLeft === true
          val organizationFail = result.left.get
          organizationFail === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }
      "the inviter is not a member" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, _) = setup
          val invitees = Set.empty[Either[Id[User], EmailAddress]]
          val notAMember = db.readWrite { implicit session =>
            UserFactory.user.withName("James", "Bond").saved
          }
          val inviteeEmails = invitees.collect { case Right(email) => email }
          val inviteeUserIds = invitees.collect { case Left(userId) => userId }
          val result = Await.result(orgInviteCommander.inviteToOrganization(OrganizationInviteSendRequest(org.id.get, notAMember.id.get, inviteeEmails, inviteeUserIds, None)), new FiniteDuration(3, TimeUnit.SECONDS))

          result.isLeft === true
          val organizationFail = result.left.get
          organizationFail === OrganizationFail.NOT_A_MEMBER
        }
      }
    }

    "convert email invitations to userId" in {
      withDb(modules: _*) { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        db.readWrite { implicit session =>
          for (i <- 1 to 20) yield {
            orgInviteRepo.save(OrganizationInvite(organizationId = Id[Organization](1), inviterId = Id[User](1), role = OrganizationRole.MEMBER, emailAddress = Some(EmailAddress("kiwi@kifi.com"))))
          }
        }

        val orgInviteCommander = inject[OrganizationInviteCommander]
        val invitedUserId = db.readWrite { implicit session =>
          val userId = userRepo.save(User(firstName = "Kiwi", lastName = "Kifi")).id.get
          orgInviteCommander.convertPendingInvites(EmailAddress("kiwi@kifi.com"), userId)
          userId
        }
        val invites = db.readOnlyMaster { implicit session =>
          orgInviteRepo.getByEmailAddress(EmailAddress("kiwi@kifi.com"))
        }
        invites.foreach { invite =>
          invite.userId === Some(invitedUserId)
        }
        invites.length === 20
      }
    }

    "accept invitations" should {
      "succeed when there are valid invitations" in {
        withDb(modules: _*) { implicit injector =>
          val inviteCommander = inject[OrganizationInviteCommander]
          val inviteRepo = inject[OrganizationInviteRepo]
          val memberRepo = inject[OrganizationMembershipRepo]
          val inviterId = Id[User](1)
          val userId = Id[User](2)
          val (org, invite) = db.readWrite { implicit session =>
            UserFactory.user().withId(inviterId).saved
            UserFactory.user().withId(userId).saved
            val org = OrganizationFactory.organization().withOwner(inviterId).withHandle(OrganizationHandle("kifi")).saved
            memberRepo.save(org.newMembership(userId = inviterId, role = OrganizationRole.ADMIN))
            val invite = inviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviterId, userId = Some(userId), role = OrganizationRole.MEMBER))
            (org, invite)
          }
          inviteCommander.acceptInvitation(org.id.get, userId, invite.authToken) must haveClass[Right[OrganizationFail, OrganizationMembership]]
        }
      }

      "fail when there are no valid invitations" in {
        withDb(modules: _*) { implicit injector =>
          val inviteCommander = inject[OrganizationInviteCommander]
          val memberRepo = inject[OrganizationMembershipRepo]
          val inviterId = Id[User](1)
          val userId = Id[User](2)
          val org = db.readWrite { implicit session =>
            UserFactory.user().withId(inviterId).saved
            UserFactory.user().withId(userId).saved
            val org = OrganizationFactory.organization().withOwner(inviterId).withHandle(OrganizationHandle("kifi")).saved
            memberRepo.save(org.newMembership(userId = inviterId, role = OrganizationRole.ADMIN))
            org
          }
          inviteCommander.acceptInvitation(org.id.get, userId, "authToken") === Left(OrganizationFail.NO_VALID_INVITATIONS)
        }
      }
    }
  }
}
