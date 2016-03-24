package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.{ PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.db.Id
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.path.Path
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
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

    "route internal links correctly" in {
      "for a friend request" in {
        withDb(modules: _*) { implicit injector =>
          val deepLink = Json.obj("t" -> "fr")
          val link = Path("friends/requests").absolute
          deepLinkRouter.generateRedirectUrl(deepLink) === Some(link)
        }
      }
      "for a library" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, orgLib, personalLib) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            val orgLib = LibraryFactory.library().withOwner(owner).withOrganization(org).saved
            val personalLib = LibraryFactory.library().withOwner(owner).saved
            (org, owner, orgLib, personalLib)
          }

          val authToken = "abcdefg"

          // with auth tokens
          val deepLink1 = Json.obj("t" -> "lr", "lid" -> Library.publicId(orgLib.id.get), "at" -> authToken)
          val link1 = Path(s"${org.handle.value}/${orgLib.slug.value}").absolute + s"?authToken=$authToken"
          deepLinkRouter.generateRedirectUrl(deepLink1) === Some(link1)

          val deepLink2 = Json.obj("t" -> "lr", "lid" -> Library.publicId(personalLib.id.get), "at" -> authToken)
          val link2 = Path(s"${owner.username.value}/${personalLib.slug.value}").absolute + s"?authToken=$authToken"
          deepLinkRouter.generateRedirectUrl(deepLink2) === Some(link2)

          // without auth tokens
          val deepLink3 = Json.obj("t" -> "lr", "lid" -> Library.publicId(orgLib.id.get))
          val link3 = Path(s"${org.handle.value}/${orgLib.slug.value}").absolute
          deepLinkRouter.generateRedirectUrl(deepLink3) === Some(link3)

          val deepLink4 = Json.obj("t" -> "lr", "lid" -> Library.publicId(personalLib.id.get))
          val link4 = Path(s"${owner.username.value}/${personalLib.slug.value}").absolute
          deepLinkRouter.generateRedirectUrl(deepLink4) === Some(link4)
        }
      }
      "for an org" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            (org, owner)
          }

          val authToken = "abcdefg"

          // with auth tokens
          val deepLink1 = Json.obj("t" -> "oi", "oid" -> Organization.publicId(org.id.get), "at" -> authToken)
          val link1 = Path(s"${org.handle.value}").absolute + s"?authToken=$authToken"
          deepLinkRouter.generateRedirectUrl(deepLink1) === Some(link1)

          // without auth tokens
          val deepLink2 = Json.obj("t" -> "oi", "oid" -> Organization.publicId(org.id.get))
          val link2 = Path(s"${org.handle.value}").absolute
          deepLinkRouter.generateRedirectUrl(deepLink2) === Some(link2)
        }
      }
      "for a new follower" in {
        withDb(modules: _*) { implicit injector =>
          val user = db.readWrite { implicit session =>
            UserFactory.user().saved
          }
          val deepLink = Json.obj("t" -> "nf", "uid" -> user.externalId)
          val link = Path(s"${user.username.value}").absolute
          deepLinkRouter.generateRedirectUrl(deepLink) === Some(link)
        }
      }
    }
    "route external deep links" in {
      "for a discussion" in {
        withDb(modules: _*) { implicit injector =>
          val url = "http://www.google.com"
          val (uri, keep) = db.readWrite { implicit session =>
            val uri = normalizedURIInterner.internByUri(url, contentWanted = false)
            val keep = KeepFactory.keep().withTitle("Random Keeps Popping Up").withUri(uri).saved
            (uri, keep)
          }
          val keepPubId = Keep.publicId(keep.id.get)
          val deepLink = Json.obj("t" -> "m", "id" -> keepPubId, "uri" -> uri.externalId)
          deepLinkRouter.generateRedirect(deepLink) === Some(DeepLinkRedirect(url, Some(s"/messages/${keepPubId.id}")))
        }
      }

      "for a discussion to a keep page" in {
        withDb(modules: _*) { implicit injector =>
          val url = "http://www.google.com"
          val (uri, keep) = db.readWrite { implicit session =>
            val uri = normalizedURIInterner.internByUri(url, contentWanted = false)
            val library = LibraryFactory.library().saved
            val keep = KeepFactory.keep().withTitle("Random Keeps Popping Up").withUri(uri).withLibrary(library).saved
            (uri, keep)
          }
          val deepLink = Json.obj("t" -> "m", "id" -> Keep.publicId(keep.id.get), "uri" -> uri.externalId, "at" -> "randomAccessToken")
          val redirect = deepLinkRouter.generateDiscussionViewRedirect(deepLink, redirectToKeepPage = true)
          redirect === Some(DeepLinkRedirect(keep.path.relativeWithLeadingSlash + "?authToken=randomAccessToken", None))
        }
      }
    }
  }
}
