package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.NotificationCategory.fromElectronicMailCategory
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.OrganizationInviteFactory.organizationInvite
import com.keepit.model.OrganizationInviteFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ OrganizationFactory, _ }
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.Duration.standardDays
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration._

class OrganizationInviteCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty

  def setup(implicit injector: Injector): (Organization, User, User) = {
    db.readWrite { implicit session =>
      val owner = UserFactory.user().withName("Kiwi", "Kiwi").saved
      val user = UserFactory.user().withEmailAddress("kiwi-test@kifi.com").saved
      val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).withHandle(OrganizationHandle("kifiorg")).withWeakMembers().saved
      (org, owner, user)
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
        val (org, owner, user) = setup
        val inviteeEmails = invitees.collect { case Right(email) => email }
        val inviteeUserIds = invitees.collect { case Left(userId) => userId }
        val orgInvite = OrganizationInviteSendRequest(org.id.get, owner.id.get, inviteeEmails, inviteeUserIds, Some("would you like to join Kifi?"))
        val result = Await.result(orgInviteCommander.inviteToOrganization(orgInvite), Duration.Inf)

        result.isRight === true
        val inviteesWithAccess = result.right.get
        inviteesWithAccess.size === 1
        val invited = inviteesWithAccess.head
        invited must equalTo(Left(BasicUser.fromUser(user)))
      }
    }

    "when inviting members already in the org" in {
      "return an error" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, _) = setup
          val invitees: Set[Either[Id[User], EmailAddress]] = Set(Left(owner.id.get))
          val inviteeEmails = invitees.collect { case Right(email) => email }
          val inviteeUserIds = invitees.collect { case Left(userId) => userId }
          val orgInvite = OrganizationInviteSendRequest(org.id.get, owner.id.get, inviteeEmails, inviteeUserIds, Some("inviting the owner, what a shmuck"))
          val result = Await.result(orgInviteCommander.inviteToOrganization(orgInvite), Duration.Inf)

          result must beLeft
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
            val bond = UserFactory.user().withName("James", "Bond").saved
            orgMembershipRepo.save(OrganizationMembership(organizationId = org.id.get, userId = bond.id.get, role = OrganizationRole.MEMBER))
            bond
          }
          val inviteeEmails = invitees.collect { case Right(email) => email }
          val inviteeUserIds = invitees.collect { case Left(userId) => userId }
          val orgInvite = OrganizationInviteSendRequest(org.id.get, aMemberThatCannotInvite.id.get, inviteeEmails, inviteeUserIds, None)
          val result = Await.result(orgInviteCommander.inviteToOrganization(orgInvite), Duration.Inf)

          result must beLeft
          val organizationFail = result.left.get
          organizationFail === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }
    }

    "convert email invitations to userId after user verifies email" in {
      withDb(modules: _*) { implicit injector =>
        val orgInviteRepo = inject[OrganizationInviteRepo]
        db.readWrite { implicit session =>
          for (i <- 1 to 20) yield {
            orgInviteRepo.save(OrganizationInvite(organizationId = Id[Organization](1), inviterId = Id[User](1), role = OrganizationRole.MEMBER, emailAddress = Some(EmailAddress("kiwi@kifi.com"))))
          }
        }

        val pendingInvite = inject[PendingInviteCommander]
        val invitedUserId = db.readWrite { implicit session =>
          val userId = userRepo.save(User(firstName = "Kiwi", lastName = "Kifi")).id.get
          pendingInvite.convertPendingOrgInvites(EmailAddress("kiwi@kifi.com"), userId)
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
          val (org, user, invite) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            val invite = inviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = owner.id.get, userId = Some(user.id.get), role = OrganizationRole.MEMBER))
            (org, user, invite)
          }
          inviteCommander.acceptInvitation(org.id.get, user.id.get, Some(invite.authToken)) must beRight
        }
      }

      "fail when there are no valid invitations" in {
        withDb(modules: _*) { implicit injector =>
          val inviteCommander = inject[OrganizationInviteCommander]
          val memberRepo = inject[OrganizationMembershipRepo]
          val (org, user) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            (org, user)
          }
          inviteCommander.acceptInvitation(org.id.get, user.id.get, Some("authToken")) === Left(OrganizationFail.NO_VALID_INVITATIONS)
        }
      }
    }
    "cancel invites" in {
      "remove extra org permissions from a user when their invite is cancelled" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, invitee) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val invitee = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner.id.get).secret().saved
            (org, owner, invitee)
          }

          db.readOnlyMaster { implicit session =>
            permissionCommander.getOrganizationPermissions(org.id.get, Some(invitee.id.get)) must not contain OrganizationPermission.VIEW_ORGANIZATION
          }

          val inviteResponse = orgInviteCommander.inviteToOrganization(OrganizationInviteSendRequest(org.id.get, owner.id.get, Set.empty, Set(invitee.id.get)))
          Await.result(inviteResponse, Duration.Inf) must beRight

          val invite = db.readOnlyMaster { implicit session =>
            val invites = orgInviteRepo.getByOrgAndUserId(org.id.get, invitee.id.get)
            invites must haveSize(1)
            permissionCommander.getOrganizationPermissions(org.id.get, Some(invitee.id.get)) must contain(OrganizationPermission.VIEW_ORGANIZATION)
            invites.head
          }

          orgInviteCommander.cancelOrganizationInvites(OrganizationInviteCancelRequest(org.id.get, owner.id.get, Set.empty, Set(invitee.id.get))) must beRight

          db.readOnlyMaster { implicit session =>
            orgInviteRepo.getByOrgAndUserId(org.id.get, invitee.id.get) must beEmpty
            permissionCommander.getOrganizationPermissions(org.id.get, Some(invitee.id.get)) must not contain (OrganizationPermission.VIEW_ORGANIZATION)
          }
        }
      }
    }

    "send invite reminder emails" in {
      "sends the email on command" in withDb(modules: _*) { implicit injector =>
        val orgInvite = db.readWrite { implicit session =>
          val (org, owner, user) = setup
          organizationInvite().withOrganization(org).withEmailAddress(EmailAddress("cam@panthers.com")).withInviter(owner).saved
        }

        orgInvite.remindersSent === 0
        val emailOpt = Await.result(orgInviteCommander.sendInviteReminder(orgInvite), 5 seconds)
        emailOpt must beSome

        db.readOnlyMaster { implicit session =>
          val updatedOrgInvite = orgInviteRepo.get(orgInvite.id.get)
          updatedOrgInvite.remindersSent === 1
          updatedOrgInvite.lastReminderSentAt must beSome
          val Seq(email) = electronicMailRepo.all()
          email === emailOpt.get
          email.to === Seq(orgInvite.emailAddress.get)
          email.htmlBody.value must contain("reminder to join my team")
          fromElectronicMailCategory(email.category) === NotificationCategory.NonUser.ORGANIZATION_INVITATION_REMINDER
        }
      }

      "sends reminder emails to those who have an old pending invite" in withDb(modules: _*) { implicit injector =>
        val now = inject[Clock].now()
        def daysAgo(n: Int) = now.minusDays(n)

        val (org1, org2, invites) = db.readWrite { implicit session =>
          val owner = UserFactory.user().withName("Roger", "Goodell").saved
          val org1 = OrganizationFactory.organization().withName("Carolina Panthers").withOwner(owner).withHandle(OrganizationHandle("keeppounding")).withWeakMembers().saved
          val org2 = OrganizationFactory.organization().withName("Denver Broncos").withOwner(owner).withHandle(OrganizationHandle("broncos")).withWeakMembers().saved

          inject[OrganizationExperimentRepo].save(OrganizationExperiment(orgId = org1.id.get, experimentType = OrganizationExperimentType.ORG_INVITE_REMINDERS))

          def invite = organizationInvite().withOrganization(org1).withInviter(owner)
          val invites = Seq(
            // 2 of these guys should get a reminder email, 2 should not b/c they were invited < 3 days ago
            invite.withEmailAddress(EmailAddress("mtolbert@panthers.com")).withCreatedAt(daysAgo(2)).saved,
            invite.withEmailAddress(EmailAddress("golsen@panthers.com")).withCreatedAt(daysAgo(3).plusMinutes(1)).saved,
            invite.withEmailAddress(EmailAddress("jstewart@panthers.com")).withCreatedAt(daysAgo(4)).saved,
            invite.withEmailAddress(EmailAddress("cnewton@panthers.com")).withCreatedAt(daysAgo(5)).saved,

            // this org isn't in the ORG_INVITE_REMINDERS experiment, this shouldn't send a reminder
            organizationInvite().withOrganization(org2).withCreatedAt(daysAgo(6)).saved
          )

          (org1, org2, invites)
        }

        val remindersSentByOrg = Await.result(orgInviteCommander.sendInviteReminders(standardDays(3), Int.MaxValue), 5 seconds)
        remindersSentByOrg.size === 2
        remindersSentByOrg.head === (org1.id.get, 2)
        remindersSentByOrg(1) === (org2.id.get, 0)

        def assertNotSentTo(inv: OrganizationInvite) = { inv.lastReminderSentAt must beNone; inv.remindersSent === 0 }
        def assertSentTo(inv: OrganizationInvite) = { inv.lastReminderSentAt must beSome; inv.remindersSent === 1 }

        db.readOnlyMaster { implicit session =>
          val Seq(otherguy, newton, stewart, olsen, tolbert) = orgInviteRepo.all().sortBy(_.createdAt)
          tolbert === invites.head
          olsen === invites(1)

          assertNotSentTo(tolbert)
          assertNotSentTo(olsen)
          assertNotSentTo(otherguy)
          assertSentTo(stewart)
          assertSentTo(newton)

          electronicMailRepo.count === 2
        }
      }
    }
  }
}
