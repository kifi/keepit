package com.keepit.common.healthcheck

import com.keepit.common.zookeeper._
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
import play.api.Mode.Test

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

    "deployment payload" in {
      withInjector(TestFortyTwoModule(), FakeDiscoveryModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        formatter.deploymentMessage === "api_key=fakeApiKey&deploy[rails_env]=test&deploy[scm_repository]=https://github.com/FortyTwoEng/keepit&deploy[scm_revision]=0.0.0"
      }
    }

    "format stack trace with x2 cause" in {
      withInjector(TestFortyTwoModule(), FakeDiscoveryModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = new IllegalArgumentException("hi there", new Exception("middle thing" , new IllegalStateException("its me")))
        val xml = formatter.noticeError(ErrorWithStack(error), None)
        val lines = (xml \\ "line").toVector
        lines.head === <line method="java.lang.IllegalArgumentException: hi there" file="InjectorProvider.scala" number="39"/>
        lines(1) === <line method="com.keepit.inject.InjectorProvider#withInjector" file="InjectorProvider.scala" number="39"/>
        lines.size === 188
      }
    }

    "format stack trace with x1 cause" in {
      withInjector(TestFortyTwoModule(), FakeDiscoveryModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = new IllegalArgumentException("hi there", new IllegalStateException("its me"))
        val xml = formatter.noticeError(ErrorWithStack(error), None)
        val lines = (xml \\ "line").toVector
        lines.head === <line method="java.lang.IllegalArgumentException: hi there" file="InjectorProvider.scala" number="39"/>
        lines(1) === <line method="com.keepit.inject.InjectorProvider#withInjector" file="InjectorProvider.scala" number="39"/>
        lines.size === 125
      }
    }

    "format only error" in {
      withInjector() { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = AirbrakeError(new IllegalArgumentException("hi there", new IllegalStateException("its me", new NullPointerException())))
        val xml = formatter.format(error)
        validate(xml)
        (xml \ "api-key").head === <api-key>fakeApiKey</api-key>
        (xml \ "error" \ "class").head === <class>java.lang.NullPointerException</class>
        (xml \ "error" \ "message").head === <message>[0L] java.lang.NullPointerException</message>
        (xml \ "error" \ "backtrace" \ "line").size === 188
        (xml \ "server-environment" \ "environment-name").head === <environment-name>test</environment-name>
        (xml \ "server-environment" \ "app-version").head.text === "0.0.0"
        (xml \ "server-environment" \ "project-root").head.text === "TEST_MODE"
      }
    }

    "format with url and no params" in {
      withInjector(TestFortyTwoModule(), FakeDiscoveryModule(), StandaloneTestActorSystemModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = AirbrakeError(
            exception = new IllegalArgumentException("hi there"),
            message = None,
            url = Some("http://www.kifi.com/hi"),
            method = Some("POST"))
        val xml = formatter.format(error)
        validate(xml)
        (xml \ "api-key").head === <api-key>fakeApiKey</api-key>
        (xml \ "error" \ "class").head === <class>java.lang.IllegalArgumentException</class>
        (xml \ "error" \ "message").head === <message>[0L] java.lang.IllegalArgumentException: hi there</message>
        (xml \ "error" \ "backtrace" \ "line").size === 62
        (xml \ "server-environment" \ "environment-name").head === <environment-name>test</environment-name>
        (xml \ "request" \ "url").head === <url>http://www.kifi.com/hi</url>
        (xml \ "request" \ "action").head === <action>POST</action>
      }
    }

    "create signature" in {
      def troubleMaker() =
        for (i <- 1 to 2)
          yield {
            AirbrakeError(
              new IllegalArgumentException("foo error = " + i,
                new RuntimeException("cause is bar " + i)))
          }
      def method1() = troubleMaker()
      def method2() = method1()
      def method3() = method2()
      def method4() = method3()
      def method5() = method3()
      val errors = method4() ++ method5()
      errors(0).signature === errors(1).signature
      errors(0).signature === errors(2).signature
      errors(0).signature === errors(3).signature
    }

    "causeStacktraceHead stack depth" in {
      def troubleMaker(i: Int) =
            AirbrakeError(
              new IllegalArgumentException("foo error = " + i,
                new RuntimeException("cause is bar " + i)))
      def method1() = troubleMaker(0)::troubleMaker(1)::Nil
      def method2() = method1()
      def method3() = method2()
      def method4() = method3()
      def method5() = method3()
      val errors = method4() ++ method5()
      errors(0).causeStacktraceHead(3) === errors(1).causeStacktraceHead(3)
      errors(0).causeStacktraceHead(3) === errors(2).causeStacktraceHead(3)
      errors(0).causeStacktraceHead(3) === errors(3).causeStacktraceHead(3)
      errors(0).causeStacktraceHead(4) === errors(3).causeStacktraceHead(4)
      errors(0).causeStacktraceHead(5) !== errors(3).causeStacktraceHead(5)
    }
  }
}
