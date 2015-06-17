package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper.KeepPersister

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
          organizationRepo.save(Organization(name = "OrgName", ownerId = user.id.get, handle = Some(PrimaryOrganizationHandle(OrganizationHandle("handle"), OrganizationHandle("handle")))))
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
          organizationMembershipRepo.save(OrganizationMembership(organizationId = org.id.get, userId = user.id.get, role = OrganizationRole.OWNER))
        }
        orgMember.id must beSome

        // OrganizationInviteRepo
        val organizationInviteRepo = inject[OrganizationInviteRepo]
        val invite = db.readWrite { implicit session =>
          organizationInviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = user.id.get, userId = user.id, role = OrganizationRole.OWNER))
        }
        invite.id must beSome

        // OrganizationLogoRepo
        val organizationLogoRepo = inject[OrganizationAvatarRepo]
        val logo = db.readWrite { implicit session =>
          val orgLogo = OrganizationAvatar(organizationId = org.id.get, position = Some(ImagePosition(0, 0)),
            width = 100, height = 100, format = ImageFormat.JPG, kind = ProcessImageOperation.Scale,
            imagePath = ImagePath(""), source = ImageSource.UserUpload, sourceFileHash = ImageHash("X"),
            sourceImageURL = Some("NONE"))
          organizationLogoRepo.save(orgLogo)
        }
        logo.id must beSome

        // HandleOwnershipRepo
        val handleOwnershipRepo = inject[HandleOwnershipRepo]
        db.readWrite { implicit session =>
          val saved = handleOwnershipRepo.save(HandleOwnership(handle = Handle("leo"), ownerId = Some(Id[User](134))))
          handleOwnershipRepo.get(saved.id.get).handle.value === "leo"
        }
      }
    }
  }
}
