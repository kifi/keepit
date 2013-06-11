package com.keepit.common.social

import java.io.File

import org.specs2.mutable._

import com.keepit.inject._
import com.keepit.model._
import com.keepit.test._

import play.api.Play.current
import play.api.libs.json.Json
import play.api.test.Helpers._

class SocialUserImportEmailTest extends Specification with DbRepos {

  "SocialUserImportEmail" should {
    "import email" in {
      running(new EmptyApplication().withFakeHttpClient()) {
        val graphs = List(
            ("facebook_graph_andrew.json", "fb@andrewconner.org")
        )
        graphs map { case (filename, email) => testSocialUserImportEmail(filename, email) }
      }
    }
  }

  def testSocialUserImportEmail(jsonFilename: String, emailString: String) = {
    val user = db.readWrite {implicit s =>
      userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
    }
    val json = Json.parse(io.Source.fromFile(new File("modules/common/test/com/keepit/common/social/%s".format(jsonFilename))).mkString)
    val email = inject[FacebookSocialGraph].extractEmails(json)
      .map(em => inject[SocialUserImportEmail].importEmail(user.id.get, em)).head
    email.address === emailString
    db.readOnly{ implicit session =>
      emailAddressRepo.get(email.id.get) === email
    }
  }

}
