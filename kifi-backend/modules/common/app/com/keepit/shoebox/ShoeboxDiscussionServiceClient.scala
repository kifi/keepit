package com.keepit.shoebox

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.ServiceClient
import com.keepit.common.zookeeper._
import com.keepit.model._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future }

trait ShoeboxDiscussionServiceClient extends ServiceClient {
  def createKeep(rawDiscussion: RawDiscussion): Future[Discussion]
  def linkKeepToDiscussion(keepId: KeepId, messageThreadId: MessageThreadId): Future[KeepId]
}

class ShoeboxDiscussionServiceClientImpl @Inject() (
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier,
    cacheProvider: ShoeboxCacheProvider,
    implicit val executionContext: ScalaExecutionContext) extends ShoeboxDiscussionServiceClient with Logging {

  def createKeep(rawDiscussion: RawDiscussion): Future[Discussion] = {
    val payload = Json.toJson(rawDiscussion)
    call(Shoebox.internal.createKeep(), payload).map { _.json.as[Discussion] }
  }

  def linkKeepToDiscussion(keepId: KeepId, messageThreadId: MessageThreadId): Future[KeepId] = {
    val payload = Json.toJson(KeepAndMessageThread(keepId, messageThreadId))
    call(Shoebox.internal.linkKeepToDiscussion(), payload).map { _.json.as[KeepId] }
  }
}
