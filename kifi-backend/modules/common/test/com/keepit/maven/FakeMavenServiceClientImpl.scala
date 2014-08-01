package com.keepit.curator

import scala.concurrent.Future
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.service.ServiceType
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.curator.model.Recommendation

class FakeCuratorServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def adHocRecos(userId: Id[User], n: Int): Future[Seq[Recommendation]] = Future.successful(Seq.empty)

}
