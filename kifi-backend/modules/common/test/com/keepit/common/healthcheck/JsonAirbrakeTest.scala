package com.keepit.common.healthcheck

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.controller.ReportedException
import com.keepit.common.time._
import com.keepit.common.zookeeper._
import com.keepit.test._
import com.keepit.inject.FakeFortyTwoModule
import com.keepit.common.actor._
import org.joda.time.DateTime
import org.specs2.mutable.Specification

import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.validation.{ Validator => JValidator }
import java.io.StringReader

import play.api.libs.json.{ JsArray, Json, JsValue }

import scala.xml._
import com.keepit.model.User

class JsonAirbrakeTest extends Specification with CommonTestInjector {
  "Json Airbrake Formatter" should {
    val modules = Seq(FakeFortyTwoModule(), FakeDiscoveryModule())

    "format to json" in {
      withInjector(modules: _*) { implicit injector =>
        val formatter = inject[JsonAirbrakeFormatter]
        val error = new IllegalArgumentException("hi there", new Exception("middle thing", new IllegalStateException("its me")))
        val json: JsValue = formatter.format(error)
        (json \ "notifier" \ "name").as[String] === "S42"
      }
    }

    "have an environment" in {
      withInjector(modules: _*) { implicit injector =>
        val formatter = inject[JsonAirbrakeFormatter]
        val json = formatter.format(new Exception("There's an Error!"))
        Option(json \ "environment") !== None
      }
    }

    "have a context" in {
      withInjector(modules: _*) { implicit injector =>
        val formatter = inject[JsonAirbrakeFormatter]
        val json = formatter.format(AirbrakeError(new Exception("There's an Error!"), userName = Some("test")))
        Option(json \ "context") !== None
      }
    }

    "populate fields for json correctly" in {
      withInjector(modules: _*) { implicit injector =>
        val error = AirbrakeError(exception = new Exception("There's an Error!"), message = Some("Message"),
          userId = Some(Id[User](1234)), userName = Some("username"),
          url = Some("url"), params = Map(), method = Some("GET"), headers = Map(),
          id = ExternalId[AirbrakeError], createdAt = DateTime.now(), panic = false, aggregateOnly = false)

        val formatter = inject[JsonAirbrakeFormatter]
        val json = formatter.format(error)

        val notifier = json \ "notifier"
        (notifier \ "name").asOpt[String] === Some("S42")
        (notifier \ "version").asOpt[String] === Some("0.0.2")
        (notifier \ "url").asOpt[String] === Some("https://admin.kifi.com/admin")

        val errors = json \ "errors"
        errors must haveClass[JsArray]

        val context = json \ "context"
        (context \ "userName").asOpt[String] === Some("username")
        (context \ "userId").asOpt[String] === Some("1234")
        (context \ "userEmail").asOpt[String] === None
      }
    }

    "json formatter does not change message" in {
      withInjector(modules: _*) { implicit injector =>
        val error = AirbrakeError(exception = new Exception("There's an Error!"), message = Some("Message"))

        val formatter = inject[JsonAirbrakeFormatter]
        val json = formatter.format(error)
        ((json \ "errors")(0) \ "message").asOpt[String] === Some("[0L]Message java.lang.Exception: There's an Error!")
      }
    }

    "clean json formatter" in {
      withInjector(modules: _*) { implicit injector =>
        val formatter = inject[JsonAirbrakeFormatter]
        val error = AirbrakeError(message = Some("Execution exception in null:null"), exception = new IllegalArgumentException("hi there"))
        val json = formatter.format(error)
        ((json \ "errors")(0) \ "message").asOpt[String] === Some("[0L] java.lang.IllegalArgumentException: hi there")
      }
    }
  }
}

