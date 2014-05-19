package com.keepit.search.sharding

import com.keepit.common.db.Id
import com.keepit.common.net.ClientResponse
import com.keepit.common.routes.ServiceRoute
import com.keepit.common.zookeeper.{ServiceInstance, CustomRouter}
import com.keepit.model.NormalizedURI
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{JsObject, JsString, JsNull, JsValue}
import scala.concurrent.Future

class DistributedSearchRouter(client: SearchServiceClient) extends CustomRouter {

  type T = NormalizedURI

  @volatile
  private[this] var dispatcher = Dispatcher[T](Vector.empty[ServiceInstance], ()=>())

  def update(instances: Vector[ServiceInstance], forceReload: ()=>Unit): Unit = {
    dispatcher = Dispatcher[T](instances.filter(_.isUp), forceReload)
  }

  def plan(shards: Set[Shard[T]], maxShardsPerInstance: Int = Int.MaxValue): Seq[(ServiceInstance, Set[Shard[T]])] = {
    dispatcher.dispatch(shards, maxShardsPerInstance){ (instance, shards) => (instance, shards) }
  }

  def dispatch(plan: Seq[(ServiceInstance, Set[Shard[T]])], url: ServiceRoute, request: JsValue): Seq[Future[ClientResponse]] = {
    plan.map{ case (instance, shards) =>
      val body = JsObject(List("shards" -> JsString(ShardSpec.toString(shards)), "request" -> request))
      client.call(instance, url, body)
    }
  }

  def dispatch(shards: Set[Shard[T]], url: ServiceRoute, request: JsValue, maxShardsPerInstance: Int = Int.MaxValue): (Seq[Future[ClientResponse]]) = {
    dispatch(plan(shards, maxShardsPerInstance), url, request)
  }

  def call[R](uriId: Id[T], url: ServiceRoute, body: JsValue = JsNull): Future[ClientResponse] = {
    dispatcher.call(uriId){
      instance => client.call(instance, url, body)
    }
  }
}
