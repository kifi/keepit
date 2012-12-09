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

  val errors = MutableList[HealthcheckError]()

  def errorCountFuture(): Future[Int] = Future[Int](errorCount())

  def errorCount(): Int = errors.size

  def resetErrorCount(): Unit = errors.clear

  def addError(error: HealthcheckError): HealthcheckError = {
    errors += error
    error
  }

  def reportStart(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "start", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
  def reportStop(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "stop", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
}
