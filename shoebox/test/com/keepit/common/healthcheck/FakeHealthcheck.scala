package com.keepit.common.healthcheck

import com.keepit.common.mail._
import com.keepit.common.mail.EmailAddresses.ENG
import akka.actor.Actor._
import akka.actor._
import scala.collection.mutable.MutableList
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._
import scala.concurrent.Future
import scala.concurrent.duration._

case class FakeHealthcheck() extends HealthcheckPlugin {

  val _errors = MutableList[HealthcheckError]()

  def errorCountFuture(): Future[Int] = future {_errors.size}

  def errorCount(): Int = errors.size

  def resetErrorCount(): Unit = _errors.clear

  def errorsFuture(): Future[List[HealthcheckError]] = future {_errors.toList}

  def errors(): List[HealthcheckError] = _errors.toList

  def addError(error: HealthcheckError): HealthcheckError = {
    _errors += error
    error
  }

  def reportStart(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "start", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
  def reportStop(): ElectronicMail = ElectronicMail(from = ENG, to = ENG, subject = "stop", htmlBody = "", category = PostOffice.Categories.HEALTHCHECK)
}
