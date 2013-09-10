package com.keepit.common.cache


import com.keepit.serializer._
import play.api.libs.json._
import com.keepit.test._
import org.specs2.mutable.Specification
import scala.concurrent.duration.Duration
import com.keepit.inject._
import play.api.test.Helpers._



case class TestJsonCacheData(name: String, age: Int)

case class TestJsonCacheKey(id: String) extends Key[TestJsonCacheData] {
    override val version = 1
    val namespace = "json_test_cache"
    def toKey = id
}

class TestJsonCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[TestJsonCacheKey, TestJsonCacheData](innermostPluginSettings, innerToOuterPluginSettings:_*)(Json.format[TestJsonCacheData])




case class TestBinaryCacheKey(id: String) extends Key[Array[Byte]] {
  override val version = 1
  val namespace = "binary_test_cache"
  def toKey = id
}

object DummyBinarySerializer extends BinaryFormat[Array[Byte]] {
    def writes(x: Array[Byte]) = x
    def reads(x: Array[Byte]) = x
}

class TestBinaryCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[TestBinaryCacheKey, Array[Byte]](innermostPluginSettings, innerToOuterPluginSettings:_*)(DummyBinarySerializer)




case class TestPrimitiveCacheKey(id: String) extends Key[Double] {
    override val version = 1
    val namespace = "primitive_test_cache"
    def toKey = id
}

class TestPrimitiveCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[TestPrimitiveCacheKey, Double](innermostPluginSettings, innerToOuterPluginSettings:_*)




case class TestStringCacheKey(id: String) extends Key[String] {
    override val version = 1
    val namespace = "string_test_cache"
    def toKey =  id
}

class TestStringCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[TestStringCacheKey](innermostPluginSettings, innerToOuterPluginSettings:_*)



class FortyTwoCacheTest extends Specification with DeprecatedTestInjector {

    "JsonCacheImpl Instance" should {
        withInjector(EhCacheCacheModule()){ implicit injector =>
            val cachePlugin = inject[FortyTwoCachePlugin]
            val cache = new TestJsonCache((cachePlugin, Duration(7, "days")))
            "yield the value TestJsonCacheData('hello', 42)" in {
                cache.getOrElse(TestJsonCacheKey("hello_42"))(TestJsonCacheData("hello", 42)) === TestJsonCacheData("hello", 42)
            }
            "yield None without checking the db" in {
                cache.set(TestJsonCacheKey("missing_value"), None)
                cache.getOrElseOpt(TestJsonCacheKey("missing_value"))(Some(TestJsonCacheData("Error!", -42))) === None
            }
            "throw exception & invalidate cache when size exceeded" in {
                val key = TestJsonCacheKey("foo")
                val obj = Json.obj("foo" -> "bar")
                val v   = TestJsonCacheData(Json.stringify(obj), 42)
                cache.set(key, Some(v))
                cache.get(key).get mustEqual v
                val arr = Array.fill[JsObject](1000000) { obj }
                val big = Json.stringify(JsArray(arr))
                cache.set(key, Some(TestJsonCacheData(big, 42))) must throwA[CacheSizeLimitExceededException]
                cache.get(key) mustEqual None
            }
        } 
    }


    "BinaryCacheImpl Instance" should {
        withInjector(EhCacheCacheModule()){ implicit injector =>
            val cachePlugin = inject[FortyTwoCachePlugin]
            val cache = new TestBinaryCache((cachePlugin, Duration(7, "days")))
            "yield the value Array[Byte](2,3,7)" in {
                cache.getOrElse(TestBinaryCacheKey("hello_42"))(Array[Byte](2,3,7)) === Array[Byte](2,3,7)
            }
            "yield None without checking the db" in {
                cache.set(TestBinaryCacheKey("missing_value"), None)
                cache.getOrElseOpt(TestBinaryCacheKey("missing_value"))(Some(Array[Byte](-2,-3,-7))) === None
            }
          "throw exception & invalidate cache when size exceeded" in {
            val key = TestBinaryCacheKey("foo")
            val v   = Array[Byte](2,3,7)
            cache.set(key, Some(v))
            cache.get(key).get mustEqual v
            val big = Array.fill[Byte](1000000) { 4 }
            cache.set(key, Some(big)) must throwA[CacheSizeLimitExceededException]
            cache.get(key) mustEqual None
          }

        } 
    }


    "PrimitiveCacheImpl Instance" should {
        withInjector(EhCacheCacheModule()){ implicit injector =>
            val cachePlugin = inject[FortyTwoCachePlugin]
            val cache = new TestPrimitiveCache((cachePlugin, Duration(7, "days")))
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
        withInjector(EhCacheCacheModule()){ implicit injector =>
            val cachePlugin = inject[FortyTwoCachePlugin]
            val cache = new TestStringCache((cachePlugin, Duration(7, "days")))
            "yield the value 'fortytwo'" in {
                cache.getOrElse(TestStringCacheKey("hello_42"))("fortytwo") === "fortytwo"
            }
            "yield None without checking the db" in {
                cache.set(TestStringCacheKey("missing_value"), None)
                cache.getOrElseOpt(TestStringCacheKey("missing_value"))(Some("owtytrof")) === None
            }
          "throw exception & invalidate cache when size exceeded" in {
            val key = TestStringCacheKey("foo")
            val v   = "foobar"
            cache.set(key, Some(v))
            cache.get(key).get mustEqual v
            val chars = Array.fill[Char](1000000) { 'f' }
            val big = new String(chars)
            cache.set(key, Some(big)) must throwA[CacheSizeLimitExceededException]
            cache.get(key) mustEqual None
          }
        }
    }


}



