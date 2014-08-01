package com.keepit.common.cache

import com.keepit.serializer._
import play.api.libs.json._
import com.keepit.test._
import com.keepit.common.logging.AccessLog
import org.specs2.mutable.Specification
import scala.concurrent.duration.Duration
import com.keepit.common.healthcheck.FakeAirbrakeModule

case class TestJsonCacheData(name: String, age: Int)

case class TestJsonCacheKey(id: String) extends Key[TestJsonCacheData] {
  override val version = 1
  val namespace = "json_test_cache"
  def toKey = id
}

class TestJsonCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[TestJsonCacheKey, TestJsonCacheData](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Json.format[TestJsonCacheData])

case class TestBinaryCacheKey(id: String) extends Key[Array[Byte]] {
  override val version = 1
  val namespace = "binary_test_cache"
  def toKey = id
}

object DummyBinarySerializer extends BinaryFormat[Array[Byte]] {
  protected def writes(prefix: Byte, x: Array[Byte]) = {
    val rv = new Array[Byte](1 + x.length)
    rv(0) = prefix
    System.arraycopy(x, 0, rv, 1, x.length)
    rv
  }
  protected def reads(x: Array[Byte], offset: Int, length: Int) = {
    val rv = new Array[Byte](length)
    System.arraycopy(x, offset, rv, 0, length)
    rv
  }
}

class TestBinaryCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[TestBinaryCacheKey, Array[Byte]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(DummyBinarySerializer)

case class TestPrimitiveCacheKey(id: String) extends Key[Double] {
  override val version = 1
  val namespace = "primitive_test_cache"
  def toKey = id
}

class TestPrimitiveCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[TestPrimitiveCacheKey, Double](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class TestStringCacheKey(id: String) extends Key[String] {
  override val version = 1
  val namespace = "string_test_cache"
  def toKey = id
}

class TestStringCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[TestStringCacheKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class FortyTwoCacheTest extends Specification with CommonTestInjector {

  val cacheTestModules = Seq(FakeAirbrakeModule(), TestCacheModule())

  import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

  "JsonCacheImpl Instance" should {
    withInjector(cacheTestModules: _*) { implicit injector =>
      val cachePlugin = inject[FortyTwoCachePlugin]
      val cache = new TestJsonCache(inject[CacheStatistics],
        inject[AccessLog], (cachePlugin, Duration(7, "days")))
      "yield the value TestJsonCacheData('hello', 42)" in {
        cache.getOrElse(TestJsonCacheKey("hello_42"))(TestJsonCacheData("hello", 42)) === TestJsonCacheData("hello", 42)
      }
      "yield None without checking the db" in {
        cache.set(TestJsonCacheKey("missing_value"), None)
        cache.getOrElseOpt(TestJsonCacheKey("missing_value"))(Some(TestJsonCacheData("Error!", -42))) === None
      }
    }
  }

  "BinaryCacheImpl Instance" should {
    withInjector(cacheTestModules: _*) { implicit injector =>
      val cachePlugin = inject[FortyTwoCachePlugin]
      val cache = new TestBinaryCache(inject[CacheStatistics],
        inject[AccessLog], (cachePlugin, Duration(7, "days")))
      "yield the value Array[Byte](2,3,7)" in {
        cache.getOrElse(TestBinaryCacheKey("hello_42"))(Array[Byte](2, 3, 7)) === Array[Byte](2, 3, 7)
      }
      "yield None without checking the db" in {
        cache.set(TestBinaryCacheKey("missing_value"), None)
        cache.getOrElseOpt(TestBinaryCacheKey("missing_value"))(Some(Array[Byte](-2, -3, -7))) === None
      }
      "throw exception & invalidate cache when size exceeded" in {
        val key = TestBinaryCacheKey("foo")
        val v = Array[Byte](2, 3, 7)
        cache.set(key, Some(v))
        cache.get(key).get mustEqual v
        val big = Array.fill[Byte](1000000) { 4 }
        cache.set(key, Some(big)) must throwA[CacheSizeLimitExceededException]
        cache.get(key) mustEqual None
      }

    }
  }

  "PrimitiveCacheImpl Instance" should {
    withInjector(cacheTestModules: _*) { implicit injector =>
      val cachePlugin = inject[FortyTwoCachePlugin]
      val cache = new TestPrimitiveCache(inject[CacheStatistics],
        inject[AccessLog], (cachePlugin, Duration(7, "days")))
      "yield the value 4.2141" in {
        cache.getOrElse(TestPrimitiveCacheKey("hello_42"))(4.2141) === 4.2141
      }
      "yield None without checking the db" in {
        cache.getOrElseOpt(TestPrimitiveCacheKey("missing_value"))(None)
        cache.getOrElseOpt(TestPrimitiveCacheKey("missing_value"))(Some(-4.2547)) === None
      }
    }
  }

  "StringCacheImpl Instance" should {
    withInjector(cacheTestModules: _*) { implicit injector =>
      val cache = new TestStringCache(inject[CacheStatistics],
        inject[AccessLog], (inject[FortyTwoCachePlugin], Duration(7, "days")))
      "yield the value 'fortytwo'" in {
        cache.getOrElse(TestStringCacheKey("hello_42"))("fortytwo") === "fortytwo"
      }
      "yield None without checking the db" in {
        cache.set(TestStringCacheKey("missing_value"), None)
        cache.getOrElseOpt(TestStringCacheKey("missing_value"))(Some("owtytrof")) === None
      }
    }
  }
}
