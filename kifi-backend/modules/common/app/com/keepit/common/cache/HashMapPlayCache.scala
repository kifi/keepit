package com.keepit.common.cache

import play.api._
import play.api.cache._
import java.util.concurrent.ConcurrentHashMap
import com.keepit.common.logging.Logging

class HashMapPlayCache(app: Application) extends CachePlugin with Logging {
  override lazy val enabled = {
    !app.configuration.getString("hashmapplaycache").filter(_ == "disabled").isDefined
  }

  override def onStart() {
    
  }

  override def onStop() {
    
  }

  lazy val api = new CacheAPI {
    val cache = new ConcurrentHashMap[String, Any]()
    def set(key: String, value: Any, expiration: Int) {
      log.info(s"Setting cache key: $key, value: $value")
      cache.put(key, value)
    }

    def get(key: String): Option[Any] = {
      val g = cache.get(key)
      log.info(s"Getting cache key: $key, value: $g")
      Option(g)
    }

    def remove(key: String) {
      cache.remove(key)
    }
  }

}