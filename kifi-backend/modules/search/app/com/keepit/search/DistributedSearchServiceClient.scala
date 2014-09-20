package com.keepit.search

import com.keepit.common.service.{ ServiceType, ServiceClient }
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.search.sharding.Shard
import com.keepit.model.NormalizedURI
import scala.concurrent.Future
import com.keepit.search.engine.result.LibraryShardResult

trait DistributedSearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH
  def distLibrarySearch(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: LibrarySearchRequest): Seq[Future[Set[LibraryShardResult]]] = ???
}
