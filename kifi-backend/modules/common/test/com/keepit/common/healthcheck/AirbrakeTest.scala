package com.keepit.common.healthcheck

import com.keepit.test._
import com.keepit.inject.TestFortyTwoModule
import com.keepit.common.net._
import com.keepit.common.actor._
import org.specs2.mutable.Specification
import akka.testkit.TestKit

import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import javax.xml.validation.{Validator => JValidator}
import org.xml.sax.SAXException
import java.io.StringReader

import scala.xml._

class AirbrakeTest extends Specification with TestInjector {

  def validate(xml: NodeSeq) = {
    val schemaLang = "http://www.w3.org/2001/XMLSchema"
    val factory = SchemaFactory.newInstance(schemaLang)
    val schema = factory.newSchema(new StreamSource("modules/common/test/com/keepit/common/healthcheck/airbrake_2_3.xsd"))
    val validator = schema.newValidator()
    validator.validate(new StreamSource(new StringReader(xml.toString)))
  }

  "AirbrakeTest" should {
    "format only error" in {
      withInjector(TestFortyTwoModule(), StandaloneTestActorSystemModule(), FakeHttpClientModule(), FakeAirbrakeModule()) { implicit injector =>
        val notifyer = inject[FakeAirbrakeNotifier]
        val error = AirbrakeError(new IllegalArgumentException("hi there"))
        val xml = notifyer.format(error)
        println(xml)
        validate(xml)
        (xml \ "api-key").head === <api-key>fakeApiKey</api-key>
        (xml \ "error" \ "class").head === <class>java.lang.IllegalArgumentException</class>
        (xml \ "error" \ "message").head === <message>java.lang.IllegalArgumentException: hi there</message>
        (xml \ "error" \ "backtrace" \ "line").head === <line method="withInjector" file="InjectorProvider.scala" number="39"/>
        (xml \ "error" \ "backtrace" \ "line").last === <line method="main" file="ForkMain.java" number="84"/>
        (xml \ "server-environment" \ "environment-name").head === <environment-name>Test</environment-name>
      }
    }

    "format with url and no params" in {
      withInjector(TestFortyTwoModule(), StandaloneTestActorSystemModule(), FakeHttpClientModule(), FakeAirbrakeModule()) { implicit injector =>
        val notifyer = inject[FakeAirbrakeNotifier]
        val error = AirbrakeError(
            exception = new IllegalArgumentException("hi there"),
            message = None,
            url = Some("http://www.kifi.com/hi"),
            method = Some("POST"))
        val xml = notifyer.format(error)
        println(xml)
        validate(xml)
        (xml \ "api-key").head === <api-key>fakeApiKey</api-key>
        (xml \ "error" \ "class").head === <class>java.lang.IllegalArgumentException</class>
        (xml \ "error" \ "message").head === <message>java.lang.IllegalArgumentException: hi there</message>
        (xml \ "error" \ "backtrace" \ "line").head === <line method="withInjector" file="InjectorProvider.scala" number="39"/>
        (xml \ "error" \ "backtrace" \ "line").last === <line method="main" file="ForkMain.java" number="84"/>
        (xml \ "server-environment" \ "environment-name").head === <environment-name>Test</environment-name>
        (xml \ "request" \ "url").head === <url>http://www.kifi.com/hi</url>
        (xml \ "request" \ "action").head === <action>POST</action>
      }
    }

    // "live send" in {
    //   withInjector(StandaloneTestActorSystemModule(), ProdHttpClientModule()) { implicit injector =>
    //     val actor = inject[ActorInstance[AirbrakeNotifierActor]]
    //     val elizaKey = "b903a091b834929686b95673c23fcb0d"
    //     val notifyer = new AirbrakeNotifier(elizaKey, actor)
    //     val error = AirbrakeError(new IllegalArgumentException("hi there"))
    //     notifyer.notifyError(error)
    //     Thread.sleep(5000)
    //     1 === 1
    //   }
    // }
  }
}
