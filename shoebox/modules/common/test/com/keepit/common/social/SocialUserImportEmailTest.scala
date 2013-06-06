package com.keepit.common.social

import com.keepit.inject._
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import java.util.concurrent.TimeUnit
import com.keepit.controllers._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._
import com.keepit.common.net.HttpClientImpl
import com.keepit.model._
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}
import com.keepit.common.net.FakeHttpClient
import play.api.Play
import java.net.URL
import java.io.File

class SocialUserImportEmailTest extends Specification with DbRepos {

  "SocialUserImportEmail" should {
    "import email" in {
      running(new EmptyApplication()) {
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
    val email = inject[SocialUserImportEmail].importEmail(user.id.get, Seq(json)).getOrElse(
        throw new Exception("fail getting email %s of %s".format(emailString, json.toString)))
    email.address === emailString
    db.readOnly{ implicit session =>
      emailAddressRepo.get(email.id.get) === email
    }
  }

}
