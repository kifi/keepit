package com.keepit.common.social

import java.io.File

import org.specs2.mutable._
import com.keepit.model._
import com.keepit.test._

import play.api.libs.json.Json
import com.google.inject.Injector
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.{EmailAddress, FakeMailModule}

class SocialUserImportEmailTest extends Specification with ShoeboxTestInjector {

  "SocialUserImportEmail" should {
    "import email" in {
      withDb(FakeHttpClientModule(), FakeMailModule()) { implicit injector =>
        val graphs = List(
            ("facebook_graph_andrew.json", "fb@andrewconner.org")
        )
        graphs map { case (filename, emailString) => testSocialUserImportEmail(filename, EmailAddress(emailString)) }
      }
    }
  }

  def testSocialUserImportEmail(jsonFilename: String, emailAddress: EmailAddress)(implicit injector: Injector) = {
    val user = db.readWrite {implicit s =>
      userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
    }
    val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format(jsonFilename))).mkString)
    val email = inject[FacebookSocialGraph].extractEmails(json)
      .map(em => inject[SocialUserImportEmail].importEmail(user.id.get, em)).head
    email.address === emailAddress
    db.readOnlyMaster{ implicit session =>
      emailAddressRepo.get(email.id.get).address === emailAddress
    }
  }

}
