package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.{ PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.path.Path
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.{ UserFactory, _ }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class DeepLinkRouterTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule()
  )

  "DeepLinkRouter" should {
    implicit val context = HeimdalContext.empty
    implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
    implicit def deepLinkRouter(implicit injector: Injector) = inject[DeepLinkRouter]
    implicit class PathWrapper(pathOpt: Option[Path]) {
      def ===(that: Option[Path]) = pathOpt.map(_.absolute) must equalTo(that.map(_.absolute))
    }

    "route correctly" in {
      "for a friend request" in {
        withDb(modules: _*) { implicit injector =>
          val deepLink = Json.obj("t" -> "fr")
          val link = Path("friends/requests")
          deepLinkRouter.generateRedirect(deepLink) === Some(link)
        }
      }
      "for a library recommendation" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, orgLib, personalLib) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            val orgLib = LibraryFactory.library().withOwner(owner).withOrganization(org).saved
            val personalLib = LibraryFactory.library().withOwner(owner).saved
            (org, owner, orgLib, personalLib)
          }

          val deepLink1 = Json.obj("t" -> "lr", "lid" -> Library.publicId(orgLib.id.get))
          val link1 = Path(s"${org.handle.value}/${orgLib.slug.value}")
          deepLinkRouter.generateRedirect(deepLink1) === Some(link1)

          val deepLink2 = Json.obj("t" -> "lr", "lid" -> Library.publicId(personalLib.id.get))
          val link2 = Path(s"${owner.username.value}/${personalLib.slug.value}")
          deepLinkRouter.generateRedirect(deepLink2) === Some(link2)
        }
      }
      "for a new follower" in {
        withDb(modules: _*) { implicit injector =>
          val user = db.readWrite { implicit session =>
            UserFactory.user().saved
          }
          val deepLink = Json.obj("t" -> "nf", "uid" -> user.externalId)
          val link = Path(s"${user.username.value}")
          deepLinkRouter.generateRedirect(deepLink) === Some(link)
        }
      }
      "for a new message" in {
        skipped("need to figure out how to route a message-related deep-link")
        withDb(modules: _*) { implicit injector =>
          val messageId = ???
          val deepLink = Json.obj("t" -> "m", "id" -> messageId)
          val link = ???
          deepLinkRouter.generateRedirect(deepLink) === Some(link)
        }
      }
    }
  }
}
