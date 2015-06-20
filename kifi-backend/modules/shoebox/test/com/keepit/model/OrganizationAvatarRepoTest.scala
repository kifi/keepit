package com.keepit.model

import com.keepit.commanders.CroppedImageSize
import com.keepit.common.db.Id
import com.keepit.common.store.{ ImageSize, ImagePath }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import scala.collection.immutable.Range.Inclusive

class OrganizationAvatarRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Logo Repo" should {
    "save invites and get them by id" in {
      withDb() { implicit injector =>
        val orgAvatarRepo = inject[OrganizationAvatarRepo]
        val org = db.readWrite { implicit s =>
          val path: ImagePath = ImagePath("prefix", ImageHash("x"), ImageSize(100, 120), ProcessImageOperation.Crop, ImageFormat.JPG)
          orgAvatarRepo.save(OrganizationAvatar(organizationId = Id[Organization](1), position = Some(ImagePosition(0, 0)),
            width = 100, height = 120, format = ImageFormat.JPG, kind = ProcessImageOperation.Crop,
            imagePath = path, source = ImageSource.UserUpload, sourceFileHash = ImageHash("x"), sourceImageURL = Some("internets")))
        }

        db.readOnlyMaster { implicit s =>
          orgAvatarRepo.get(org.id.get) === org
        }
      }
    }

    "list by organization" in {
      withDb() { implicit injector =>
        val orgAvatarRepo = inject[OrganizationAvatarRepo]
        val orgId = Id[Organization](1)
        val range: Inclusive = 1 to 20
        db.readWrite { implicit session =>
          for (count <- range) {
            val path: ImagePath = ImagePath("prefix", ImageHash("x"), ImageSize(count, count), ProcessImageOperation.Crop, ImageFormat.JPG)
            orgAvatarRepo.save(OrganizationAvatar(organizationId = orgId, position = None, width = count, height = count,
              format = ImageFormat.JPG, kind = ProcessImageOperation.Crop, imagePath = path,
              source = ImageSource.UserUpload, sourceFileHash = ImageHash("X"), sourceImageURL = None))
          }
        }

        val avatarsForOrganization = db.readOnlyMaster { implicit s => orgAvatarRepo.getByOrganizationId(orgId) }
        avatarsForOrganization.length === range.length
        avatarsForOrganization.map(_.width).diff(range) === List.empty[Int]
      }
    }
  }
}
