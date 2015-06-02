package com.keepit.model

import com.keepit.commanders.CroppedImageSize
import com.keepit.common.db.Id
import com.keepit.common.store.{ ImageSize, ImagePath }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class OrganizationLogoRepoTest extends Specification with ShoeboxTestInjector {

  "Organization Logo Repo" should {
    "save invites and get them by id" in {
      withDb() { implicit injector =>
        val orgLogoRepo = inject[OrganizationLogoRepo]
        val org = db.readWrite { implicit s =>
          val path: ImagePath = ImagePath("prefix", ImageHash("x"), ImageSize(100, 120), ProcessImageOperation.Crop, ImageFormat.JPG)
          orgLogoRepo.save(OrganizationLogo(organizationId = Id[Organization](1), position = Some(ImagePosition(0, 0)),
            width = 100, height = 120, format = ImageFormat.JPG, kind = ProcessImageOperation.Crop,
            imagePath = path, source = ImageSource.UserUpload, sourceFileHash = ImageHash("x"), sourceImageURL = Some("internets")))
        }

        db.readOnlyMaster { implicit s =>
          orgLogoRepo.get(org.id.get) === org
        }
      }
    }
  }
}
