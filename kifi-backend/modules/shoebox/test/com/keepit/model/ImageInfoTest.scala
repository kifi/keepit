package com.keepit.model

import com.keepit.common.store.ImagePath
import org.specs2.mutable.Specification
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import play.api.test.Helpers._

class ImageInfoTest extends Specification with ShoeboxApplicationInjector {

  "image info" should {
    "be persisted and loaded" in {
      running(new ShoeboxApplication()) {
        val imageInfo = db.readWrite { implicit s =>
          val uri = inject[NormalizedURIRepo].save(NormalizedURI.withHash("http://www.foobar.com"))
          val imageInfo = inject[ImageInfoRepo].save(ImageInfo(uriId = uri.id.get, url = Some(uri.url), path = ImagePath("path123")))
          imageInfo
        }
        db.readOnlyMaster { implicit s =>
          inject[ImageInfoRepo].get(imageInfo.id.get) === imageInfo
        }
      }
    }
  }
}
