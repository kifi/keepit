package com.keepit.commanders

import com.keepit.common.concurrent.WatchableExecutionContext
import com.keepit.common.db.Id
import com.keepit.common.net.UserAgent
import com.keepit.common.service.IpAddress
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class UserIpAddressCommanderTest extends Specification with ShoeboxTestInjector {
  "UserIpAddressCommander" should {
    "correctly simplify user agent strings" in {
      withInjector() { implicit injector =>
        val commander = inject[UserIpAddressCommander]
        val inputs = Seq(
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:20.0) Gecko/20100101 Firefox/20.0",
          "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11",
          "iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"
        )
        val answers = Seq(
          "BROWSER",
          "BROWSER",
          "KIFI IPHONE APP"
        )
        for ((inp, ans) <- inputs zip answers) {
          commander.simplifyUserAgent(UserAgent(inp)) === ans
        }

        1 === 1
      }
    }

    "log and recall a user's ip" in {
      withDb() { implicit injector =>
        val commander = inject[UserIpAddressCommander]

        commander.logUser(Id[User](1), IpAddress("127.0.0.1"), UserAgent("iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"))
        inject[WatchableExecutionContext].drain()
        commander.totalNumberOfLogs() === 1
      }
    }
  }
}
