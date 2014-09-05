package com.keepit.common.social

import java.io.File

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.UserCommander
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable._
import com.keepit.model._
import com.keepit.test._

import play.api.libs.json.Json
import com.google.inject.Injector
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }

class SocialUserImportEmailTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeHttpClientModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxStoreModule(),
    FakeExternalServiceModule(),
    FakeScrapeSchedulerModule()
  )

  "SocialUserImportEmail" should {
    "import email" in {
      withDb(modules: _*) { implicit injector =>
        val graphs = List(
          ("facebook_graph_andrew.json", "fb@andrewconner.org")
        )
        graphs forall { case (filename, emailString) => testSocialUserImportEmail(filename, EmailAddress(emailString)) }
      }
    }
  }

  def testSocialUserImportEmail(jsonFilename: String, emailAddress: EmailAddress)(implicit injector: Injector) = {
    val user = db.readWrite { implicit s =>
      userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
    }
    val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format(jsonFilename))).mkString)
    val email = inject[FacebookSocialGraph].extractEmails(json)
      .map(em => inject[UserCommander].importSocialEmail(user.id.get, em)).head
    email.address === emailAddress
    db.readOnlyMaster { implicit session =>
      emailAddressRepo.get(email.id.get).address == emailAddress
    }
  }

}
