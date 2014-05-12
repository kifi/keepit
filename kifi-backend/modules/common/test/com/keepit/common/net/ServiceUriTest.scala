package com.keepit.common.net

import org.specs2.mutable.Specification
import com.keepit.common.zookeeper._
import com.keepit.common.service._

class ServiceUriTest extends Specification {
  "ServiceUri" should {
    "have a good summery" in {
      val remoteService1 = RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE)
      val instance = new ServiceInstance(Node("/node_00000001"), false, remoteService1)
      val uri = new ServiceUri(instance, null, -1, "/this/is/the/path/and/it/may/be/very/very/very/very/very/very/very/very/very/very/very/very/very/very/long/so/it/must/be/chopped/a/bit/if/you/know/what/i/mean")
      uri.summary === "TM1:/this/is/the/path/and/it/may/be/very/very/very/very/very/very/very/very/very/very/very/very/very/..."
    }
  }
}
