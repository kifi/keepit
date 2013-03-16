package com.keepit.common.healthcheck

import com.keepit.inject._
import com.keepit.common.mail.FakeOutbox
import com.keepit.test.EmptyApplication
import play.api.Play.current
import play.api.test.Helpers.running
import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxApplication
import com.keepit.shoebox.ShoeboxModule

class HealthcheckErrorTest extends Specification {

  "HealthcheckError" should {
    "create signature" in {
      def troubleMaker() =
        for (i <- 1 to 2)
          yield HealthcheckError(
            error = Some(
              new IllegalArgumentException(
                "foo error = " + i,
                new RuntimeException("cause is bar " + i))),
            callType = Healthcheck.API)
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
        HealthcheckError(
          error = Some(
            new IllegalArgumentException(
              "foo error = " + i,
              new RuntimeException("cause is bar " + i))),
          callType = Healthcheck.API)
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
