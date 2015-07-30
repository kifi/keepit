package com.keepit.controllers.website

import java.io.File

import com.google.inject.Injector
import com.keepit.commanders.{ OrganizationAvatarConfiguration, OrganizationAvatarCommander }
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ UserFactory, _ }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import org.apache.commons.io.FileUtils
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class OrganizationAvatarControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {
  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeSocialGraphModule(),
    FakeHttpClientModule()
  )

  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def fakeFile = {
    val tf = TemporaryFile(new File("test/data/image1-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf.file)
    tf
  }
  private def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val owner = UserFactory.user().saved
      val rando = UserFactory.user().saved
      val org = OrganizationFactory.organization().withOwner(owner).saved
      (org, owner, rando)
    }
  }

  "OrganizationAvatarController" should {
    "support image uploads" in {
      "allow an owner to upload an organization avatar" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, rando) = setup()
          val pubId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(owner)
          val route = com.keepit.controllers.website.routes.OrganizationAvatarController.uploadAvatar(pubId, 0, 0, 50, 50)
          val request = FakeRequest(route.method, route.url).withBody(fakeFile)
          val result = inject[OrganizationAvatarController].uploadAvatar(pubId, 0, 0, 50, 50)(request)

          status(result) === OK
          Json.parse(contentAsString(result)) === Json.parse("""{"uploaded": "oa/26dbdc56d54dbc94830f7cfc85031481_200x200_cs.png"}""")

          inject[OrganizationAvatarCommander].getBestImage(org.id.get, OrganizationAvatarConfiguration.defaultSize) must haveClass[Some[OrganizationAvatar]]
        }
      }
      "forbid a non-member from uploading an organization avatar" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, rando) = setup()
          val pubId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(rando)
          val route = com.keepit.controllers.website.routes.OrganizationAvatarController.uploadAvatar(pubId, 0, 0, 50, 50)
          val request = FakeRequest(route.method, route.url).withBody(fakeFile)
          val result = inject[OrganizationAvatarController].uploadAvatar(pubId, 0, 0, 50, 50)(request)

          status(result) === FORBIDDEN
          inject[OrganizationAvatarCommander].getBestImage(org.id.get, OrganizationAvatarConfiguration.defaultSize).isEmpty === true
        }
      }
    }
  }

}
