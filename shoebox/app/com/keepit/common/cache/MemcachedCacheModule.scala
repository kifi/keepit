package com.keepit.common.cache

import com.tzavellas.sse.guice.ScalaModule

import com.google.inject.Provides
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.TypeLiteral
import com.google.inject.{Provides, Inject, Singleton, TypeLiteral}
import com.keepit.common.db.slick.Repo
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.db.slick._
import org.joda.time.DateTime
import org.joda.time.LocalDate
import akka.actor.ActorSystem
import akka.actor.Scheduler
import javax.sql.DataSource
import com.tzavellas.sse.guice.ScalaModule
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.MySQL
import com.keepit.common.db.slick.H2
import com.keepit.common.db.slick.DataBaseComponent
import play.api.Play.current
import play.api.Play
import net.spy.memcached.{ConnectionFactoryBuilder, AddrUtil, MemcachedClient}
import net.spy.memcached.auth.{PlainCallbackHandler, AuthDescriptor}
import com.keepit.inject.AppScoped

class MemcachedCacheModule() extends ScalaModule {
  def configure(): Unit = {
    bind[FortyTwoCachePlugin].to[MemcachedCache].in[AppScoped]
  }

  @Singleton
  @Provides
  def spyMemcachedClient(): MemcachedClient = {
    if (Play.isTest) throw new IllegalStateException("memecach client should not be loaded in test!")

    System.setProperty("net.spy.log.LoggerImpl", "com.keepit.common.cache.MemcachedSlf4JLogger")

    lazy val singleHost = current.configuration.getString("memcached.host").map(AddrUtil.getAddresses)
    lazy val multipleHosts = current.configuration.getString("memcached.1.host").map { _ =>
      def accumulate(cacheNumber: Int): String = {
        current.configuration.getString("memcached." + cacheNumber + ".host").map { h => h + " " + accumulate(cacheNumber + 1) }.getOrElse("")
      }
      AddrUtil.getAddresses(accumulate(1))
    }

    val addrs = singleHost.orElse(multipleHosts)
      .getOrElse(throw new RuntimeException("Bad configuration for memcached: missing host(s)"))

    current.configuration.getString("memcached.user").map { memcacheUser =>
      val memcachePassword = current.configuration.getString("memcached.password").getOrElse {
        throw new RuntimeException("Bad configuration for memcached: missing password")
      }

      // Use plain SASL to connect to memcached
      val ad = new AuthDescriptor(Array("PLAIN"),
        new PlainCallbackHandler(memcacheUser, memcachePassword))
      val cf = new ConnectionFactoryBuilder()
        .setProtocol(ConnectionFactoryBuilder.Protocol.BINARY)
        .setAuthDescriptor(ad)
        .build()

      new MemcachedClient(cf, addrs)
    }.getOrElse {
      new MemcachedClient(addrs)
    }
  }
}
