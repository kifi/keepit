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
    if (Play.isTest) throw new IllegalStateException("memcached client should not be loaded in test!")

    System.setProperty("net.spy.log.LoggerImpl", "com.keepit.common.cache.MemcachedSlf4JLogger")

    current.configuration.getString("elasticache.config.endpoint").map { endpoint =>
      new MemcachedClient(AddrUtil.getAddresses(endpoint))
    }.getOrElse(throw new RuntimeException("Bad configuration for memcached: missing host(s)"))
  }
}
