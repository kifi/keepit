package com.keepit.common.healthcheck

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.controller.ReportedException
import com.keepit.common.zookeeper._
import com.keepit.test._
import com.keepit.inject.FakeFortyTwoModule
import com.keepit.common.actor._
import org.specs2.mutable.Specification

import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.validation.{ Validator => JValidator }
import java.io.StringReader

import scala.xml._
import com.keepit.model.User

class AirbrakeTest extends Specification with CommonTestInjector {

  def validate(xml: NodeSeq) = {
    val schemaLang = "http://www.w3.org/2001/XMLSchema"
    val factory = SchemaFactory.newInstance(schemaLang)
    val schema = factory.newSchema(new StreamSource("test/com/keepit/common/healthcheck/airbrake_2_3.xsd"))
    val validator = schema.newValidator()
    validator.validate(new StreamSource(new StringReader(xml.toString)))
  }

  "AirbrakeTest" should {

    "deployment payload" in {
      withInjector(FakeFortyTwoModule(), FakeDiscoveryModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        formatter.deploymentMessage === "api_key=fakeApiKey&deploy[rails_env]=test&deploy[scm_repository]=https://github.com/kifi/keepit&deploy[scm_revision]=00000000-0000-TEST-0000000"
      }
    }

    "format stack trace with x2 cause" in {
      withInjector(FakeFortyTwoModule(), FakeDiscoveryModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = new IllegalArgumentException("hi there", new Exception("middle thing", new IllegalStateException("its me")))
        val xml = formatter.noticeError(ErrorWithStack(error), None)
        val lines = (xml \\ "line").toVector
        lines.head === <line method="java.lang.IllegalArgumentException: hi there" file="TestInjectorProvider.scala" number="18"/>
        lines(1) === <line method="com.keepit.test.TestInjectorProvider#withInjector" file="TestInjectorProvider.scala" number="18"/>
        lines.size must be greaterThan 150
      }
    }

    "format stack trace with x1 cause" in {
      withInjector(FakeFortyTwoModule(), FakeDiscoveryModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = new IllegalArgumentException("hi there", new IllegalStateException("its me"))
        val xml = formatter.noticeError(ErrorWithStack(error), None)
        val lines = (xml \\ "line").toVector
        lines.head === <line method="java.lang.IllegalArgumentException: hi there" file="TestInjectorProvider.scala" number="18"/>
        lines(1) === <line method="com.keepit.test.TestInjectorProvider#withInjector" file="TestInjectorProvider.scala" number="18"/>
        lines.size must be greaterThan 100
      }
    }

    "format only error" in {
      withInjector() { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = AirbrakeError(new IllegalArgumentException("hi there", new IllegalStateException("its me", new NullPointerException()))).cleanError
        val xml = formatter.format(error)
        validate(xml)
        (xml \ "api-key").head === <api-key>fakeApiKey</api-key>
        (xml \ "error" \ "class").head === <class>java.lang.NullPointerException</class>
        (xml \ "error" \ "message").head === <message>[0L] java.lang.NullPointerException</message>
        (xml \ "error" \ "backtrace" \ "line").size must be greaterThan 150
        (xml \ "server-environment" \ "environment-name").head === <environment-name>test</environment-name>
        (xml \ "server-environment" \ "app-version").head.text === "00000000-0000-TEST-0000000"
        (xml \ "server-environment" \ "project-root").head.text === "TEST_MODE"
      }
    }

    "clean formatter" in {
      withInjector() { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = AirbrakeError(message = Some("Execution exception in null:null"), exception = new IllegalArgumentException("hi there"))
        val xml = formatter.format(error)
        validate(xml)
        (xml \ "error" \ "message").head === <message>[0L] java.lang.IllegalArgumentException: hi there</message>
      }
    }

    "formatter not altering message" in {
      withInjector() { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = AirbrakeError(message = Some("Execution NPE in Foo.java:87"), exception = new IllegalArgumentException("hi there"))
        val xml = formatter.format(error)
        validate(xml)
        (xml \ "error" \ "message").head === <message>[0L]Execution NPE in Foo.java:87 java.lang.IllegalArgumentException: hi there</message>
      }
    }

    "format with url and no params" in {
      withInjector(FakeFortyTwoModule(), FakeDiscoveryModule(), FakeActorSystemModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = AirbrakeError(
          exception = new IllegalArgumentException("hi there"),
          message = None,
          url = Some("http://www.kifi.com/hi"),
          method = Some("POST")).cleanError
        val xml = formatter.format(error)
        validate(xml)
        (xml \ "api-key").head === <api-key>fakeApiKey</api-key>
        (xml \ "error" \ "class").head === <class>java.lang.IllegalArgumentException</class>
        (xml \ "error" \ "message").head === <message>[0L] java.lang.IllegalArgumentException: hi there</message>
        (xml \ "error" \ "backtrace" \ "line").size must be greaterThan 50
        (xml \ "server-environment" \ "environment-name").head === <environment-name>test</environment-name>
        (xml \ "request" \ "url").head === <url>http://www.kifi.com/hi</url>
        (xml \ "request" \ "action").head === <action>POST</action>
        (xml \ "request" \ "session" \ "var").head === <var key="Z-InternalErrorId">{ error.id.toString }</var>
        (xml \ "request" \ "session" \ "var").size === 1
      }
    }

    "format with user info" in {
      withInjector(FakeFortyTwoModule(), FakeDiscoveryModule(), FakeActorSystemModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        val error = AirbrakeError(
          exception = new IllegalArgumentException("hi there"),
          message = None,
          userId = Some(Id[User](42)),
          userName = Some("Robert Heinlein"),
          url = Some("http://www.kifi.com/hi"),
          method = Some("POST")).cleanError
        val xml = formatter.format(error)
        validate(xml)
        (xml \ "request" \ "session" \ "var")(0) === <var key="Z-InternalErrorId">{ error.id.toString }</var>
        ((xml \ "request" \ "session" \ "var")(1) \ "@key").toString === "Z-UserId"
        (xml \ "request" \ "session" \ "var")(1).text === "https://admin.kifi.com/admin/user/42"
        (xml \ "request" \ "session" \ "var")(2).toString === """<var key="Z-UserName">Robert Heinlein</var>"""
        (xml \ "request" \ "session" \ "var").size === 3
      }
    }

    "cleanError does nothing" in {
      val error = AirbrakeError(
        new IllegalArgumentException("foo error",
          new RuntimeException("cause is bar")))
      error === error.cleanError
    }

    "cleanError cleans ReportedException" in {
      val error = AirbrakeError(
        new ReportedException(ExternalId[AirbrakeError](),
          new IllegalArgumentException("foo error",
            new RuntimeException("cause is bar"))))
      val clean = error.cleanError
      error !== clean
      error.exception.getClass.toString === "class com.keepit.common.controller.ReportedException"
      clean.exception.getClass.toString === "class java.lang.IllegalArgumentException"
    }

    "cleanError cleans null:null" in {
      val error = AirbrakeError(
        new Exception("Execution exception in null:null",
          new IllegalArgumentException("foo error",
            new RuntimeException("cause is bar"))))
      val clean = error.cleanError
      error !== clean
      clean.exception.getClass.toString === "class java.lang.IllegalArgumentException"
    }

    "cleanError cleans Option" in {
      withInjector(FakeFortyTwoModule(), FakeDiscoveryModule()) { implicit injector =>
        val formatter = inject[AirbrakeFormatter]
        def method1() = try {
          Option(null).get
        } catch {
          case e: Throwable => throw new IllegalArgumentException("me iae", e)
        }
        try {
          method1()
          1 === 2
        } catch {
          case error: Throwable =>
            val xml = formatter.noticeError(ErrorWithStack(error), None)
            val lines = (xml \\ "line").toVector
            lines.head === <line method="java.lang.IllegalArgumentException: me iae" file="TestInjectorProvider.scala" number="18"/>
            lines(1) === <line method="com.keepit.test.TestInjectorProvider#withInjector" file="TestInjectorProvider.scala" number="18"/>
            lines.size must be greaterThan 100
        }
      }
    }

    "create signature" in {
      def troubleMaker() =
        for (i <- 1 to 2) yield {
          AirbrakeError(
            new IllegalArgumentException("foo error " + i,
              new RuntimeException("cause is bar " + i))).cleanError
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
            new RuntimeException("cause is bar " + i))).cleanError
      def method1() = troubleMaker(0) :: troubleMaker(1) :: Nil
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
