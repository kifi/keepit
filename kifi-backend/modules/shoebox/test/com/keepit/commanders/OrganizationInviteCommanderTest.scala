package com.keepit.commanders

import java.util.concurrent.TimeUnit

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.abook.model.RichContact
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

class OrganizationInviteCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  type Invitation = (Either[Id[User], EmailAddress], OrganizationRole, Option[String])
  def orgInviteCommander(implicit injector: Injector) = inject[OrganizationInviteCommander]
  def organizationRepo(implicit injector: Injector) = inject[OrganizationRepo]
  def organizationMembershipRepo(implicit injector: Injector) = inject[OrganizationMembershipRepo]

  def setup(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      db.readWrite { implicit session =>
        val owner = UserFactory.user().withName("Kiwi", "Kiwi").saved
        val org = organizationRepo.save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
        val membership = organizationMembershipRepo.save(OrganizationMembership(organizationId = org.id.get, userId = owner.id.get, role = OrganizationRole.OWNER))
        (org, owner, membership)
      }
    }
  }

  val modules = Seq(
    FakeABookServiceClientModule()
  )
  "organization invite commander" should {
    "invite members" in {
      withDb(modules: _*) { implicit injector =>
        val invitees = Seq[Invitation]((Right(EmailAddress("kiwi-test@kifi.com")), OrganizationRole.MEMBER, Some("join, we have kiwis at kifi")))
        val (org, owner, membership) = setup
        val result = Await.result(orgInviteCommander.inviteUsersToOrganization(org.id.get, owner.id.get, invitees), new FiniteDuration(2, TimeUnit.SECONDS))

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
          val (org, owner, membership) = setup
          val invitees = Seq[Invitation]((Left(owner.id.get), OrganizationRole.MEMBER, Some("haha I just demoted you from owner")))
          val aMemberThatCannotInvite = db.readWrite { implicit session =>
            val bond = UserFactory.user.withName("James", "Bond").saved
            val membership: OrganizationMembership = OrganizationMembership(organizationId = org.id.get, userId = bond.id.get, role = OrganizationRole.MEMBER)
            organizationMembershipRepo.save(membership.copy(permissions = (membership.permissions + OrganizationPermission.INVITE_MEMBERS)))
            bond
          }
          val result = Await.result(orgInviteCommander.inviteUsersToOrganization(org.id.get, aMemberThatCannotInvite.id.get, invitees), new FiniteDuration(2, TimeUnit.SECONDS))

          result.isRight === true
          val inviteesWithRole = result.right.get
          inviteesWithRole.length === 0
        }
      }
    }

    "not invite members when" in {
      "the inviter cannot invite" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, membership) = setup
          val invitees = Seq[Invitation]()
          val aMemberThatCannotInvite = db.readWrite { implicit session =>
            val bond = UserFactory.user.withName("James", "Bond").saved
            organizationMembershipRepo.save(OrganizationMembership(organizationId = org.id.get, userId = bond.id.get, role = OrganizationRole.MEMBER))
            bond
          }
          val result = Await.result(orgInviteCommander.inviteUsersToOrganization(org.id.get, aMemberThatCannotInvite.id.get, invitees), new FiniteDuration(2, TimeUnit.SECONDS))

          result.isLeft === true
          val organizationFail = result.left.get
          organizationFail.message === "cannot_invite_members"
        }
      }
      "the inviter is not a member" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, membership) = setup
          val invitees = Seq[Invitation]()
          val notAMember = db.readWrite { implicit session =>
            UserFactory.user.withName("James", "Bond").saved
          }
          val result = Await.result(orgInviteCommander.inviteUsersToOrganization(org.id.get, notAMember.id.get, invitees), new FiniteDuration(2, TimeUnit.SECONDS))

          result.isLeft === true
          val organizationFail = result.left.get
          organizationFail.message === "inviter_not_a_member"
        }
      }
    }
  }
}
