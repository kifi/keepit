package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.path.Path
import com.keepit.common.store.ImagePath
import com.keepit.discussion.Message
import com.keepit.export.{ FullExportRequestRepo, FullExportStatus, FullExportRequest }
import com.keepit.model.LibrarySpace.UserSpace
import com.keepit.shoebox.path.ShortenedPathRepo
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
          KeepFactory.keep().withLibrary(lib).withUser(user).withEmail("ryan@kifi.com").saved
        }
        db.readOnlyMaster { implicit s =>
          keep.id must beSome
          keepRepo.get(keep.id.get) === keep
          ktlRepo.getAllByKeepId(keep.id.get) must haveSize(1)
          ktuRepo.getAllByKeepId(keep.id.get) must haveSize(1)
          kteRepo.getAllByKeepId(keep.id.get) must haveSize(1)
        }

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
            kifiBot = Some(KifiSlackBot(SlackUserId("UIAMABOT"), SlackBotAccessToken("xbxb-bottoken")))
          ))
          slackTeamRepo.getBySlackTeamId(saved.slackTeamId) must beSome(saved)
          saved
        }

        // SlackTeamMembershipRepo
        val slackTeamMembershipRepo = inject[SlackTeamMembershipRepo]
        val slackAccount = SlackTeamMembershipInternRequest(
          Some(user.id.get),
          SlackUserId("UFAKE"),
          slackTeam.slackTeamId,
          Some(SlackTokenWithScopes(SlackUserAccessToken("fake_token"), Set(SlackAuthScope.SearchRead))),
          slackUser = None
        )
        db.readWrite { implicit session =>
          val saved = slackTeamMembershipRepo.internMembership(slackAccount)._1
          slackTeamMembershipRepo.getBySlackTeamAndUser(slackAccount.slackTeamId, slackAccount.slackUserId) must beSome(saved)
        }

        // SlackChannelRepo
        val slackChannelRepo = inject[SlackChannelRepo]
        val slackChannel = db.readWrite { implicit session =>
          val saved = slackChannelRepo.getOrCreate(slackTeam.slackTeamId, SlackChannelId("CFAKE"), SlackChannelName("#fake"))
          slackChannelRepo.get(saved.id.get) === saved
          saved
        }

        // SlackIncomingWebhookInfoRepo
        val slackWebhookRepo = inject[SlackIncomingWebhookInfoRepo]
        val hook = SlackIncomingWebhook(slackChannel.slackChannelId, slackChannel.slackChannelName, "fake_url", "fake_config_url")
        db.readWrite { implicit session =>
          val saved = slackWebhookRepo.save(SlackIncomingWebhookInfo(
            slackUserId = slackAccount.slackUserId,
            slackTeamId = slackAccount.slackTeamId,
            slackChannelId = hook.channelId,
            url = hook.url,
            configUrl = hook.configUrl,
            lastPostedAt = None
          ))
          slackWebhookRepo.get(saved.id.get) === saved
        }

        // LibraryToSlackChannel
        val integrationRequest = SlackIntegrationCreateRequest(slackAccount.userId.get, slackAccount.slackUserId, slackAccount.slackTeamId, slackChannel.slackChannelId, lib.id.get, status = SlackIntegrationStatus.On)
        val libraryToSlackChannelRepo = inject[LibraryToSlackChannelRepo]
        val libToSlackChannel = db.readWrite { implicit session =>
          val saved = libraryToSlackChannelRepo.internBySlackTeamChannelAndLibrary(integrationRequest)
          libraryToSlackChannelRepo.get(saved.id.get) === saved
          saved
        }

        // SlackChannelToLibrary
        val slackChannelToLibraryRepo = inject[SlackChannelToLibraryRepo]
        db.readWrite { implicit session =>
          val saved = slackChannelToLibraryRepo.internBySlackTeamChannelAndLibrary(integrationRequest)
          slackChannelToLibraryRepo.get(saved.id.get) === saved
        }

        // SlackPushForKeep
        val slackPushForKeepRepo = inject[SlackPushForKeepRepo]
        val slackPushForKeep = db.readWrite { implicit session =>
          val saved = slackPushForKeepRepo.intern(SlackPushForKeep(
            slackTeamId = libToSlackChannel.slackTeamId,
            slackChannelId = libToSlackChannel.slackChannelId,
            slackUserId = libToSlackChannel.slackUserId,
            integrationId = libToSlackChannel.id.get,
            keepId = keep.id.get,
            timestamp = SlackTimestamp("42424242.00000"),
            text = "I am a keep-flavored meat popsicle",
            lastKnownEditability = SlackMessageEditability.EDITABLE,
            messageRequest = Some(SlackMessageRequest.fromKifi("wahoo"))
          ))
          slackPushForKeepRepo.get(saved.id.get) === saved
          saved
        }

        // SlackPushForMessage
        val slackPushForMessageRepo = inject[SlackPushForMessageRepo]
        val slackPushForMessage = db.readWrite { implicit session =>
          val saved = slackPushForMessageRepo.intern(SlackPushForMessage(
            slackTeamId = libToSlackChannel.slackTeamId,
            slackChannelId = libToSlackChannel.slackChannelId,
            slackUserId = libToSlackChannel.slackUserId,
            integrationId = libToSlackChannel.id.get,
            messageId = Id[Message](4257),
            timestamp = SlackTimestamp("42424242.00000"),
            text = "I am a message-flavored meat popsicle",
            lastKnownEditability = SlackMessageEditability.EDITABLE,
            messageRequest = Some(SlackMessageRequest.fromKifi("wahoo"))
          ))
          slackPushForMessageRepo.get(saved.id.get) === saved
          saved
        }

        db.readWrite { implicit s =>
          val p = Path("/brewstercorp/buffalo")
          val sp = inject[ShortenedPathRepo].intern(p)
          inject[ShortenedPathRepo].get(sp.id.get) === sp
        }

        db.readWrite { implicit s =>
          val req = inject[FullExportRequestRepo].intern(FullExportRequest(userId = user.id.get, status = FullExportStatus.NotStarted))
          inject[FullExportRequestRepo].get(req.id.get) === req
        }

        1 === 1
      }
    }
  }
}
