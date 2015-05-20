package com.keepit.common.healthcheck

import com.keepit.common.zookeeper.ServiceDiscovery
import com.google.inject.{ Singleton, Provides }
import com.keepit.common.service.FortyTwoServices
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.common.time._
import com.keepit.common.service.FakeServiceModule
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.model.User

import scala.xml.NodeSeq

case class FakeAirbrakeModule() extends AirbrakeModule {

  def configure(): Unit = {
    install(FakeClockModule())
    install(FakeServiceModule())
    install(FakeDiscoveryModule())
    bind[AirbrakeNotifier].to[FakeAirbrakeNotifier]
  }

  @Provides
  def formatter(playMode: Mode, service: FortyTwoServices, serviceDiscovery: ServiceDiscovery): AirbrakeFormatter = {
    new AirbrakeFormatterImpl("fakeApiKey", Mode.Test, service, serviceDiscovery)
  }
}

case class FakeAirbrakeAndFormatterModule() extends AirbrakeModule {

  def configure(): Unit = {
    bind[AirbrakeNotifier].to[FakeAirbrakeNotifier]
  }

  @Provides
  def formatter(): AirbrakeFormatter = {
    new AirbrakeFormatter {
      private[healthcheck] def deploymentMessage: String = ""
      private[healthcheck] def format(error: AirbrakeError): NodeSeq = null
      def noticeError(error: ErrorWithStack, message: Option[String]): NodeSeq = null
    }
  }
}

@Singleton
class FakeAirbrakeNotifier() extends AirbrakeNotifier {
  var errors: List[AirbrakeError] = List()
  def errorCount(): Int = errors.size
  def reportDeployment(): Unit = {}
  def notify(errorException: Throwable): AirbrakeError = { println(errorException.toString); notify(AirbrakeError(errorException)) }
  def notify(errorMessage: String): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage))) }
  def notify(errorMessage: String, errorException: Throwable): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage), exception = errorException)) }
  def notify(errorException: Throwable, user: User): AirbrakeError = { println(errorException.toString); notify(AirbrakeError(errorException)) }
  def notify(errorMessage: String, user: User): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage))) }
  def notify(errorMessage: String, errorException: Throwable, user: User): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage), exception = errorException)) }
  def notify(error: AirbrakeError): AirbrakeError = {
    errors = error :: errors
    error
  }
  def panic(errorException: Throwable): AirbrakeError = { println(errorException.toString); notify(AirbrakeError(errorException).copy(panic = true)) }
  def panic(errorMessage: String): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage)).copy(panic = true)) }
  def panic(errorMessage: String, errorException: Throwable): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage), exception = errorException).copy(panic = true)) }
  def panic(errorException: Throwable, user: User): AirbrakeError = { println(errorException.toString); notify(AirbrakeError(errorException).copy(panic = true)) }
  def panic(errorMessage: String, user: User): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage)).copy(panic = true)) }
  def panic(errorMessage: String, errorException: Throwable, user: User): AirbrakeError = { println(errorMessage); notify(AirbrakeError(message = Some(errorMessage), exception = errorException).copy(panic = true)) }
  def panic(error: AirbrakeError): AirbrakeError = {
    errors = error.copy(panic = true) :: errors
    error
  }
}
