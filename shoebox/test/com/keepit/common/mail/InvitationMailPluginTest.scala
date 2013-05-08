package com.keepit.common.mail

import scala.Some

import org.joda.time.Days
import org.specs2.mutable.Specification

import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks
import com.keepit.inject.inject
import com.keepit.model._
import com.keepit.test.{FakeClock, EmptyApplication, DbRepos}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.Play.current
import play.api.test.Helpers.running

class InvitationMailPluginTest extends TestKit(ActorSystem()) with Specification with DbRepos {
  "InvitationMailPlugin" should {
    "send emails to newly accepted users" in {
      running(new EmptyApplication().withTestActorSystem(system).withFakeMail()) {
        val plugin = inject[InvitationMailPlugin]
        val fakeOutbox = inject[FakeOutbox]
        val (user, addr) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Smith"))
          val addr = emailAddressRepo.save(EmailAddress(userId = user.id.get, address = "andrew@smith.org"))
          (user, addr)
        }
        plugin.notifyAcceptedUser(user.id.get)
        val mail = fakeOutbox.mails.head
        mail.to.address === addr.address
        mail.from.address === EmailAddresses.CONGRATS.address
        mail.subject must not startWith("Reminder")
      }
    }
    "resend emails after the appropriate amount of time" in {
      running(new EmptyApplication().withTestActorSystem(system).withFakeMail()) {
        val clock = inject[FakeClock]
        val plugin = inject[InvitationMailPlugin]
        val fakeOutbox = inject[FakeOutbox]
        val (user, sui1, sui2) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Eishay", lastName = "Jacobs"))
          emailAddressRepo.save(EmailAddress(userId = user.id.get, address = "eishay@jacobs.org"))
          socialUserInfoRepo.save(SocialUserInfo(
            userId = Some(user.id.get), fullName = "Eishay Jacobs", socialId = SocialId("ejacobs"),
            networkType = SocialNetworks.FACEBOOK))

          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Smith"))
          emailAddressRepo.save(EmailAddress(userId = user1.id.get, address = "andrew@smith.org"))
          val sui1 = socialUserInfoRepo.save(SocialUserInfo(
            userId = Some(user1.id.get), fullName = "Andrew Smith", socialId = SocialId("asmith"),
            networkType = SocialNetworks.FACEBOOK))

          val user2 = userRepo.save(User(firstName = "Greg", lastName = "Conner"))
          emailAddressRepo.save(EmailAddress(userId = user2.id.get, address = "greg@conner.org"))
          val sui2 = socialUserInfoRepo.save(SocialUserInfo(
            userId = Some(user2.id.get), fullName = "Greg Conner", socialId = SocialId("gconner"),
            networkType = SocialNetworks.FACEBOOK))

          (user, sui1, sui2)
        }
        val inv1 = db.readWrite { implicit s =>
          invitationRepo.save(Invitation(senderUserId = user.id, recipientSocialUserId = sui1.id.get,
            state = InvitationStates.ADMIN_ACCEPTED))
        }

        clock += Days.TWO

        val inv2 = db.readWrite { implicit s =>
          invitationRepo.save(Invitation(senderUserId = user.id, recipientSocialUserId = sui2.id.get,
            state = InvitationStates.ADMIN_ACCEPTED))
        }

        clock += Days.TWO

        plugin.resendNotifications()
        val mail = fakeOutbox.mails.find (_.to.address == "andrew@smith.org")
        mail must beSome
        mail.get.subject must startWith("Reminder")
        fakeOutbox.mails.exists(_.to.address == "greg@conner.org") must beFalse

        fakeOutbox.mails.clear()

        clock += Days.TWO

        plugin.resendNotifications()
        fakeOutbox.mails.exists(_.to.address == "andrew@smith.org") must beFalse
        fakeOutbox.mails.exists(_.to.address == "greg@conner.org") must beTrue

        fakeOutbox.mails.clear()
        db.readWrite { implicit s =>
          invitationRepo.save(inv1.withState(InvitationStates.JOINED))
          invitationRepo.save(inv2.withState(InvitationStates.JOINED))
        }

        clock += Days.SIX

        fakeOutbox.mails must beEmpty
      }
    }
  }
}
