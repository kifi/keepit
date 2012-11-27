package com.keepit.common.healthcheck

import com.keepit.common.mail._
import com.keepit.common.mail.EmailAddresses.ENG
import akka.actor.Actor._
import akka.actor._
import akka.dispatch.Future
import scala.collection.mutable.MutableList
import akka.dispatch.Promise
import akka.dispatch.ExecutionContext

case class FakeHealthcheck() extends Healthcheck {

  val errors = MutableList[HealthcheckError]()

  def errorCountFuture(): Future[Int] = Promise.successful(errors.size)(new ExecutionContext() {
    def execute(runnable: Runnable): Unit = {}
    def reportFailure(t: Throwable): Unit = {}
  })

  def addError(error: HealthcheckError): HealthcheckError = {
    errors += error
    error
  }

  def reportStart(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "start", htmlBody = "")
  def reportStop(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "stop", htmlBody = "")
}
