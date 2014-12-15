package com.keepit.search.index.sharding

import com.keepit.common.db.Id
import com.keepit.common.net.ClientResponse
import com.keepit.common.routes.ServiceRoute
import com.keepit.common.zookeeper.{ ServiceInstance, CustomRouter }
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.search.{ DistributedSearchServiceClient }
import play.api.libs.json.{ JsObject, JsString, JsNull, JsValue }
import scala.concurrent.Future

class DistributedSearchRouter(client: DistributedSearchServiceClient) extends CustomRouter {

  type T = NormalizedURI

  @volatile
  private[this] var dispatcher = Dispatcher[T](Vector.empty[ServiceInstance], () => ())

  private def getRandomizer(userId: Id[User]) = {
    if (userId.id < 0L) Dispatcher.defaultRandomizer else Dispatcher.randomizer(userId.id)
  }

  def update(instances: Vector[ServiceInstance], forceReload: () => Unit): Unit = {
    dispatcher = Dispatcher[T](instances.filter(_.isUp), forceReload)
  }

  def plan(userId: Id[User], shards: Set[Shard[T]], maxShardsPerInstance: Int = Int.MaxValue): Seq[(ServiceInstance, Set[Shard[T]])] = {
    dispatcher.dispatch(shards, getRandomizer(userId), maxShardsPerInstance) { (instance, shards) => (instance, shards) }
  }

  def planRemoteOnly(userId: Id[User], maxShardsPerInstance: Int = Int.MaxValue): Seq[(ServiceInstance, Set[Shard[T]])] = {
    dispatcher.dispatch(getRandomizer(userId), maxShardsPerInstance) { (instance, shards) => (instance, shards) }
  }

  def dispatch[R](plan: Seq[(ServiceInstance, Set[Shard[T]])])(body: (ServiceInstance, Set[Shard[T]]) => R): Seq[R] = {
    plan.map(body.tupled)
  }

  def dispatch(plan: Seq[(ServiceInstance, Set[Shard[T]])], url: ServiceRoute, request: JsValue): Seq[Future[ClientResponse]] = {
    dispatch(plan) {
      case (instance, shards) =>
        val body = JsObject(List("shards" -> JsString(ShardSpec.toString(shards)), "request" -> request))
        client.call(instance, url, body)
    }
  }

  def call(userId: Id[User], uriId: Id[T], url: ServiceRoute, body: JsValue = JsNull): Future[ClientResponse] = {
    dispatcher.call[Future[ClientResponse]](uriId, Dispatcher.randomizer(userId.id)) {
      instance => client.call(instance, url, body)
    }
  }
}
