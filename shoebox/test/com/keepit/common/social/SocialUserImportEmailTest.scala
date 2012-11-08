package com.keepit.common.social

import com.keepit.inject._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import ru.circumflex.orm._
import java.util.concurrent.TimeUnit
import com.keepit.controllers._
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication
import com.keepit.common.net.HttpClientImpl
import com.keepit.model.User
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}
import com.keepit.common.net.FakeHttpClient
import com.keepit.model.SocialUserInfo
import play.api.Play
import java.net.URL
import java.io.File
import com.keepit.model.EmailAddress

@RunWith(classOf[JUnitRunner])
class SocialUserImportEmailTest extends SpecificationWithJUnit {

  "SocialUserImportEmail" should {
    "import email" in {
      running(new EmptyApplication().withFakeStore) {
        val graphs = List(
            ("facebook_graph_andrew.json", "fb@andrewconner.org")
        )
        graphs map { case (filename, email) => testSocialUserImportEmail(filename, email) }
        
      }
    }
  }
  
  def testSocialUserImportEmail(jsonFilename: String, emailString: String) = {
    val user = CX.withConnection { implicit c =>
      User(firstName = "Eishay", lastName = "Smith").save
    }
    val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format(jsonFilename))).mkString)
    val email = inject[SocialUserImportEmail].importEmail(user.id.get, Seq(json)).getOrElse(
        throw new Exception("fail getting email %s of %s".format(emailString, json.toString)))
    email.address === emailString
    CX.withConnection { implicit c =>
      EmailAddress.get(email.id.get) === email
    }
    
  }
  
}
