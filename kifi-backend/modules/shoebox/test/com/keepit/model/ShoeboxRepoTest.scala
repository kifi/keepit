package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.ImagePath
import com.keepit.model.LibrarySpace.UserSpace
import com.keepit.slack.models._
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper.KeepPersister

import scala.util.Success

class ShoeboxRepoTest extends Specification with ShoeboxApplicationInjector {

  "Shoebox Repos " should {
    "save and retrieve models" in {
      running(new ShoeboxApplication()) {
        // User Repo
        val user = db.readWrite { implicit session =>
          UserFactory.user().withName("Colin", "Lane").withUsername("colin-lane").saved
        }
        user.id must beSome

        // Library Repo
        val libraryRepo = inject[LibraryRepo]
        val lib = db.readWrite { implicit session =>
          libraryRepo.save(Library(name = "Library", ownerId = user.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("Slug"), memberCount = 0))
        }
        lib.id must beSome

        // OrganizationRepo
        val organizationRepo = inject[OrganizationRepo]
        val org = db.readWrite { implicit session =>
          organizationRepo.save(Organization(name = "OrgName", ownerId = user.id.get, primaryHandle = Some(PrimaryOrganizationHandle(OrganizationHandle("handle"), OrganizationHandle("handle"))), description = None, site = None))
        }
        org.id must beSome

        // KeepRepo
        val keep: Keep = db.readWrite { implicit session =>
          KeepFactory.keep().withLibrary(lib).withUser(user).withOrganizationId(org.id).saved
        }
        keep.id must beSome
        keep.organizationId === org.id

        // OrganizationMembershipRepo
        val organizationMembershipRepo = inject[OrganizationMembershipRepo]
        val orgMember = db.readWrite { implicit session =>
          organizationMembershipRepo.save(OrganizationMembership(organizationId = org.id.get, userId = user.id.get, role = OrganizationRole.ADMIN))
        }
        orgMember.id must beSome

        // OrganizationInviteRepo
        val organizationInviteRepo = inject[OrganizationInviteRepo]
        val invite = db.readWrite { implicit session =>
          organizationInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = user.id.get, userId = user.id, role = OrganizationRole.ADMIN))
        }
        invite.id must beSome

        // OrganizationAvatarRepo
        val organizationAvatarRepo = inject[OrganizationAvatarRepo]
        val avatar = db.readWrite { implicit session =>
          val orgAvatar = OrganizationAvatar(organizationId = org.id.get,
            width = 100, height = 100, format = ImageFormat.JPG, kind = ProcessImageOperation.Scale,
            imagePath = ImagePath(""), source = ImageSource.UserUpload, sourceFileHash = ImageHash("X"),
            sourceImageURL = Some("NONE"))
          organizationAvatarRepo.save(orgAvatar)
        }
        avatar.id must beSome

        // HandleOwnershipRepo
        val handleOwnershipRepo = inject[HandleOwnershipRepo]
        db.readWrite { implicit session =>
          val saved = handleOwnershipRepo.save(HandleOwnership(handle = Handle("leo"), ownerId = Some(Id[User](134))))
          handleOwnershipRepo.get(saved.id.get).handle.value === "leo"
        }

        // PasswordResetRepo
        val passwordResetRepo = inject[PasswordResetRepo]
        val passwordReset = db.readWrite { implicit session =>
          passwordResetRepo.createNewResetToken(Id(1), EmailAddress("leo@kifi.com"))
        }
        passwordReset.id must beSome

        // SlackTeam
        val slackTeamRepo = inject[SlackTeamRepo]
        val slackTeam = db.readWrite { implicit session =>
          val saved = slackTeamRepo.save(SlackTeam(
            slackTeamId = SlackTeamId("TFAKE"),
            slackTeamName = SlackTeamName("Fake"),
            organizationId = Some(org.id.get),
            generalChannelId = None,
            kifiBotUserId = Some(SlackUserId("UIAMABOT")),
            kifiBotToken = Some(SlackBotAccessToken("xbxb-bottoken"))
          ))
          slackTeamRepo.getBySlackTeamId(saved.slackTeamId) must beSome(saved)
          saved
        }

        // SlackTeamMembershipRepo
        val slackTeamMembershipRepo = inject[SlackTeamMembershipRepo]
        val slackAccount = SlackTeamMembershipInternRequest(
          Some(user.id.get),
          SlackUserId("UFAKE"),
          SlackUsername("@fake"),
          slackTeam.slackTeamId,
          slackTeam.slackTeamName,
          Some(SlackUserAccessToken("fake_token")),
          Set(SlackAuthScope.SearchRead),
          slackUser = None
        )
        db.readWrite { implicit session =>
          val saved = slackTeamMembershipRepo.internMembership(slackAccount)._1
          slackTeamMembershipRepo.getBySlackTeamAndUser(slackAccount.slackTeamId, slackAccount.slackUserId) must beSome(saved)
        }

        // SlackIncomingWebhookInfoRepo
        val slackWebhookRepo = inject[SlackIncomingWebhookInfoRepo]
        val channelName = SlackChannelName("#fake")
        val channelId = SlackChannelId("CFAKE")
        val hook = SlackIncomingWebhook(Some(channelId), channelName, "fake_url", "fake_config_url")
        db.readWrite { implicit session =>
          val saved = slackWebhookRepo.save(SlackIncomingWebhookInfo(
            slackUserId = slackAccount.slackUserId,
            slackTeamId = slackAccount.slackTeamId,
            slackChannelId = Some(channelId),
            webhook = hook,
            lastPostedAt = None
          ))
          slackWebhookRepo.get(saved.id.get) === saved
        }

        // LibraryToSlackChannel
        val integrationRequest = SlackIntegrationCreateRequest(slackAccount.userId.get, LibrarySpace.fromUserId(slackAccount.userId.get), slackAccount.slackUserId, slackAccount.slackTeamId, None, channelName, lib.id.get, status = SlackIntegrationStatus.On)
        val libraryToSlackChannelRepo = inject[LibraryToSlackChannelRepo]
        db.readWrite { implicit session =>
          val saved = libraryToSlackChannelRepo.internBySlackTeamChannelAndLibrary(integrationRequest)
          libraryToSlackChannelRepo.get(saved.id.get) === saved
        }

        // SlackChannelToLibrary
        val slackChannelToLibraryRepo = inject[SlackChannelToLibraryRepo]
        db.readWrite { implicit session =>
          val saved = slackChannelToLibraryRepo.internBySlackTeamChannelAndLibrary(integrationRequest)
          slackChannelToLibraryRepo.get(saved.id.get) === saved
        }
      }
    }
  }
}
