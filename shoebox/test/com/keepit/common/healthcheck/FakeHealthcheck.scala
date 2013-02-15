package com.keepit.common.healthcheck

import com.keepit.common.mail._
import com.keepit.common.mail.EmailAddresses.ENG
import akka.actor.Actor._
import akka.actor._
import scala.collection.mutable.MutableList
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.concurrent.duration._

case class FakeHealthcheck() extends HealthcheckPlugin {

  val _errors = MutableList[HealthcheckError]()

  def errorCountFuture(): Future[Int] = Promise.successful(_errors.size)(new ExecutionContext() {
    def execute(runnable: Runnable): Unit = {}
    def reportFailure(t: Throwable): Unit = {}
  })

  def errorCount(): Int = errors.size

  def resetErrorCount(): Unit = _errors.clear

  def errorsFuture(): Future[List[HealthcheckError]] = Promise.successful(_errors.toList)(new ExecutionContext() {
    def execute(runnable: Runnable): Unit = {}
    def reportFailure(t: Throwable): Unit = {}
  })

  def errors(): List[HealthcheckError] = _errors.toList

  def addError(error: HealthcheckError): HealthcheckError = {
    _errors += error
    error
  }

  def reportStart(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "start", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
  def reportStop(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "stop", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
}
