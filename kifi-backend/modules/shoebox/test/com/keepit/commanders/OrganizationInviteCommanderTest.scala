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
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

class OrganizationInviteCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  def orgInviteCommander(implicit injector: Injector) = inject[OrganizationInviteCommander]
  def organizationRepo(implicit injector: Injector) = inject[OrganizationRepo]
  def organizationMembershipRepo(implicit injector: Injector) = inject[OrganizationMembershipRepo]

  def setup(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      db.readWrite { implicit session =>
        val owner = UserFactory.user().withName("Kiwi", "Kiwi").withEmailAddress("kiwi-test@kifi.com").saved
        userEmailAddressRepo.save(UserEmailAddress(userId = owner.id.get, address = owner.primaryEmail.get))
        val org = organizationRepo.save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
        val membership = organizationMembershipRepo.save(org.newMembership(userId = owner.id.get, role = OrganizationRole.OWNER))
        (org, owner, membership)
      }
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
        val invitees = Seq[OrganizationMemberInvitation](OrganizationMemberInvitation(Right(EmailAddress("kiwi-test@kifi.com")), OrganizationRole.MEMBER, Some("join, we have kiwis at kifi")))
        val (org, owner, _) = setup
        val result = Await.result(orgInviteCommander.inviteToOrganization(org.id.get, owner.id.get, invitees), new FiniteDuration(3, TimeUnit.SECONDS))

        result.isRight === true
        val inviteesWithAccess = result.right.get
        inviteesWithAccess.length === 1
        val invitation = inviteesWithAccess(0)
        invitation must equalTo((Right(RichContact(EmailAddress("kiwi-test@kifi.com"), None, None, None, None)), OrganizationRole.MEMBER))
      }
    }

    // TODO: when more roles are added test this.
    "when inviting members already in the org" in {
      "promote invited members" in {
        withDb(modules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          // We can't test this right now.
          // We only have two Roles and we can't promote someone from MEMBER to OWNER through an invitation.
          // Changing the owner should be a separate process to take control of an organization since an OWNER should be unique.
          skipped("Need more than two OrganizationRole to test this")
        }
      }

      "do not demote invited members" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, _) = setup
          val invitees = Seq[OrganizationMemberInvitation](OrganizationMemberInvitation(Left(owner.id.get), OrganizationRole.MEMBER, Some("I just demoted you from owner")))
          val inviter = db.readWrite { implicit session =>
            val bond = UserFactory.user.withName("James", "Bond").saved
            val membership: OrganizationMembership = org.newMembership(userId = bond.id.get, role = OrganizationRole.MEMBER)
            organizationMembershipRepo.save(membership.copy(permissions = (membership.permissions + OrganizationPermission.INVITE_MEMBERS)))
            bond
          }
          val result = Await.result(orgInviteCommander.inviteToOrganization(org.id.get, inviter.id.get, invitees), new FiniteDuration(3, TimeUnit.SECONDS))

          result.isRight === true
          val inviteesWithRole = result.right.get
          inviteesWithRole.length === 0
        }
      }
    }

    "not invite members when" in {
      "the inviter cannot invite" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, _) = setup
          val invitees = Seq[OrganizationMemberInvitation]()
          val aMemberThatCannotInvite = db.readWrite { implicit session =>
            val bond = UserFactory.user.withName("James", "Bond").saved
            organizationMembershipRepo.save(org.newMembership(userId = bond.id.get, role = OrganizationRole.MEMBER))
            bond
          }
          val result = Await.result(orgInviteCommander.inviteToOrganization(org.id.get, aMemberThatCannotInvite.id.get, invitees), new FiniteDuration(3, TimeUnit.SECONDS))

          result.isLeft === true
          val organizationFail = result.left.get
          organizationFail === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }
      "the inviter is not a member" in {
        withDb(modules: _*) { implicit injector =>
          val (org, _, _) = setup
          val invitees = Seq[OrganizationMemberInvitation]()
          val notAMember = db.readWrite { implicit session =>
            UserFactory.user.withName("James", "Bond").saved
          }
          val result = Await.result(orgInviteCommander.inviteToOrganization(org.id.get, notAMember.id.get, invitees), new FiniteDuration(3, TimeUnit.SECONDS))

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
        db.readWrite { implicit session =>
          orgInviteCommander.convertPendingInvites(EmailAddress("kiwi@kifi.com"), Id[User](42))
        }
        val invites = db.readOnlyMaster { implicit session =>
          orgInviteRepo.getByEmailAddress(EmailAddress("kiwi@kifi.com"))
        }
        invites.foreach { invite =>
          invite.userId === Some(Id[User](42))
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
            val org = inject[OrganizationRepo].save(Organization(name = "kifi", ownerId = inviterId, handle = None))
            memberRepo.save(org.newMembership(userId = inviterId, role = OrganizationRole.OWNER))
            val invite = inviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviterId, userId = Some(userId), role = OrganizationRole.MEMBER))
            (org, invite)
          }
          inviteCommander.acceptInvitation(org.id.get, userId, invite.authToken) must haveClass[Right[OrganizationFail, OrganizationMembership]]
        }
      }

      "pick the highest role" in {
        withDb(modules: _*) { implicit injector =>
          val inviteCommander = inject[OrganizationInviteCommander]
          val inviteRepo = inject[OrganizationInviteRepo]
          val memberRepo = inject[OrganizationMembershipRepo]
          val inviterId = Id[User](1)
          val userId = Id[User](2)
          val (org, invite) = db.readWrite { implicit session =>
            UserFactory.user().withId(inviterId).saved
            UserFactory.user().withId(userId).saved
            val org = inject[OrganizationRepo].save(Organization(name = "kifi", ownerId = inviterId, handle = None))
            memberRepo.save(org.newMembership(userId = inviterId, role = OrganizationRole.OWNER))
            val invite = inviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviterId, userId = Some(userId), role = OrganizationRole.MEMBER))
            inviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviterId, userId = Some(userId), role = OrganizationRole.OWNER))
            (org, invite)
          }
          inviteCommander.acceptInvitation(org.id.get, userId, invite.authToken) must haveClass[Right[OrganizationFail, OrganizationMembership]]

          db.readOnlyMaster { implicit session =>
            memberRepo.getByOrgIdAndUserId(org.id.get, userId).map(_.role) === Some(OrganizationRole.OWNER)
          }
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
            val org = inject[OrganizationRepo].save(Organization(name = "kifi", ownerId = inviterId, handle = None))
            memberRepo.save(org.newMembership(userId = inviterId, role = OrganizationRole.OWNER))
            org
          }
          inviteCommander.acceptInvitation(org.id.get, userId, "authToken") === Left(OrganizationFail.NO_VALID_INVITATIONS)
        }
      }

      "fail when there are invitations but none are valid" in {
        withDb(modules: _*) { implicit injector =>
          val inviteCommander = inject[OrganizationInviteCommander]
          val inviteRepo = inject[OrganizationInviteRepo]
          val memberRepo = inject[OrganizationMembershipRepo]
          val inviterId = Id[User](1)
          val userId = Id[User](2)
          val (org, invite) = db.readWrite { implicit session =>
            UserFactory.user().withId(inviterId).saved
            UserFactory.user().withId(userId).saved
            val org = inject[OrganizationRepo].save(Organization(name = "kifi", ownerId = inviterId, handle = None))
            memberRepo.save(org.newMembership(userId = inviterId, role = OrganizationRole.MEMBER))
            val invite = inviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviterId, userId = Some(userId), role = OrganizationRole.OWNER))
            (org, invite)
          }
          inviteCommander.acceptInvitation(org.id.get, userId, invite.authToken) === Left(OrganizationFail.NO_VALID_INVITATIONS)
        }
      }
    }
  }
}
